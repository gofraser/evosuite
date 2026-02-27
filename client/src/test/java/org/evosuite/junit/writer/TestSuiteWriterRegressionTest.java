/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit.writer;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class TestSuiteWriterRegressionTest {

    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;
    private final boolean defaultTestScaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean defaultTestExtensionMode = Properties.TEST_EXTENSION_MODE;
    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final Properties.OutputGranularity defaultOutputGranularity = Properties.OUTPUT_GRANULARITY;
    private final boolean defaultReplaceCalls = Properties.REPLACE_CALLS;
    private final boolean defaultVirtualFs = Properties.VIRTUAL_FS;
    private final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean defaultVirtualNet = Properties.VIRTUAL_NET;
    private final boolean defaultReplaceGui = Properties.REPLACE_GUI;
    private final boolean defaultUseSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
    private final String[] defaultIgnoreThreads = Properties.IGNORE_THREADS;
    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final Properties.TestNamingStrategy defaultTestNamingStrategy = Properties.TEST_NAMING_STRATEGY;

    @AfterEach
    public void restoreProperties() {
        Properties.TEST_FORMAT = defaultOutputFormat;
        Properties.TEST_SCAFFOLDING = defaultTestScaffolding;
        Properties.TEST_EXTENSION_MODE = defaultTestExtensionMode;
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
        Properties.OUTPUT_GRANULARITY = defaultOutputGranularity;
        Properties.REPLACE_CALLS = defaultReplaceCalls;
        Properties.VIRTUAL_FS = defaultVirtualFs;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.VIRTUAL_NET = defaultVirtualNet;
        Properties.REPLACE_GUI = defaultReplaceGui;
        Properties.USE_SEPARATE_CLASSLOADER = defaultUseSeparateClassLoader;
        Properties.IGNORE_THREADS = defaultIgnoreThreads;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.TEST_NAMING_STRATEGY = defaultTestNamingStrategy;
    }

    @Test
    public void testNoRuntimeJunit5DoesNotEmitRegisterExtension() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-bug1-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.NO_RUNTIME_DEPENDENCY = true;
        Properties.TEST_SCAFFOLDING = true;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.writeTestSuite("Bug1Test", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("Bug1Test.java"));
        Assertions.assertFalse(code.contains("@RegisterExtension"));
        Assertions.assertFalse(code.contains("EvoRunnerJUnit5 runner ="));
        Assertions.assertFalse(code.contains("import org.junit.jupiter.api.extension.RegisterExtension;"));
        Assertions.assertFalse(code.contains("import org.evosuite.runtime.EvoRunnerJUnit5;"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testPerFileNoRuntimeDoesNotInlineScaffoldingMethods() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-bug2-");
        configureDefaults();
        Properties.OUTPUT_GRANULARITY = Properties.OutputGranularity.TESTCASE;
        Properties.NO_RUNTIME_DEPENDENCY = true;
        Properties.TEST_SCAFFOLDING = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.insertTest(new DefaultTestCase());
        writer.writeTestSuite("Bug2Test", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("Bug2Test_0.java"));
        Assertions.assertFalse(code.contains("initEvoSuiteFramework"));
        Assertions.assertFalse(code.contains("doneWithTestCase"));
        Assertions.assertFalse(code.contains("initTestCase"));
        Assertions.assertFalse(code.contains("threadStopper"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testReplaceGuiRequiresRuntimeAgentConfiguration() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-bug7-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.REPLACE_GUI = true;
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.TEST_SCAFFOLDING = true;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.writeTestSuite("Bug7Test", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("Bug7Test.java"));
        Assertions.assertTrue(code.contains("@RunWith(EvoRunner.class) @EvoRunnerParameters("));
        Assertions.assertTrue(code.contains("mockGUI = true"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testJUnit3WithScaffoldingFailsFast() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-bug9-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT3;
        Properties.TEST_SCAFFOLDING = true;
        Properties.TEST_EXTENSION_MODE = false;
        Properties.NO_RUNTIME_DEPENDENCY = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> writer.writeTestSuite("Bug9Test", tempDir.toString(), Collections.emptyList()));
        Assertions.assertTrue(ex.getMessage().contains("JUnit3 output with scaffolding is not supported"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testExtensionModeCanEmitExplicitInitializationOrder() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-bug10-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.TEST_SCAFFOLDING = false;
        Properties.RESET_STATIC_FIELDS = true;
        Properties.USE_SEPARATE_CLASSLOADER = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.setExtensionInitializationOrder(Arrays.asList("z.InitLast", "a.InitFirst", "m.InitMiddle"));
        writer.writeTestSuite("Bug10Test", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("Bug10Test.java"));
        Assertions.assertTrue(code.contains("private static final String[] EVO_INIT_ORDER = {\"z.InitLast\", \"a.InitFirst\", \"m.InitMiddle\"};"));
        Assertions.assertTrue(code.contains("new EvoSuiteExtension(Bug10Test.class, EVO_INIT_ORDER);"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testExtensionModeHeaderUsesIndentedRegisterExtension() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-ext-indent-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.TEST_SCAFFOLDING = false;
        Properties.RESET_STATIC_FIELDS = true;
        Properties.USE_SEPARATE_CLASSLOADER = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.writeTestSuite("ExtIndentTest", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("ExtIndentTest.java"));
        Assertions.assertTrue(code.contains("\n  @RegisterExtension\n  static EvoSuiteExtension runner = new EvoSuiteExtension(ExtIndentTest.class);"));
        deleteTempDir(tempDir);
    }

    @Test
    public void testExtensionModeImportsAreSortedAndStaticImportsAreGroupedLast() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-ext-imports-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.TEST_SCAFFOLDING = false;
        Properties.REPLACE_CALLS = true;
        Properties.USE_SEPARATE_CLASSLOADER = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.insertTest(new DefaultTestCase());
        writer.writeTestSuite("ExtImportOrderTest", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("ExtImportOrderTest.java"));
        List<String> normalImports = extractImports(code, false);
        List<String> staticImports = extractImports(code, true);

        List<String> sortedNormal = new ArrayList<>(normalImports);
        Collections.sort(sortedNormal);
        Assertions.assertEquals(sortedNormal, normalImports, "Normal imports must be sorted");

        List<String> sortedStatic = new ArrayList<>(staticImports);
        Collections.sort(sortedStatic);
        Assertions.assertEquals(sortedStatic, staticImports, "Static imports must be sorted");

        int firstStatic = code.indexOf("import static ");
        int lastNormal = findLastNonStaticImportIndex(code);
        Assertions.assertTrue(firstStatic >= 0);
        Assertions.assertTrue(firstStatic > 0 && lastNormal > 0 && firstStatic >= lastNormal,
                "Static imports should be emitted after normal imports");

        deleteTempDir(tempDir);
    }

    @Test
    public void testLegacyJunit5ScaffoldingModeRemainsOperational() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-legacy-j5-scaff-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_EXTENSION_MODE = false;
        Properties.TEST_SCAFFOLDING = true;
        Properties.REPLACE_CALLS = true;

        TestSuiteWriter writer = new TestSuiteWriter();
        List<java.io.File> generated = writer.writeTestSuite("LegacyJ5ScaffTest", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("LegacyJ5ScaffTest.java"));
        Assertions.assertTrue(code.contains("static EvoRunnerJUnit5 runner = new EvoRunnerJUnit5(LegacyJ5ScaffTest.class);"));
        Assertions.assertFalse(code.contains("EvoSuiteExtension"));
        Assertions.assertTrue(generated.stream().anyMatch(f -> f.getName().endsWith("_scaffolding.java")));
        Assertions.assertTrue(Files.exists(tempDir.resolve("LegacyJ5ScaffTest_scaffolding.java")));
        deleteTempDir(tempDir);
    }

    private static int findLastNonStaticImportIndex(String code) {
        int lastIndex = -1;
        String[] lines = code.split("\\R");
        int cursor = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && !trimmed.startsWith("import static ")) {
                lastIndex = cursor;
            }
            cursor += line.length() + 1;
        }
        return lastIndex;
    }

    private static List<String> extractImports(String code, boolean includeStatic) {
        List<String> imports = new ArrayList<>();
        String[] lines = code.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            boolean isStatic = trimmed.startsWith("import static ");
            if (isStatic == includeStatic) {
                imports.add(trimmed);
            }
        }
        return imports;
    }

    private static String readFile(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void deleteTempDir(Path tempDir) throws IOException {
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort cleanup for test-only directories.
                        }
                    });
        }
    }

    private static void configureDefaults() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = true;
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.OUTPUT_GRANULARITY = Properties.OutputGranularity.MERGED;
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.REPLACE_GUI = false;
        Properties.USE_SEPARATE_CLASSLOADER = true;
        Properties.IGNORE_THREADS = new String[]{};
        Properties.CLASS_PREFIX = "";
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
    }
}
