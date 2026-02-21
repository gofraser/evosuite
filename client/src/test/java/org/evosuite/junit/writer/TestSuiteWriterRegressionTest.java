/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
import java.util.Collections;
import java.util.stream.Stream;

public class TestSuiteWriterRegressionTest {

    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;
    private final boolean defaultTestScaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final Properties.OutputGranularity defaultOutputGranularity = Properties.OUTPUT_GRANULARITY;
    private final boolean defaultReplaceCalls = Properties.REPLACE_CALLS;
    private final boolean defaultVirtualFs = Properties.VIRTUAL_FS;
    private final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean defaultVirtualNet = Properties.VIRTUAL_NET;
    private final boolean defaultReplaceGui = Properties.REPLACE_GUI;
    private final String[] defaultIgnoreThreads = Properties.IGNORE_THREADS;
    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final Properties.TestNamingStrategy defaultTestNamingStrategy = Properties.TEST_NAMING_STRATEGY;

    @AfterEach
    public void restoreProperties() {
        Properties.TEST_FORMAT = defaultOutputFormat;
        Properties.TEST_SCAFFOLDING = defaultTestScaffolding;
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
        Properties.OUTPUT_GRANULARITY = defaultOutputGranularity;
        Properties.REPLACE_CALLS = defaultReplaceCalls;
        Properties.VIRTUAL_FS = defaultVirtualFs;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.VIRTUAL_NET = defaultVirtualNet;
        Properties.REPLACE_GUI = defaultReplaceGui;
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
        Properties.NO_RUNTIME_DEPENDENCY = false;

        TestSuiteWriter writer = new TestSuiteWriter();
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> writer.writeTestSuite("Bug9Test", tempDir.toString(), Collections.emptyList()));
        Assertions.assertTrue(ex.getMessage().contains("JUnit3 output with scaffolding is not supported"));
        deleteTempDir(tempDir);
    }

    private static String readFile(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
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
        Properties.IGNORE_THREADS = new String[]{};
        Properties.CLASS_PREFIX = "";
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
    }
}
