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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TimeControllerPhaseBudgetTest {

    private static final int EXTRA = 60;
    private static final int INITIALIZATION = 120;
    private static final int SEARCH = 100;
    private static final int MINIMIZATION = 30;
    private static final int ASSERTIONS = 40;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.EXTRA_TIMEOUT = EXTRA;
        Properties.INITIALIZATION_TIMEOUT = INITIALIZATION;
        Properties.SEARCH_BUDGET = SEARCH;
        Properties.MINIMIZE = true;
        Properties.MINIMIZATION_TIMEOUT = MINIMIZATION;
        Properties.ASSERTIONS = true;
        Properties.ASSERTION_TIMEOUT = ASSERTIONS;
        Properties.JUNIT_TESTS = false;
        Properties.TEST_FACTORY = Properties.TestFactory.RANDOM;
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
        TimeController.resetSingleton();
    }

    private static TimeController controllerInPhase(ClientState phase) {
        TimeController controller = TimeController.getInstance();
        controller.updateState(ClientState.STARTED);
        controller.updateState(phase);
        return controller;
    }

    @Test
    public void totalBudgetIsTheSumOfThePhaseBudgets() {
        Assertions.assertEquals(EXTRA + INITIALIZATION + SEARCH + MINIMIZATION + ASSERTIONS,
                TimeController.getInstance().calculateForHowLongClientWillRunInSeconds());
    }

    @Test
    public void searchKeepsItsFullBudget() {
        TimeController controller = controllerInPhase(ClientState.SEARCH);

        // The search is bounded by its own timeout, not by what the phases after it were promised.
        Assertions.assertTrue(controller.getRemainingTimeInPhaseMs() > (SEARCH - 5) * 1000L,
                "search must not be charged for the budget of later phases");
        Assertions.assertTrue(controller.isThereStillTimeInThisPhase((SEARCH - 5) * 1000L));
    }

    @Test
    public void aPhaseWithoutATimeoutIsBoundedByWhatIsLeftForTheLaterPhases() {
        TimeController controller = controllerInPhase(ClientState.COVERAGE_ANALYSIS);

        // Everything except EXTRA_TIMEOUT is either already spent or reserved for the phases that
        // still have to run, so coverage analysis may only use the slack.
        long remaining = controller.getRemainingTimeInPhaseMs();
        Assertions.assertTrue(remaining <= EXTRA * 1000L + INITIALIZATION * 1000L + SEARCH * 1000L
                        + MINIMIZATION * 1000L,
                "an untimed phase must not see the budget of the phases after it");
        Assertions.assertFalse(controller.isThereStillTimeInThisPhase(
                        (EXTRA + INITIALIZATION + SEARCH + MINIMIZATION + ASSERTIONS - 5) * 1000L),
                "an untimed phase must not be able to spend the whole client budget");
        Assertions.assertTrue(controller.isThereStillTimeInThisPhase(1),
                "with nothing overspent yet there is still slack to work with");
    }
}
