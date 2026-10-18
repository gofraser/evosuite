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

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The process-wide state of the JUnit checks (compile filter, instability check, order-sensitivity
 * probe): whether javac is unusable, whether memory ran out, and which check rounds were given up
 * on. It belongs to the JVM rather than to a check phase, so it is not part of
 * {@code JUnitCheckBudget}.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>The javac infrastructure flag and the two "already logged" flags are never reset during a
 *       run: once javac has failed, compile filtering stays off for the rest of the process.</li>
 *   <li>The memory-exhaustion flag, the incompleteness flag and the round counter are cleared by
 *       {@link #resetForPhase()}, which {@code TestSuiteGenerator} calls at the start of every
 *       JUnit check phase.</li>
 * </ul>
 */
public final class JUnitCheckState {

    private static final Logger logger = LoggerFactory.getLogger(JUnitCheckState.class);

    private static volatile boolean compileCheckDisabled = false;
    private static volatile boolean compileCheckDisabledLogged = false;
    private static volatile boolean compileDependentExecutionSkippedLogged = false;

    /**
     * Set when a check ran out of heap (javac included, which runs in this JVM). The remaining
     * checks of the phase are skipped and the tests not checked yet are kept, as with any other
     * sign of low memory during the check; unlike a javac infrastructure failure, this does not
     * disable compile filtering for good.
     */
    private static volatile boolean memoryExhausted = false;

    /**
     * Set when any JUnit check round during this phase had to be abandoned before completing. The
     * suite is kept regardless (see the callers of {@link #recordIncomplete(String)}); this only
     * records that it was never fully validated, so that fact reaches the generation report
     * instead of being visible only in the log.
     */
    private static volatile boolean incomplete = false;

    /** Number of distinct give-up events recorded via {@link #recordIncomplete(String)}. */
    private static volatile int incompleteRounds = 0;

    private JUnitCheckState() {
    }

    /**
     * Turns compile filtering off for the rest of the run.
     *
     * @return true if it was enabled before this call
     */
    static boolean disableCompileCheck() {
        boolean first = !compileCheckDisabled;
        compileCheckDisabled = true;
        return first;
    }

    /**
     * Whether javac failed in a way that turned compile filtering off for the rest of the run.
     *
     * @return true if compile filtering is disabled
     */
    public static boolean isCompileCheckDisabled() {
        return compileCheckDisabled;
    }

    /**
     * Claims the right to log that the compile check is disabled.
     *
     * @return true the first time it is called, false afterwards
     */
    static boolean claimCompileCheckDisabledLog() {
        if (compileCheckDisabledLogged) {
            return false;
        }
        compileCheckDisabledLogged = true;
        return true;
    }

    /**
     * Claims the right to log that compile-dependent execution checks are skipped.
     *
     * @return true the first time it is called, false afterwards
     */
    static boolean claimCompileDependentExecutionSkippedLog() {
        if (compileDependentExecutionSkippedLogged) {
            return false;
        }
        compileDependentExecutionSkippedLogged = true;
        return true;
    }

    /**
     * Records that a check ran out of memory.
     *
     * @return true if memory had not been exhausted before this call
     */
    static boolean markMemoryExhausted() {
        boolean first = !memoryExhausted;
        memoryExhausted = true;
        return first;
    }

    /**
     * Whether a check ran out of memory since the last {@link #resetForPhase()}; the remaining
     * checks are then skipped and the tests not checked yet are kept.
     *
     * @return true if memory ran out during a check
     */
    public static boolean isMemoryExhausted() {
        return memoryExhausted;
    }

    /** Whether checks are off for the rest of the run: javac failed or memory ran out. */
    static boolean isSuspended() {
        return compileCheckDisabled || memoryExhausted;
    }

    /**
     * Records that a JUnit check round was abandoned before completing: the reason is logged, and
     * the fact reaches the generation report via {@link RuntimeVariable#JUnit_Check_Incomplete}
     * and {@link RuntimeVariable#JUnit_Check_Incomplete_Rounds}. Does not change whether the suite
     * (or the specific tests involved) is kept; that decision is made at each call site.
     *
     * <p>Also called by {@code TestSuiteGenerator} and its {@code JUnitCheckBudget} for the rounds
     * abandoned before the analyzer is reached at all: the phase skipped for low memory, its time
     * budget expired, its per-test loop exited early, an order probe skipped, or its memory guard
     * tripped.
     *
     * @param reason short description of what was abandoned, for the debug log
     */
    public static void recordIncomplete(String reason) {
        incompleteRounds++;
        incomplete = true;
        logger.debug("Recording JUnit check as incomplete: {}", reason);
        track();
    }

    /**
     * Whether any JUnit check round since the last {@link #resetForPhase()} had to be abandoned
     * before completing.
     *
     * @return true if at least one give-up event was recorded
     */
    public static boolean isIncomplete() {
        return incomplete;
    }

    /**
     * Number of distinct JUnit check rounds that were abandoned before completing since the last
     * {@link #resetForPhase()}.
     *
     * @return the count of recorded give-up events
     */
    public static int getIncompleteRounds() {
        return incompleteRounds;
    }

    /**
     * Forgets an earlier out-of-memory condition and any recorded check incompleteness, and
     * publishes the cleared baseline. Called at the start of a new JUnit check phase; the javac
     * infrastructure flag survives.
     */
    public static void resetForPhase() {
        memoryExhausted = false;
        incomplete = false;
        incompleteRounds = 0;
        track();
    }

    /** Clears everything, including the flags that otherwise last for the whole run. */
    static void resetAllForTests() {
        compileCheckDisabled = false;
        compileCheckDisabledLogged = false;
        compileDependentExecutionSkippedLogged = false;
        resetForPhase();
    }

    static boolean isCompileCheckDisabledLogged() {
        return compileCheckDisabledLogged;
    }

    static boolean isCompileDependentExecutionSkippedLogged() {
        return compileDependentExecutionSkippedLogged;
    }

    private static void track() {
        ClientServices.track(RuntimeVariable.JUnit_Check_Incomplete, incomplete);
        ClientServices.track(RuntimeVariable.JUnit_Check_Incomplete_Rounds, incompleteRounds);
    }
}
