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
package org.evosuite;

import org.evosuite.Properties.NoSuchParameterException;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class PropertiesTest {

    @BeforeAll
    public static void initClass() {
        Properties.getInstance();
    }

    @AfterEach
    public void reset() {
        Properties.getInstance().resetToDefaults();
        ClassPathHandler.resetSingleton();
    }

    @Test
    public void llmOperatorEnabledAliasSelectsSyncModeOnlyWhenModeIsUnset() {
        // Unset + alias true => SYNC.
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        Properties.resolveLlmOperatorModeAlias(false);
        assertEquals(Properties.LlmOperatorMode.SYNC, Properties.LLM_OPERATOR_MODE);

        // Unset + alias false => stays OFF.
        Properties.LLM_OPERATOR_ENABLED = false;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        Properties.resolveLlmOperatorModeAlias(false);
        assertEquals(Properties.LlmOperatorMode.OFF, Properties.LLM_OPERATOR_MODE);

        // Explicit OFF + alias true => stays OFF; the explicit setting wins.
        Properties.LLM_OPERATOR_ENABLED = true;
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        Properties.resolveLlmOperatorModeAlias(true);
        assertEquals(Properties.LlmOperatorMode.OFF, Properties.LLM_OPERATOR_MODE);
    }

    @Test
    public void testParameterThatDoesNotExist() {
        try {
            Properties.getInstance().setValue("a_parameter_that_does_not_exist", 1);
            fail();
        } catch (IllegalArgumentException | IllegalAccessException
                | NoSuchParameterException e) {
            //expected
        }
    }

    @Test
    public void testOutOfRangeInput() throws IllegalArgumentException, IllegalAccessException, NoSuchParameterException {

        Properties.getInstance().setValue("crossover_rate", "0.6"); //this should be OK

        try {
            Properties.getInstance().setValue("crossover_rate", "2.5");
            fail();
        } catch (Exception e) {/* OK*/}

        try {
            Properties.getInstance().setValue("crossover_rate", "-10.6");
            fail();
        } catch (Exception e) {/* OK*/}
    }

    @Test
    public void testInvalidBooleanInput() throws Exception {

        final boolean defaultValue = Properties.TEST_CARVING;

        Properties.getInstance().setValue("test_carving", Boolean.toString(!defaultValue));
        Assertions.assertNotEquals(defaultValue, Properties.TEST_CARVING);

        try {
            Properties.getInstance().setValue("test_carving", "tru");
            fail();
        } catch (Exception e) {
            //expected
        }
    }

    @Test
    public void testObjectRuleIsEnabledByDefault() throws Exception {
        Assertions.assertTrue(Properties.APPLY_OBJECT_RULE);
        Assertions.assertTrue(Properties.APPLY_OBJECT_RULE);
    }

    @Test
    public void testReset() {

        final String defaultValue = Properties.TARGET_CLASS;

        final String aString = "foo_foo_foo";
        Assertions.assertNotEquals(defaultValue, aString);

        Properties.TARGET_CLASS = aString;
        Assertions.assertEquals(aString, Properties.TARGET_CLASS);

        Properties.getInstance().resetToDefaults();
        Assertions.assertEquals(defaultValue, Properties.TARGET_CLASS);
    }

    @Test
    public void testWriteConfigurationFallsBackToPropertiesCpWhenClasspathHandlerIsUnset() throws Exception {
        ClassPathHandler.resetSingleton();
        Properties.CP = "/tmp/a" + java.io.File.pathSeparator + "/tmp/b";

        Path tmp = Files.createTempFile("evosuite-properties", ".properties");
        try {
            Properties.getInstance().writeConfiguration(tmp.toString());
            String firstLine = Files.readAllLines(tmp, StandardCharsets.UTF_8).get(0);
            Assertions.assertEquals("CP=/tmp/a" + java.io.File.pathSeparator + "/tmp/b", firstLine);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void retiredKeysAreReportedOnceAndIgnoredInPropertyFiles() throws Exception {
        Assertions.assertTrue(Properties.isRetired("epsilon"));
        Assertions.assertFalse(Properties.isRetired("search_budget"));
        Assertions.assertFalse(Properties.isRetired("no_such_key"));
        Assertions.assertTrue(Properties.warnIfRetired("epsilon"));
        Assertions.assertFalse(Properties.warnIfRetired("search_budget"));

        Path file = Files.createTempFile("evosuite-retired", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList("epsilon=0.5", "num_tests=9", "search_budget=7",
                    "no_such_key=1"), StandardCharsets.UTF_8);
            Properties.getInstance().loadProperties(file.toString(), true);
            Assertions.assertEquals(7, Properties.SEARCH_BUDGET);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void invalidValueOfLiveKeyInPropertyFileStillFails() throws Exception {
        Path file = Files.createTempFile("evosuite-invalid", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList("search_budget=abc"), StandardCharsets.UTF_8);
            Assertions.assertThrows(IllegalStateException.class,
                    () -> Properties.getInstance().loadProperties(file.toString(), true));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void everyParameterFieldHasItsOwnKey() {
        Set<String> keys = new HashSet<>();
        for (Field field : Properties.class.getFields()) {
            Properties.Parameter parameter = field.getAnnotation(Properties.Parameter.class);
            if (parameter != null) {
                Assertions.assertTrue(keys.add(parameter.key()),
                        "Key " + parameter.key() + " is declared more than once");
            }
        }
        Assertions.assertEquals(keys, Properties.getParameters());
    }

    @Test
    public void insertionProbabilitiesCanBeSetIndividually() throws Exception {
        Properties.getInstance().setValue("insertion_uut", "0.3");
        Properties.getInstance().setValue("insertion_parameter", "0.2");
        Properties.getInstance().setValue("insertion_environment", "0.5");
        Assertions.assertEquals(0.3, Properties.INSERTION_UUT, 0.0);
        Assertions.assertEquals(0.2, Properties.INSERTION_PARAMETER, 0.0);
        Assertions.assertEquals(0.5, Properties.INSERTION_ENVIRONMENT, 0.0);
    }

    @Test
    public void unknownEnumValueNamesKeyValueAndValidValues() {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> Properties.getInstance().setValue("strategy", "no_such_strategy"));
        Assertions.assertTrue(e.getMessage().contains("'strategy'"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("'no_such_strategy'"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("MOSUITE"), e.getMessage());
    }

    @Test
    public void unknownCriterionInListNamesKeyValueAndValidValues() {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> Properties.getInstance().setValue("criterion", "branch:no_such_criterion"));
        Assertions.assertTrue(e.getMessage().contains("'criterion'"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("'no_such_criterion'"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("LINE"), e.getMessage());
    }

    @Test
    public void enumValuesAreMatchedIgnoringCase() throws Exception {
        Properties.getInstance().setValue("strategy", "mosuite");
        Assertions.assertEquals(Properties.Strategy.MOSUITE, Properties.STRATEGY);
        Properties.getInstance().setValue("criterion", "line:Branch");
        Assertions.assertArrayEquals(new Properties.Criterion[]{Properties.Criterion.LINE, Properties.Criterion.BRANCH},
                Properties.CRITERION);
    }

    @Test
    public void unknownEnumValueInPropertyFileNamesKey() throws Exception {
        Path file = Files.createTempFile("evosuite-enum", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList("strategy=no_such_strategy"), StandardCharsets.UTF_8);
            IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                    () -> Properties.getInstance().loadProperties(file.toString(), true));
            Assertions.assertTrue(e.getMessage().contains("'no_such_strategy'"), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("MOSUITE"), e.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
