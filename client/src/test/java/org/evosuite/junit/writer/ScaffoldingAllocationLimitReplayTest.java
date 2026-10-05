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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Compile the real scaffolding and replay its initializer and allocation guard in a fresh JVM. */
public class ScaffoldingAllocationLimitReplayTest {
    @TempDir
    Path directory;
    private final Map<Field, Object> previous = new LinkedHashMap<>();

    @BeforeEach
    void configure() throws Exception {
        for (String name : Arrays.asList("CUMULATIVE_ARRAY_LIMIT_BYTES", "CLASS_PREFIX", "TARGET_CLASS",
                "REPLACE_CALLS", "VIRTUAL_FS", "VIRTUAL_NET", "RESET_STATIC_FIELDS", "REPLACE_GUI",
                "REPLACE_SYSTEM_IN", "RESET_STANDARD_STREAMS", "IGNORE_THREADS", "HEADLESS_MODE",
                "NO_RUNTIME_DEPENDENCY", "TEST_FORMAT", "TEST_SCAFFOLDING", "TEST_EXTENSION_MODE")) {
            Field field = Properties.class.getField(name);
            previous.put(field, field.get(null));
        }
        Properties.CLASS_PREFIX = "";
        Properties.TARGET_CLASS = "allocation.fixture.VirtualAllocation";
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = true; // Request the generated runtime initializer without attaching an agent.
        Properties.VIRTUAL_NET = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.REPLACE_GUI = false;
        Properties.REPLACE_SYSTEM_IN = false;
        Properties.RESET_STANDARD_STREAMS = false;
        Properties.IGNORE_THREADS = new String[0];
        Properties.HEADLESS_MODE = true;
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = true;
        Properties.TEST_EXTENSION_MODE = false;
    }

    @AfterEach
    void restore() throws Exception {
        for (Map.Entry<Field, Object> entry : previous.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    @Test
    void disabledLimitAllowsMoreThanTheStandaloneDefault() throws Exception {
        replay(-1L);
    }

    @Test
    void increasedLimitAllowsAndThenRejectsTheConfiguredBudget() throws Exception {
        replay(600_000_000L);
    }

    @Test
    void smallerLimitRejectsAtTheConfiguredBoundary() throws Exception {
        replay(96L);
    }

    @Test
    void defaultLimitRetainsItsBoundary() throws Exception {
        replay(400_000_000L);
    }

    @Test
    void maximumLongLimitCompilesAndRoundTripsExactly() throws Exception {
        replay(Long.MAX_VALUE);
    }

    @Test
    void noRuntimeOutputDoesNotAcquireAnAllocationRuntimeDependency() throws Exception {
        Properties.NO_RUNTIME_DEPENDENCY = true;
        Properties.CUMULATIVE_ARRAY_LIMIT_BYTES = -1L;
        new TestSuiteWriter().writeTestSuite("AllocationNoRuntimeTest", directory.toString(),
                Collections.emptyList());
        Path source = directory.resolve("AllocationNoRuntimeTest.java");
        String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(Files.exists(directory.resolve(Scaffolding.getFileName("AllocationNoRuntimeTest") + ".java")));
        assertFalse(code.contains("org.evosuite.runtime"), code);
        assertFalse(code.contains("maxCumulativeArrayBytes"), code);
        String junitOnly = new File(org.junit.Test.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getPath();
        compile(junitOnly, source);
    }

    private void replay(long limit) throws Exception {
        Properties.CUMULATIVE_ARRAY_LIMIT_BYTES = limit;
        String testName = "AllocationReplayTest";
        String scaffoldingName = Scaffolding.getFileName(testName);
        String scaffolding = Scaffolding.getScaffoldingFileContent(testName,
                Collections.singletonList(new ExecutionResult(new DefaultTestCase())), false);
        Path source = directory.resolve(scaffoldingName + ".java");
        Files.write(source, scaffolding.getBytes(StandardCharsets.UTF_8));
        Path probe = directory.resolve("AllocationReplayProbe.java");
        // Virtual charges exercise the same runtime guard as instrumented array sites,
        // without spending hundreds of megabytes merely to prove the replay configuration.
        String probeCode = "public class AllocationReplayProbe { public static void main(String[] args) throws Exception {\n"
                + "if (org.evosuite.runtime.RuntimeSettings.maxCumulativeArrayBytes != 400000000L) throw new AssertionError(\"not a fresh runtime\");\n"
                + "Class.forName(args[0]).getMethod(\"initEvoSuiteFramework\").invoke(null);\n"
                + "long expected = Long.parseLong(args[1]);\n"
                + "org.evosuite.runtime.ArrayAllocationBudget.reset();\n"
                + "if (expected < 0 || expected == Long.MAX_VALUE) {\n"
                + "  org.evosuite.runtime.ArrayAllocationBudget.checkAndRecord(500000000L, 1);\n"
                + "} else {\n"
                + "  long allowed = Math.min(expected - 16L, 500000000L);\n"
                + "  org.evosuite.runtime.ArrayAllocationBudget.checkAndRecord(allowed, 1);\n"
                + "  boolean rejected = false;\n"
                + "  try { org.evosuite.runtime.ArrayAllocationBudget.checkAndRecord(expected - allowed - 16L, 1); }\n"
                + "  catch (org.evosuite.runtime.TooManyResourcesException correct) { rejected = true; }\n"
                + "  if (!rejected) throw new AssertionError(\"configured allocation boundary did not reject\");\n"
                + "}\n"
                + "if (org.evosuite.runtime.RuntimeSettings.maxCumulativeArrayBytes != expected) throw new AssertionError(\"configured limit was not preserved: \" + org.evosuite.runtime.RuntimeSettings.maxCumulativeArrayBytes);\n"
                + "System.out.println(\"ALLOCATION_REPLAY_OK \" + expected);\n"
                + "}}\n";
        Files.write(probe, probeCode.getBytes(StandardCharsets.UTF_8));
        String classpath = System.getProperty("java.class.path");
        compile(classpath, source, probe);
        Path output = directory.resolve("fork-output.txt");
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Djava.awt.headless=true", "-cp", directory + File.pathSeparator + classpath,
                "AllocationReplayProbe", scaffoldingName, Long.toString(limit))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!child.waitFor(45, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            fail("allocation replay child JVM timed out");
        }
        String log = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), log + "\n" + scaffolding);
    }

    private void compile(String classpath, Path... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required for the generated scaffolding regression");
        List<String> arguments = new ArrayList<>(Arrays.asList("-proc:none", "-classpath", classpath,
                "-d", directory.toString()));
        for (Path source : sources) {
            arguments.add(source.toString());
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = compiler.run(null, diagnostics, diagnostics, arguments.toArray(new String[0]));
        assertEquals(0, status, new String(diagnostics.toByteArray(), StandardCharsets.UTF_8));
    }
}
