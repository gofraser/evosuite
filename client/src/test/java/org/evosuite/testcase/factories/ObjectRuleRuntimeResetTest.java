/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.factories;

import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectRuleRuntimeResetTest {

    @Test
    void resetStartsTheMockSetupCountersAndRecipesAgain() {
        ObjectRuleRuntime runtime = new ObjectRuleRuntime();
        FunctionalMockSetupCoordinator before = runtime.getMockSetup();
        before.recordExtraExecution();
        assertEquals(1, before.getExtraExecutions());

        runtime.reset();

        assertNotSame(before, runtime.getMockSetup());
        assertEquals(0, runtime.getMockSetup().getExtraExecutions());
    }

    @Test
    void resetClearsTheSetterCacheAndTheTimeoutWarnings() {
        ObjectRuleRuntime runtime = new ObjectRuleRuntime();
        runtime.getSetterAnalysisCache().put(Arrays.<Object>asList("f", "C", 2), new LinkedHashMap<>());
        assertTrue(runtime.markSynthesisTimeoutWarned("C"));
        assertEquals(false, runtime.markSynthesisTimeoutWarned("C"));

        runtime.reset();

        assertTrue(runtime.getSetterAnalysisCache().isEmpty());
        assertTrue(runtime.markSynthesisTimeoutWarned("C"));
    }
}
