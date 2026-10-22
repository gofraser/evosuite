/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.junit.writer;

import org.evosuite.Properties;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the exact source the writer emits, for merged and per-test output, every scaffolding
 * placement, the three JUnit versions, thread-wrapped tests, mocks, replay snapshots and an empty
 * suite. The expected text lives in golden files among the test resources; run with
 * {@code -Devosuite.updateGolden=<resource directory>} to rewrite them.
 */
public class TestSuiteWriterOutputGoldenTest {

    private final Properties.OutputFormat format = Properties.TEST_FORMAT;
    private final boolean scaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean extensionMode = Properties.TEST_EXTENSION_MODE;
    private final boolean noRuntime = Properties.NO_RUNTIME_DEPENDENCY;
    private final Properties.OutputGranularity granularity = Properties.OUTPUT_GRANULARITY;
    private final boolean separateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
    private final Properties.TestNamingStrategy naming = Properties.TEST_NAMING_STRATEGY;
    private final boolean replaceCalls = Properties.REPLACE_CALLS;
    private final boolean virtualFs = Properties.VIRTUAL_FS;
    private final boolean resetStatic = Properties.RESET_STATIC_FIELDS;
    private final boolean virtualNet = Properties.VIRTUAL_NET;
    private final boolean replaceGui = Properties.REPLACE_GUI;
    private final String[] ignoreThreads = Properties.IGNORE_THREADS;
    private final String classPrefix = Properties.CLASS_PREFIX;

    @AfterEach
    public void restore() {
        Properties.TEST_FORMAT = format;
        Properties.TEST_SCAFFOLDING = scaffolding;
        Properties.TEST_EXTENSION_MODE = extensionMode;
        Properties.NO_RUNTIME_DEPENDENCY = noRuntime;
        Properties.OUTPUT_GRANULARITY = granularity;
        Properties.USE_SEPARATE_CLASSLOADER = separateClassLoader;
        Properties.TEST_NAMING_STRATEGY = naming;
        Properties.REPLACE_CALLS = replaceCalls;
        Properties.VIRTUAL_FS = virtualFs;
        Properties.RESET_STATIC_FIELDS = resetStatic;
        Properties.VIRTUAL_NET = virtualNet;
        Properties.REPLACE_GUI = replaceGui;
        Properties.IGNORE_THREADS = ignoreThreads;
        Properties.CLASS_PREFIX = classPrefix;
    }

    /** Where the scaffolding goes: inline in the test class, a separate file, nowhere, or an extension. */
    private enum Placement { INLINE, SEPARATE, NONE, EXTENSION }

    /** The suites under test. */
    private enum Suite { PLAIN, WRAPPED, MOCKS, MOCKS_REPLAY, EMPTY }

    /** Writer that hands back prepared results instead of executing the tests. */
    private static final class PreparedWriter extends TestSuiteWriter {
        private final Map<TestCase, ExecutionResult> prepared = new IdentityHashMap<>();

        @Override
        protected ExecutionResult runTest(TestCase test) {
            return prepared.get(test);
        }
    }

    private static ExecutionResult result(TestCase test) {
        ExecutionResult result = new ExecutionResult(test);
        result.setTrace(new ExecutionTraceImpl());
        return result;
    }

    private static TestCase intTest(int value) {
        TestCaseBuilder builder = new TestCaseBuilder();
        builder.appendIntPrimitive(value);
        return builder.getDefaultTestCase();
    }

