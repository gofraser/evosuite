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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the JUnit-check watchdog stops a run, every test after the one that hung times out on its
 * first instrumented line. Those tests were never run, so they must not be removed as unstable.
 */
public class JUnitAnalyzerWatchdogCasualtyTest {

    private static final String TIMEOUT =
            "shaded.org.evosuite.testcase.execution.TestCaseExecutor$TimeoutExceeded";

    private static JUnitFailure failure(String test, String exceptionClass) {
        return new JUnitFailure(null, exceptionClass, test, false, "");
    }

    private static JUnitResult stoppedRun() {
        JUnitResult result = new JUnitResult(false, 3, 3);
        result.setWatchdogFired(true);
        return result;
    }

    @Test
    public void onlyTheFirstTimeoutOfAStoppedRunIsEvidenceAgainstATest() {
        JUnitResult result = stoppedRun();
        AtomicBoolean culpritSeen = new AtomicBoolean(false);

        assertFalse(JUnitFailurePolicy.isWatchdogCasualty(result, failure("test008", TIMEOUT), culpritSeen),
                "the test that hung is judged like any other");
        assertTrue(JUnitFailurePolicy.isWatchdogCasualty(result, failure("test009", TIMEOUT), culpritSeen));
        assertTrue(JUnitFailurePolicy.isWatchdogCasualty(result, failure("test010", TIMEOUT), culpritSeen));
    }

    @Test
    public void otherFailuresOfAStoppedRunAreNotCasualties() {
        JUnitResult result = stoppedRun();
        AtomicBoolean culpritSeen = new AtomicBoolean(true);

        assertFalse(JUnitFailurePolicy.isWatchdogCasualty(
                result, failure("test001", "java.lang.IllegalStateException"), culpritSeen));
    }

    @Test
    public void timeoutsOfARunTheWatchdogLeftAloneAreNotCasualties() {
        JUnitResult result = new JUnitResult(false, 2, 2);
        AtomicBoolean culpritSeen = new AtomicBoolean(false);

        assertFalse(JUnitFailurePolicy.isWatchdogCasualty(result, failure("test001", TIMEOUT), culpritSeen));
        assertFalse(JUnitFailurePolicy.isWatchdogCasualty(result, failure("test002", TIMEOUT), culpritSeen));
    }
}
