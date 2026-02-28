/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.diversity;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for STFDistance (State Transition Frequency distance).
 */
class STFDistanceTest {

    @Test
    void bothEmptyTracesReturnMaxDistance() {
        TestChromosome a = makeChromosomeWithBranchSequence(Collections.emptyList());
        TestChromosome b = makeChromosomeWithBranchSequence(Collections.emptyList());
        assertEquals(1.0, STFDistance.stfCosineDistance(a, b), 1e-9);
    }

    @Test
    void oneEmptyTraceReturnsMaxDistance() {
        TestChromosome a = makeChromosomeWithBranchSequence(Arrays.asList(1, 2, 3));
        TestChromosome b = makeChromosomeWithBranchSequence(Collections.emptyList());
        assertEquals(1.0, STFDistance.stfCosineDistance(a, b), 1e-9);
    }

    @Test
    void identicalTracesReturnZeroDistance() {
        List<Integer> seq = Arrays.asList(1, 2, 3, 2, 1);
        TestChromosome a = makeChromosomeWithBranchSequence(seq);
        TestChromosome b = makeChromosomeWithBranchSequence(seq);
        assertEquals(0.0, STFDistance.stfCosineDistance(a, b), 1e-9);
    }

    @Test
    void disjointTransitionsReturnMaxDistance() {
        // a: transitions (1→2), (2→3)
        TestChromosome a = makeChromosomeWithBranchSequence(Arrays.asList(1, 2, 3));
        // b: transitions (4→5), (5→6)
        TestChromosome b = makeChromosomeWithBranchSequence(Arrays.asList(4, 5, 6));
        assertEquals(1.0, STFDistance.stfCosineDistance(a, b), 1e-9);
    }

    @Test
    void partialOverlapDistanceInRange() {
        // a: transitions (1→2), (2→3)
        TestChromosome a = makeChromosomeWithBranchSequence(Arrays.asList(1, 2, 3));
        // b: transitions (2→3), (3→4) — shares (2→3) with a
        TestChromosome b = makeChromosomeWithBranchSequence(Arrays.asList(2, 3, 4));
        double dist = STFDistance.stfCosineDistance(a, b);
        assertTrue(dist > 0.0 && dist < 1.0,
                "Partial overlap should yield distance in (0,1): " + dist);
    }

    @Test
    void singleBranchNoTransitionsReturnsMax() {
        TestChromosome a = makeChromosomeWithBranchSequence(Arrays.asList(1));
        TestChromosome b = makeChromosomeWithBranchSequence(Arrays.asList(2));
        // Single branch → no bigrams → empty frequency map
        assertEquals(1.0, STFDistance.stfCosineDistance(a, b), 1e-9);
    }

    @Test
    void resultIsBoundedZeroOne() {
        TestChromosome a = makeChromosomeWithBranchSequence(Arrays.asList(1, 2, 3, 4, 5));
        TestChromosome b = makeChromosomeWithBranchSequence(Arrays.asList(2, 3, 4, 5, 6));
        double dist = STFDistance.stfCosineDistance(a, b);
        assertTrue(dist >= 0.0 && dist <= 1.0, "Distance should be in [0,1]: " + dist);
    }

    @Test
    void hybridSTFWithJaccardWeighting() {
        STFDistance pureStf = new STFDistance();
        JaccardSpeciesDistance jaccard = new JaccardSpeciesDistance(
                org.evosuite.Properties.SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        STFDistance hybrid = new STFDistance(0.5, jaccard);

        TestChromosome a = makeChromosomeWithBranchSequenceAndCoverage(
                Arrays.asList(1, 2, 3),
                new HashSet<>(Arrays.asList(1, 2)), Collections.emptySet());
        TestChromosome b = makeChromosomeWithBranchSequenceAndCoverage(
                Arrays.asList(2, 3, 4),
                new HashSet<>(Arrays.asList(2, 3)), Collections.emptySet());

        double stfDist = pureStf.distance(a, b);
        double jDist = jaccard.distance(a, b);
        double hybridDist = hybrid.distance(a, b);
        assertEquals(0.5 * jDist + 0.5 * stfDist, hybridDist, 1e-9);
    }

    @Test
    void nullExecutionResultReturnsEmptyFrequencies() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        Map<Long, Integer> freq = STFDistance.getBranchTransitionFrequencies(tc);
        assertTrue(freq.isEmpty());
    }

    @Test
    void frequencyCountIsCorrect() {
        // Sequence: 1, 2, 1, 2 → transitions: (1→2), (2→1), (1→2)
        // Expected: (1→2)=2, (2→1)=1
        TestChromosome tc = makeChromosomeWithBranchSequence(Arrays.asList(1, 2, 1, 2));
        Map<Long, Integer> freq = STFDistance.getBranchTransitionFrequencies(tc);
        assertEquals(2, freq.size());
        long key12 = ((long) 1 << 32) | (2 & 0xFFFFFFFFL);
        long key21 = ((long) 2 << 32) | (1 & 0xFFFFFFFFL);
        assertEquals(2, freq.get(key12));
        assertEquals(1, freq.get(key21));
    }

    // --- helpers ---

    private TestChromosome makeChromosomeWithBranchSequence(List<Integer> branchSeq) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        if (branchSeq.isEmpty()) {
            // Still set an execution result with empty trace
            ExecutionResult result = new ExecutionResult(tc.getTestCase());
            ExecutionTrace trace = mock(ExecutionTrace.class);
            when(trace.getMethodCalls()).thenReturn(Collections.emptyList());
            result.setTrace(trace);
            tc.setLastExecutionResult(result);
            return tc;
        }
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        MethodCall mc = new MethodCall("Test", "test", 0, 0, 0);
        mc.branchTrace = new ArrayList<>(branchSeq);
        when(trace.getMethodCalls()).thenReturn(Collections.singletonList(mc));
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }

    private TestChromosome makeChromosomeWithBranchSequenceAndCoverage(
            List<Integer> branchSeq,
            Set<Integer> trueBranches, Set<Integer> falseBranches) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        MethodCall mc = new MethodCall("Test", "test", 0, 0, 0);
        mc.branchTrace = new ArrayList<>(branchSeq);
        when(trace.getMethodCalls()).thenReturn(Collections.singletonList(mc));
        when(trace.getCoveredTrueBranches()).thenReturn(trueBranches);
        when(trace.getCoveredFalseBranches()).thenReturn(falseBranches);
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }
}