    private static TestCase mockTest() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new FunctionalMockStatement(
                tc, Comparable.class, GenericClassFactory.get(Comparable.class)));
        tc.addStatement(new IntPrimitiveStatement(tc, 0));
        return tc;
    }

    private static PreparedWriter newWriter(Suite suite) {
        PreparedWriter writer = new PreparedWriter();
        List<TestCase> tests = new ArrayList<>();
        switch (suite) {
            case PLAIN:
                tests.add(intTest(1));
                tests.add(intTest(2));
                break;
            case WRAPPED:
                tests.add(intTest(1));
                tests.add(intTest(2));
                tests.add(intTest(3));
                break;
            case MOCKS:
            case MOCKS_REPLAY:
                tests.add(intTest(1));
                tests.add(mockTest());
                break;
            default:
                break;
        }
        List<ExecutionResult> results = new ArrayList<>();
        for (TestCase test : tests) {
            ExecutionResult result = result(test);
            writer.prepared.put(test, result);
            results.add(result);
        }
        if (suite == Suite.WRAPPED) {
            // The second test times out, the third hits a security exception.
            results.get(1).reportNewThrownException(
                    tests.get(1).size(), new TestCaseExecutor.TimeoutExceeded());
            results.get(2).setSecurityException(true);
        }
        writer.insertAllTests(tests);
        if (suite == Suite.MOCKS_REPLAY) {
            writer.setReplayScaffoldingSnapshot(Scaffolding.captureReplaySnapshot(tests, results));
        }
        return writer;
    }

    private static void configure(Properties.OutputFormat format, Placement placement,
                                  Properties.OutputGranularity granularity) {
        Properties.TEST_FORMAT = format;
        Properties.OUTPUT_GRANULARITY = granularity;
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.REPLACE_GUI = false;
        Properties.IGNORE_THREADS = new String[]{};
        Properties.CLASS_PREFIX = "";
        Properties.NO_RUNTIME_DEPENDENCY = placement == Placement.NONE;
        Properties.TEST_SCAFFOLDING = placement == Placement.SEPARATE;
        Properties.TEST_EXTENSION_MODE = placement == Placement.EXTENSION;
        Properties.USE_SEPARATE_CLASSLOADER = placement != Placement.EXTENSION;
    }

    private static String render(Suite suite, Properties.OutputFormat format, Placement placement,
                                 Properties.OutputGranularity granularity) throws IOException {
        configure(format, placement, granularity);
        Path dir = Files.createTempDirectory("evosuite-golden-");
        try {
            PreparedWriter writer = newWriter(suite);
            List<File> files = writer.writeTestSuite("GoldenTest", dir.toString(), Collections.emptyList());
            StringBuilder out = new StringBuilder();
            for (File file : files) {
                out.append("@@@ ").append(file.getName()).append('\n');
                out.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
            return out.toString();
        } finally {
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static List<Placement> placements(Properties.OutputFormat format) {
        List<Placement> placements = new ArrayList<>();
        placements.add(Placement.INLINE);
        placements.add(Placement.NONE);
        if (format != Properties.OutputFormat.JUNIT3) {
            placements.add(Placement.SEPARATE);
        }
        if (format == Properties.OutputFormat.JUNIT5) {
            placements.add(Placement.EXTENSION);
        }
        return placements;
    }

    private static Map<String, String> renderAll(Properties.OutputFormat format) throws IOException {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Suite suite : Suite.values()) {
            for (Placement placement : placements(format)) {
                for (Properties.OutputGranularity granularity : new Properties.OutputGranularity[]{
                        Properties.OutputGranularity.MERGED, Properties.OutputGranularity.TESTCASE}) {
                    rendered.put(suite + "/" + placement + "/" + granularity,
                            render(suite, format, placement, granularity));
                }
            }
        }
        return rendered;
    }

    private static String terminated(String text) {
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static String serialize(Map<String, String> rendered) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : rendered.entrySet()) {
            out.append("##### ").append(entry.getKey()).append('\n').append(terminated(entry.getValue()));
        }
        return out.toString();
    }

    private static Map<String, String> parse(String golden) {
        Map<String, String> sections = new LinkedHashMap<>();
        String key = null;
        StringBuilder body = new StringBuilder();
        for (String line : golden.split("\n", -1)) {
            if (line.startsWith("##### ")) {
                if (key != null) {
                    sections.put(key, body.toString());
                }
                key = line.substring(6);
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        if (key != null) {
            // The final split element is the empty string after the trailing newline.
            sections.put(key, body.substring(0, body.length() - 1));
        }
        return sections;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void check(Properties.OutputFormat format) throws Exception {
        Map<String, String> actual = renderAll(format);
        String resource = "golden/" + format + ".txt";
        String updateDir = System.getProperty("evosuite.updateGolden");
        if (updateDir != null) {
            Files.write(Paths.get(updateDir, format + ".txt"), serialize(actual).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String golden;
        try (InputStream in = TestSuiteWriterOutputGoldenTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing golden file " + resource);
            golden = new String(readAll(in), StandardCharsets.UTF_8);
        }
        Map<String, String> expected = parse(golden);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            assertEquals(expected.get(entry.getKey()), terminated(entry.getValue()), entry.getKey());
        }
    }

    @Test
    public void testJUnit3Output() throws Exception {
        check(Properties.OutputFormat.JUNIT3);
    }

    @Test
    public void testJUnit4Output() throws Exception {
        check(Properties.OutputFormat.JUNIT4);
    }

    @Test
    public void testJUnit5Output() throws Exception {
        check(Properties.OutputFormat.JUNIT5);
    }
}
