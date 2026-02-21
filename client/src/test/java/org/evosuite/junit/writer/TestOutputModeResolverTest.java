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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;

public class TestOutputModeResolverTest {

    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final boolean defaultTestScaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean defaultReplaceGui = Properties.REPLACE_GUI;
    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;
    private final Properties.OutputGranularity defaultOutputGranularity = Properties.OUTPUT_GRANULARITY;
    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final Properties.TestNamingStrategy defaultTestNamingStrategy = Properties.TEST_NAMING_STRATEGY;

    @AfterEach
    public void restoreProperties() {
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
        Properties.TEST_SCAFFOLDING = defaultTestScaffolding;
        Properties.REPLACE_GUI = defaultReplaceGui;
        Properties.TEST_FORMAT = defaultOutputFormat;
        Properties.OUTPUT_GRANULARITY = defaultOutputGranularity;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.TEST_NAMING_STRATEGY = defaultTestNamingStrategy;
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, NO_RUNTIME",
            "true, false, NO_RUNTIME",
            "false, true, LEGACY_SCAFFOLDING_FILE",
            "false, false, LEGACY_INLINE_SCAFFOLDING"
    })
    public void testResolveOutputModeMatrix(boolean noRuntime, boolean scaffolding, TestOutputMode expected) {
        configureDefaults();
        Properties.NO_RUNTIME_DEPENDENCY = noRuntime;
        Properties.TEST_SCAFFOLDING = scaffolding;

        Assertions.assertEquals(expected, TestSuiteWriterUtils.resolveTestOutputMode());
    }

    @ParameterizedTest
    @EnumSource(TestOutputMode.class)
    public void testGeneratedHeaderDiffersByMode(TestOutputMode mode) throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-mode-");
        configureDefaults();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.REPLACE_GUI = true;

        if (mode == TestOutputMode.NO_RUNTIME) {
            Properties.NO_RUNTIME_DEPENDENCY = true;
            Properties.TEST_SCAFFOLDING = true;
        } else if (mode == TestOutputMode.LEGACY_SCAFFOLDING_FILE) {
            Properties.NO_RUNTIME_DEPENDENCY = false;
            Properties.TEST_SCAFFOLDING = true;
        } else {
            Properties.NO_RUNTIME_DEPENDENCY = false;
            Properties.TEST_SCAFFOLDING = false;
        }

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.writeTestSuite("ModeTest", tempDir.toString(), Collections.emptyList());

        String code = readFile(tempDir.resolve("ModeTest.java"));
        if (mode == TestOutputMode.LEGACY_SCAFFOLDING_FILE) {
            Assertions.assertTrue(code.contains("extends " + Scaffolding.getFileName("ModeTest")));
            Assertions.assertTrue(code.contains("@RegisterExtension"));
        } else if (mode == TestOutputMode.LEGACY_INLINE_SCAFFOLDING) {
            Assertions.assertFalse(code.contains("extends " + Scaffolding.getFileName("ModeTest")));
            Assertions.assertTrue(code.contains("initEvoSuiteFramework"));
            Assertions.assertTrue(code.contains("@RegisterExtension"));
        } else {
            Assertions.assertFalse(code.contains("extends " + Scaffolding.getFileName("ModeTest")));
            Assertions.assertFalse(code.contains("initEvoSuiteFramework"));
            Assertions.assertFalse(code.contains("@RegisterExtension"));
            Assertions.assertFalse(code.contains("@EvoRunnerParameters("));
        }

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
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.TEST_SCAFFOLDING = true;
        Properties.REPLACE_GUI = false;
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.OUTPUT_GRANULARITY = Properties.OutputGranularity.MERGED;
        Properties.CLASS_PREFIX = "";
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
    }
}
