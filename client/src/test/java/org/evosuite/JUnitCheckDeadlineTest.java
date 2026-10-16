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

import org.evosuite.rmi.service.ClientState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order-sensitivity analysis runs the whole suite twice; it must not be started when the
 * JUnit check phase cannot accommodate it.
 */
public class JUnitCheckDeadlineTest {

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 10;
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
        TimeController.resetSingleton();
    }

    @Test
    public void orderSensitivityAnalysisCostsTwoSuiteRuns() {
        assertEquals(3_000L, TestSuiteGenerator.orderSensitivityAnalysisCostMs(1_500L));
        assertEquals(0L, TestSuiteGenerator.orderSensitivityAnalysisCostMs(-1L));
    }

    @Test
    public void orderSensitivityAnalysisNeedsTwiceItsCostInThePhase() {
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);

        // 10 s phase: a 2 s suite run costs 4 s, and 8 s are left.
        assertTrue(TestSuiteGenerator.hasTimeForOrderSensitivityAnalysis(2_000L));
        // A 3 s suite run costs 6 s, which would need 12 s.
        assertFalse(TestSuiteGenerator.hasTimeForOrderSensitivityAnalysis(3_000L));
    }

    @Test
    public void orderSensitivityAnalysisRunsWithoutPhaseInformation() {
        // Nothing started yet, so no phase to be bounded by.
        assertTrue(TestSuiteGenerator.hasTimeForOrderSensitivityAnalysis(1_000_000L));
    }
}
