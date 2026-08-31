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
package org.evosuite.runtime;

/**
 * Tracks how many array elements a single test execution has allocated across every
 * NEWARRAY/ANEWARRAY site in the classes under analysis, and refuses further allocation
 * once the running total crosses {@link RuntimeSettings#maxCumulativeArrayElements}.
 *
 * <p>{@code org.evosuite.instrumentation.ArrayAllocationLimitMethodAdapter} already
 * rejects any single allocation over {@code array_limit}, but a loop that allocates many
 * individually-legal arrays -- e.g. one chunk per iteration in a chunked-array growth
 * method, each well under the per-call limit -- is invisible to that check and can still
 * exhaust the heap before any other safety net (the loop-iteration cap, the -mem
 * watchdog) gets a chance to fire. Counting the cumulative size closes that gap.
 *
 * <p>Mirrors the reset lifecycle of {@link LoopCounter}: {@link Runtime#resetRuntime()}
 * clears the running total before every test execution.
 */
public class ArrayAllocationBudget {

    /**
     * Running total of array elements allocated so far by whichever test execution is
     * currently using this thread. Not thread-confined on purpose: like
     * {@link LoopCounter}, this assumes a single test-execution thread is active at a
     * time and is reset before each one starts.
     */
    private static volatile long allocatedElements = 0L;

    private ArrayAllocationBudget() {
    }

    /**
     * Reset the running total for the next test.
     */
    public static void reset() {
        allocatedElements = 0L;
    }

    /**
     * Record an allocation of {@code elements} array slots and reject it if the
     * cumulative total for this test now exceeds the configured budget.
     *
     * <p>Called from bytecode instrumented into the classes under analysis, right after
     * a NEWARRAY/ANEWARRAY whose own size already passed the per-call {@code
     * array_limit} check.
     *
     * @param elements number of elements the allocation about to happen will occupy
     * @throws TooManyResourcesException if the cumulative budget has been exceeded
     */
    public static void checkAndRecord(long elements) {
        if (RuntimeSettings.maxCumulativeArrayElements < 0) {
            return; // disabled
        }
        allocatedElements += elements;
        if (allocatedElements > RuntimeSettings.maxCumulativeArrayElements) {
            throw new TooManyResourcesException("Cumulative array allocation for this test exceeds the "
                    + "configured cumulative_array_limit of " + RuntimeSettings.maxCumulativeArrayElements
                    + " elements (" + allocatedElements + " requested so far)");
        }
    }
}
