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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.rmi.service.ClientState;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test-only plumbing for driving {@link JUnitAnalyzer}'s private give-up state directly, shared
 * between {@link JUnitAnalyzerCheckIncompleteTest} (which drives {@code JUnitAnalyzer}'s own
 * internal abort paths) and {@code org.evosuite.TestSuiteGeneratorJUnitCheckCompletenessTest}
 * (which drives the generator-level exits around it). Both used to inline byte-for-byte copies of
 * these three helpers; this is the one place that now owns them.
 */
public final class JUnitAnalyzerTestSupport {

    private JUnitAnalyzerTestSupport() {
    }

    /**
     * Gives the JUnit-check phase a zero-length timeout and enters it, so that
     * {@code TimeController.getInstance().hasTimeToExecuteATestCase()} (and even the weaker
     * {@code isThereStillTimeInThisPhase()}) is false from the first call onward, without
     * sleeping or mocking.
     */
    public static void forceNoTimeLeftInJUnitCheckPhase() {
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.JUNIT_CHECK_TIMEOUT = 0;
        TimeController.resetSingleton();
        TimeController.getInstance().updateState(ClientState.STARTED);
        TimeController.getInstance().updateState(ClientState.JUNIT_CHECK);
        assertFalse(TimeController.getInstance().hasTimeToExecuteATestCase(),
                "test setup must actually exhaust the phase budget");
    }

    /** Reflectively pokes a private {@code static boolean} field on {@link JUnitAnalyzer}. */
    public static void setFlag(String fieldName, boolean value) throws Exception {
        Field field = JUnitAnalyzer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Reflectively pokes a private {@code static int} field on {@link JUnitAnalyzer}. */
    public static void setIntField(String fieldName, int value) throws Exception {
        Field field = JUnitAnalyzer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
