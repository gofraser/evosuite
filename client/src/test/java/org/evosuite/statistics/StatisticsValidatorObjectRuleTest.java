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
 */
package org.evosuite.statistics;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatisticsValidatorObjectRuleTest {

    private final boolean previousValidation = Properties.VALIDATE_RUNTIME_VARIABLES;

    @AfterEach
    public void restoreProperties() {
        Properties.VALIDATE_RUNTIME_VARIABLES = previousValidation;
    }

    @Test
    public void acceptsCompleteFirstShotPartition() {
        Properties.VALIDATE_RUNTIME_VARIABLES = true;
        Map<String, OutputVariable<?>> variables = partition(7, 1, 4, 2);

        assertTrue(StatisticsValidator.validateRuntimeVariables(variables));
    }

    @Test
    public void rejectsIncompleteFirstShotPartition() {
        Properties.VALIDATE_RUNTIME_VARIABLES = true;
        Map<String, OutputVariable<?>> variables = partition(7, 0, 0, 0);

        assertFalse(StatisticsValidator.validateRuntimeVariables(variables));
    }

    @Test
    public void acceptsBoundedSetupRepairFunnels() {
        Properties.VALIDATE_RUNTIME_VARIABLES = true;
        Map<String, OutputVariable<?>> variables = new HashMap<>();
        put(variables, RuntimeVariable.ObjectRuleMockSetupOpportunities, 5);
        put(variables, RuntimeVariable.ObjectRuleMockSetupAttempts, 4);
        put(variables, RuntimeVariable.ObjectRuleMockSetupSuccesses, 2);

        assertTrue(StatisticsValidator.validateRuntimeVariables(variables));
    }

    @Test
    public void rejectsImpossibleSetupRepairFunnels() {
        Properties.VALIDATE_RUNTIME_VARIABLES = true;
        Map<String, OutputVariable<?>> variables = new HashMap<>();
        put(variables, RuntimeVariable.ObjectRuleMockSetupOpportunities, 1);
        put(variables, RuntimeVariable.ObjectRuleMockSetupAttempts, 2);
        put(variables, RuntimeVariable.ObjectRuleMockSetupSuccesses, 3);

        assertFalse(StatisticsValidator.validateRuntimeVariables(variables));
    }

    private static Map<String, OutputVariable<?>> partition(
            int firstShot, int immediate, int reached, int unavailable) {
        Map<String, OutputVariable<?>> variables = new HashMap<>();
        put(variables, RuntimeVariable.ObjectRuleFirstShotSuccesses, firstShot);
        put(variables, RuntimeVariable.ObjectRuleImmediateCoverage, immediate);
        put(variables, RuntimeVariable.ObjectRuleReachedNotCovered, reached);
        put(variables, RuntimeVariable.ObjectRuleWorkingGoalUnavailable, unavailable);
        return variables;
    }

    private static void put(
            Map<String, OutputVariable<?>> variables, RuntimeVariable name, int value) {
        variables.put(name.toString(), new OutputVariable<>(name.toString(), value));
    }
}
