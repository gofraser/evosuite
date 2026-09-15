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
 * branch predicate in every generated test, plus how an object-comparison branch (which always
 * allocates the bounded rendered text) compares.
 */
public class BranchOperandOverheadBenchmarkTest {

    private static final int BRANCH_COUNT = 64;
    private static final long CALLS_PER_ROUND = 5_000_000L;
    private static final long WARMUP_CALLS = CALLS_PER_ROUND / 10;
    private static final int ROUNDS = 7;

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

        // Warm up every configuration before timing any of them: this also allocates the 64
        // BranchOperandSlots up front, so the timed "on" rounds measure steady-state slot
        // reuse rather than slot-array growth.
        runInts(false, WARMUP_CALLS);
        runInts(true, WARMUP_CALLS);
        runObjects(false, WARMUP_CALLS);
        runObjects(true, WARMUP_CALLS);

        double[] intsOff = new double[ROUNDS];
        double[] intsOn = new double[ROUNDS];
        double[] objectsOff = new double[ROUNDS];
        double[] objectsOn = new double[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            // alternate configurations within each round rather than running each
            // configuration as one long block, so warm-up/thermal drift affects them evenly
            intsOff[round] = runInts(false, CALLS_PER_ROUND);
            intsOn[round] = runInts(true, CALLS_PER_ROUND);
            objectsOff[round] = runObjects(false, CALLS_PER_ROUND);
            objectsOn[round] = runObjects(true, CALLS_PER_ROUND);
        }

        double off = median(intsOff);
        double on = median(intsOn);
        double overheadPct = (on - off) / off * 100.0;

        double objOff = median(objectsOff);
        double objOn = median(objectsOn);
        double objOverheadPct = (objOn - objOff) / objOff * 100.0;

        System.out.printf("ns/call ints:    off=%.2f on=%.2f overhead=%.1f%% (on/off=%.3fx)%n",
                off, on, overheadPct, on / off);
        System.out.printf("ns/call objects: off=%.2f on=%.2f overhead=%.1f%% (on/off=%.3fx)%n",
                objOff, objOn, objOverheadPct, objOn / objOff);

        // The property's cost on the int path must be a few percent of one passedBranch call,
        // which is itself a small fraction of total test execution time.
        assertTrue(on / off <= 1.05,
                "recording int operands should add at most ~5% to passedBranch (was " + (on / off) + "x)");
    }

    /** Runs {@code calls} int-comparison branch evaluations, cycling {@link #BRANCH_COUNT} ids. */
    private double runInts(boolean withOperandHints, long calls) {
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

    /**
     * Runs {@code calls} object-comparison branch evaluations (a short String against an
     * Integer, as {@code IF_ACMPEQ} would see on the operand stack), cycling
     * {@link #BRANCH_COUNT} ids.
     */
    private double runObjects(boolean withOperandHints, long calls) {
        Properties.LLM_BRANCH_OPERAND_HINTS = withOperandHints;
        ExecutionTracer.getExecutionTracer().clear();
        String left = "sample-value";

        long start = System.nanoTime();
        for (long i = 0; i < calls; i++) {
            int branch = (int) (i % BRANCH_COUNT);
            Integer right = (int) (i % 100);
            ExecutionTracer.passedBranch(left, right, Opcodes.IF_ACMPEQ, branch, 1);
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
