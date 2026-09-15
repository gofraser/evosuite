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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not run by default (needs {@code -Dbranch.operand.benchmark=true}): measures how much
 * {@link Properties#LLM_BRANCH_OPERAND_HINTS} adds to the cost of
 * {@link ExecutionTracer#passedBranch(int, int, int, int, int)}, which runs once per executed
 * branch predicate in every generated test.
 */
public class BranchOperandOverheadBenchmarkTest {

    private static final int BRANCH_COUNT = 64;
    private static final long CALLS_PER_ROUND = 5_000_000L;
    private static final int ROUNDS = 5;

    @AfterEach
    public void tearDown() {
        Properties.LLM_BRANCH_OPERAND_HINTS = false;
        ExecutionTracer.setCheckCallerThread(true);
        ExecutionTracer.getExecutionTracer().clear();
        ExecutionTracer.disable();
    }

    @Test
    public void testOverheadOfRecordingOperands() {
        Assumptions.assumeTrue(Boolean.getBoolean("branch.operand.benchmark"));

        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);

        // warm up both code paths before timing either
        run(false, CALLS_PER_ROUND / 10);
        run(true, CALLS_PER_ROUND / 10);

        double[] offNanosPerCall = new double[ROUNDS];
        double[] onNanosPerCall = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            offNanosPerCall[round] = run(false, CALLS_PER_ROUND);
            onNanosPerCall[round] = run(true, CALLS_PER_ROUND);
        }

        double off = median(offNanosPerCall);
        double on = median(onNanosPerCall);
        double overheadPct = (on - off) / off * 100.0;

        System.out.printf("ns/call off=%.2f on=%.2f overhead=%.1f%%%n", off, on, overheadPct);

        assertTrue(on / off < 3.0,
                "recording operands should not triple the cost of passedBranch (was " + (on / off) + "x)");
    }

    /** Runs {@code calls} invocations of passedBranch and returns the nanoseconds per call. */
    private double run(boolean withOperandHints, long calls) {
        Properties.LLM_BRANCH_OPERAND_HINTS = withOperandHints;
        ExecutionTracer.getExecutionTracer().clear();

        long start = System.nanoTime();
        for (long i = 0; i < calls; i++) {
            int branch = (int) (i % BRANCH_COUNT);
            ExecutionTracer.passedBranch((int) i, BRANCH_COUNT, Opcodes.IF_ICMPLE, branch, 1);
        }
        long elapsed = System.nanoTime() - start;
        return elapsed / (double) calls;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
