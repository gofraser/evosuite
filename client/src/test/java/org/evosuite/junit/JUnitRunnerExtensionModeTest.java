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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.runtime.EvoRunnerJUnit5;
import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class JUnitRunnerExtensionModeTest {

    @EvoRunnerParameters
    public static class LegacyRunnerModeJUnit5Fixture {
        @RegisterExtension
        static EvoRunnerJUnit5 runner = new EvoRunnerJUnit5(LegacyRunnerModeJUnit5Fixture.class);

        @Test
        public void testPasses() {
            Assertions.assertEquals(42, 40 + 2);
        }
    }

    @EvoRunnerParameters
    public static class ExtensionModeJUnit5Fixture {
        @RegisterExtension
        static EvoSuiteExtension runner = new EvoSuiteExtension(ExtensionModeJUnit5Fixture.class);

        @Test
        public void testWorks() {
            Assertions.assertEquals(42, 40 + 2);
        }
    }

    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;

    @AfterEach
    public void restoreProperties() {
        Properties.TEST_FORMAT = defaultOutputFormat;
    }

    @Test
    public void testJUnit5RunnerExecutesExtensionModeClassWithoutInitializationListFiles() throws IOException {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Path temporaryWorkingDirectory = Files.createTempDirectory("evosuite-extension-mode");
        String originalUserDir = java.lang.System.getProperty("user.dir");

        java.lang.System.setProperty("user.dir", temporaryWorkingDirectory.toString());
        try {
            JUnitRunner runner = new JUnitRunner(ExtensionModeJUnit5Fixture.class);
            runner.run();

            Assertions.assertFalse(runner.getTestResults().isEmpty());
            Assertions.assertTrue(runner.getTestResults().stream()
                    .anyMatch(result -> result.getName().contains("testWorks")));
            Assertions.assertTrue(runner.getTestResults().stream()
                    .allMatch(result -> result.getJUnitClass().equals(ExtensionModeJUnit5Fixture.class)));
            Assertions.assertFalse(Files.exists(
                    temporaryWorkingDirectory.resolve(".scaffolding_list.tmp")));
        } finally {
            java.lang.System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    public void testLegacyRunnerAndExtensionModeHaveEquivalentOutcomes() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        boolean oldUseAgent = EvoRunnerJUnit5.useAgent;
        boolean oldUseClassLoader = EvoRunnerJUnit5.useClassLoader;
        EvoRunnerJUnit5.useAgent = false;
        EvoRunnerJUnit5.useClassLoader = false;
        try {
            Map<String, Boolean> legacyOutcomes = runAndCollectOutcomes(
                    LegacyRunnerModeJUnit5Fixture.class, "testPasses");
            Map<String, Boolean> extensionOutcomes = runAndCollectOutcomes(
                    ExtensionModeJUnit5ParityFixture.class, "testPasses");

            Assertions.assertEquals(legacyOutcomes, extensionOutcomes);
        } finally {
            EvoRunnerJUnit5.useAgent = oldUseAgent;
            EvoRunnerJUnit5.useClassLoader = oldUseClassLoader;
        }
    }

    @EvoRunnerParameters
    public static class ExtensionModeJUnit5ParityFixture {
        @RegisterExtension
        static EvoSuiteExtension runner = new EvoSuiteExtension(ExtensionModeJUnit5ParityFixture.class);

        @Test
        public void testPasses() {
            Assertions.assertEquals(42, 40 + 2);
        }
    }

    @EvoRunnerParameters
    public static class LegacyRunnerModeJUnit5MixedOutcomeFixture {
        @RegisterExtension
        static EvoRunnerJUnit5 runner = new EvoRunnerJUnit5(LegacyRunnerModeJUnit5MixedOutcomeFixture.class);

        @Test
        public void testPasses() {
            Assertions.assertEquals(42, 40 + 2);
        }

        @Test
        public void testFails() {
            Assertions.assertEquals(41, 40 + 2);
        }

        @Test
        public void testThrows() {
            throw new IllegalStateException("expected-mixed-parity");
        }
    }

    @EvoRunnerParameters
    public static class ExtensionModeJUnit5MixedOutcomeFixture {
        @RegisterExtension
        static EvoSuiteExtension runner = new EvoSuiteExtension(ExtensionModeJUnit5MixedOutcomeFixture.class);

        @Test
        public void testPasses() {
            Assertions.assertEquals(42, 40 + 2);
        }

        @Test
        public void testFails() {
            Assertions.assertEquals(41, 40 + 2);
        }

        @Test
        public void testThrows() {
            throw new IllegalStateException("expected-mixed-parity");
        }
    }

    @Test
    public void testLegacyRunnerAndExtensionModeHaveEquivalentMixedOutcomes() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        boolean oldUseAgent = EvoRunnerJUnit5.useAgent;
        boolean oldUseClassLoader = EvoRunnerJUnit5.useClassLoader;
        EvoRunnerJUnit5.useAgent = false;
        EvoRunnerJUnit5.useClassLoader = false;
        try {
            Map<String, Boolean> legacyOutcomes = runAndCollectOutcomes(
                    LegacyRunnerModeJUnit5MixedOutcomeFixture.class, "testPasses", "testFails", "testThrows");
            Map<String, Boolean> extensionOutcomes = runAndCollectOutcomes(
                    ExtensionModeJUnit5MixedOutcomeFixture.class, "testPasses", "testFails", "testThrows");

            Assertions.assertEquals(legacyOutcomes, extensionOutcomes);
        } finally {
            EvoRunnerJUnit5.useAgent = oldUseAgent;
            EvoRunnerJUnit5.useClassLoader = oldUseClassLoader;
        }
    }

    private static Map<String, Boolean> runAndCollectOutcomes(Class<?> testClass, String... expectedNames) {
        JUnitRunner runner = new JUnitRunner(testClass);
        runner.run();
        List<JUnitResult> results = runner.getTestResults();
        Assertions.assertFalse(results.isEmpty());
        List<String> expected = Arrays.asList(expectedNames);

        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        for (JUnitResult result : results) {
            for (String expectedName : expected) {
                if (result.getName().contains(expectedName)) {
                    outcomes.put(expectedName, result.wasSuccessful());
                }
            }
        }
        Assertions.assertEquals(expected.size(), outcomes.size());
        return outcomes;
    }
}
