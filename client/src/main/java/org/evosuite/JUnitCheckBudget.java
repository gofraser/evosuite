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

import org.evosuite.junit.JUnitCheckState;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides, for each check of the JUnit check phase that follows the compile filter, whether it
 * may still run, and records a check that may not as incomplete. The suite is kept either way;
 * the tests not checked yet stay in it unchecked.
 *
 * <p>Each check compiles the tests and runs them on freshly loaded classes, which is expensive in
 * memory, so the phase stops checking rather than run the client out of heap. Once memory has
 * run out, or javac has failed for good, no later check of the phase runs.
 */
final class JUnitCheckBudget {

    private static final Logger logger = LoggerFactory.getLogger(JUnitCheckBudget.class);

    /** The checks of the JUnit check phase after the compile filter, in the order they run. */
    enum Step {
        /** One check per test, each compiling and running that test alone. */
        PER_TEST("the remaining per-test JUnit checks"),
        /** The whole suite at once, in its own order. */
        WHOLE_SUITE("the whole-suite JUnit check"),
        /** The whole suite in reverse order, to spot dependencies among the tests. */
        REVERSE_ORDER("the reverse-order JUnit check"),
        /** The order-sensitivity analysis of a suite written in extension mode. */
        ORDER_SENSITIVITY("the order-sensitivity analysis");

        private final String description;

        Step(String description) {
            this.description = description;
        }
    }

    private final PhaseMemoryGuard memoryGuard;
    private long perTestChecksMs = 0;

    JUnitCheckBudget(PhaseMemoryGuard memoryGuard) {
        this.memoryGuard = memoryGuard;
    }

    /**
     * Sets how long the per-test checks took, which bounds what the whole suite costs to run.
     *
     * @param perTestChecksMs the time the per-test checks took, in milliseconds
     */
    void setPerTestChecksMs(long perTestChecksMs) {
        this.perTestChecksMs = perTestChecksMs;
    }

    /**
     * Returns whether the given check may run, and records it as incomplete if it may not and
     * nothing has recorded why yet.
     *
     * @param step      the check
     * @param unchecked the number of tests the check would leave unchecked, or 0 if not relevant
     * @return true if the check may run
     */
    boolean mayRun(Step step, int unchecked) {
        switch (step) {
            case PER_TEST:
                if (JUnitCheckState.isCompileCheckDisabled()) {
                    logger.warn("Aborting remaining per-test JUnit checks due to prior javac infrastructure failure");
                    return false;
                }
                if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                    JUnitCheckState.recordIncomplete(
                            "ran out of time before finishing the per-test JUnit checks; "
                                    + unchecked + " test(s) left unchecked");
                    return false;
                }
                return !isMemoryExhausted(step, unchecked);
            case WHOLE_SUITE:
            case REVERSE_ORDER:
                if (JUnitCheckState.isCompileCheckDisabled() || isMemoryExhausted(step, 0)) {
                    return false;
                }
                if (!hasTimeForWholeSuiteCheck()) {
                    JUnitCheckState.recordIncomplete("ran out of time before " + step.description);
                    return false;
                }
                return true;
            case ORDER_SENSITIVITY:
                return !isMemoryExhausted(step, 0);
            default:
                throw new IllegalArgumentException("Unknown JUnit check step: " + step);
        }
    }

    /**
     * Whether the phase has time left to run the whole suite once, as far as the per-test checks
     * tell what that costs.
     *
     * @return true if a whole-suite run fits into the phase
     */
    boolean hasTimeForWholeSuiteCheck() {
        return TimeController.getInstance().hasTimeToExecuteATestCase()
                && TimeController.getInstance().isThereStillTimeInThisPhase(perTestChecksMs);
    }

    /**
     * Checks the memory guard of the phase, and logs (once) that the remaining checks are skipped
     * when memory has run out.
     *
     * @param step      the check that would be skipped
     * @param unchecked number of tests that stay unchecked, or 0 if not relevant
     * @return true if the check must be skipped
     */
    boolean isMemoryExhausted(Step step, int unchecked) {
        boolean wasExhausted = memoryGuard.isExhaustedAlready();
        String cause;
        // A check that ran out of memory inside JUnitAnalyzer has recorded that itself; only
        // this guard's own reading of free heap is recorded here.
        boolean ownGuardTrippedNow = false;
        if (!wasExhausted && JUnitCheckState.isMemoryExhausted()) {
            // A check (e.g. javac, which shares this heap) ran out of memory.
            memoryGuard.markExhausted();
            cause = "a check ran out of memory";
        } else if (memoryGuard.isExhausted()) {
            ownGuardTrippedNow = !wasExhausted;
            cause = (memoryGuard.getLastFreeBytes() / 1024 / 1024) + " MB free, need "
                    + (memoryGuard.getThresholdBytes() / 1024 / 1024) + " MB";
        } else {
            return false;
        }
        if (!wasExhausted) {
            LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Low memory during JUnit check (" + cause + "); skipping "
                    + step.description + " and all later checks, keeping the suite"
                    + (unchecked > 0 ? " (" + unchecked + " test(s) left unchecked)" : ""));
            if (ownGuardTrippedNow) {
                JUnitCheckState.recordIncomplete("low free JVM memory during JUnit check; skipping "
                        + step.description + " and all later checks");
            }
        }
        return true;
    }
}
