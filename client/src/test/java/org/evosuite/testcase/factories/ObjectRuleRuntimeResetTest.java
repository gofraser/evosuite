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
