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

import org.evosuite.Properties;
import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Jaccard distance metric implementations.
 */
class JaccardSpeciesDistanceTest {

    @Test
    void jaccardDistanceIdenticalSetsIsZero() {
        Set<Integer> a = new HashSet<>(Arrays.asList(1, 2, 3));
        Set<Integer> b = new HashSet<>(Arrays.asList(1, 2, 3));
        assertEquals(0.0, JaccardSpeciesDistance.jaccardDistance(a, b), 1e-9);
    }

    @Test
    void jaccardDistanceDisjointSetsIsOne() {
        Set<Integer> a = new HashSet<>(Arrays.asList(1, 2));
        Set<Integer> b = new HashSet<>(Arrays.asList(3, 4));
        assertEquals(1.0, JaccardSpeciesDistance.jaccardDistance(a, b), 1e-9);
    }

    @Test
    void jaccardDistancePartialOverlap() {
        Set<Integer> a = new HashSet<>(Arrays.asList(1, 2, 3));
        Set<Integer> b = new HashSet<>(Arrays.asList(2, 3, 4));
        // intersection=2, union=4, distance=1-2/4=0.5
        assertEquals(0.5, JaccardSpeciesDistance.jaccardDistance(a, b), 1e-9);
    }

    @Test
    void jaccardDistanceBothEmptyIsOne() {
        assertEquals(1.0, JaccardSpeciesDistance.jaccardDistance(
                Collections.emptySet(), Collections.emptySet()), 1e-9);
    }

    @Test
    void jaccardDistanceOneEmptyIsOne() {
        Set<Integer> a = new HashSet<>(Arrays.asList(1, 2));
        assertEquals(1.0, JaccardSpeciesDistance.jaccardDistance(a, Collections.emptySet()), 1e-9);
    }

    @Test
    void traceBranchJaccardWithNullExecution() {
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance(
                SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        TestChromosome a = makeChromosomeWithBranches(null);
        TestChromosome b = makeChromosomeWithBranches(null);
        // Both null execution results → both empty sets → distance=1.0
        assertEquals(1.0, dist.distance(a, b), 1e-9);
    }

    @Test
    void traceBranchJaccardComputesCorrectly() {
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance(
                SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        TestChromosome a = makeChromosomeWithBranches(
                new HashSet<>(Arrays.asList(1, 2, 3)), Collections.emptySet());
        TestChromosome b = makeChromosomeWithBranches(
                new HashSet<>(Arrays.asList(2, 3, 4)), Collections.emptySet());
        // intersection={2,3}, union={1,2,3,4} → 1-2/4=0.5
        assertEquals(0.5, dist.distance(a, b), 1e-9);
    }

    @Test
    void traceLineJaccardComputesCorrectly() {
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance(
                SpeciationMetric.TRACE_LINE_JACCARD, 0.7);
        TestChromosome a = makeChromosomeWithLines(new HashSet<>(Arrays.asList(10, 20)));
        TestChromosome b = makeChromosomeWithLines(new HashSet<>(Arrays.asList(10, 30)));
        // intersection={10}, union={10,20,30} → 1-1/3≈0.667
        assertEquals(1.0 - 1.0 / 3.0, dist.distance(a, b), 1e-9);
    }

    @Test
    void methodCallJaccardComputesCorrectly() {
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance(
                SpeciationMetric.METHOD_CALL_JACCARD, 0.7);
        TestChromosome a = makeChromosomeWithMethods(Arrays.asList("Foo.bar", "Foo.baz"));
        TestChromosome b = makeChromosomeWithMethods(Arrays.asList("Foo.baz", "Foo.qux"));
        // intersection={Foo.baz}, union={Foo.bar,Foo.baz,Foo.qux} → 1-1/3
        assertEquals(1.0 - 1.0 / 3.0, dist.distance(a, b), 1e-9);
    }

    @Test
    void hybridMetricCombinesBothComponents() {
        double w = 0.6;
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.HYBRID, w);
        TestChromosome a = makeChromosomeWithBranchesAndMethods(
                new HashSet<>(Arrays.asList(1, 2)), Collections.emptySet(),
                Arrays.asList("A.m1"));
        TestChromosome b = makeChromosomeWithBranchesAndMethods(
                new HashSet<>(Arrays.asList(2, 3)), Collections.emptySet(),
                Arrays.asList("A.m2"));
        // branch: intersection={2}, union={1,2,3} → 1-1/3≈0.667
        // method: intersection={}, union={A.m1,A.m2} → 1.0
        double expectedBranch = 1.0 - 1.0 / 3.0;
        double expectedMethod = 1.0;
        double expected = w * expectedBranch + (1.0 - w) * expectedMethod;
        assertEquals(expected, dist.distance(a, b), 1e-9);
    }

    // --- helpers ---

    private TestChromosome makeChromosomeWithBranches(Set<Integer> trueBranches) {
        return makeChromosomeWithBranches(trueBranches, Collections.emptySet());
    }

    private TestChromosome makeChromosomeWithBranches(Set<Integer> trueBranches,
                                                       Set<Integer> falseBranches) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        if (trueBranches == null) {
            return tc; // no execution result
        }
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredTrueBranches()).thenReturn(trueBranches);
        when(trace.getCoveredFalseBranches()).thenReturn(
                falseBranches != null ? falseBranches : Collections.emptySet());
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }

    private TestChromosome makeChromosomeWithLines(Set<Integer> lines) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredLines()).thenReturn(lines);
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }

    private TestChromosome makeChromosomeWithMethods(List<String> signatures) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        List<MethodCall> calls = new ArrayList<>();
        for (String sig : signatures) {
            String[] parts = sig.split("\\.");
            MethodCall mc = mock(MethodCall.class);
            mc.className = parts[0];
            mc.methodName = parts[1];
            calls.add(mc);
        }
        when(trace.getMethodCalls()).thenReturn(calls);
        when(trace.getCoveredTrueBranches()).thenReturn(Collections.emptySet());
        when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }

    private TestChromosome makeChromosomeWithBranchesAndMethods(
            Set<Integer> trueBranches, Set<Integer> falseBranches,
            List<String> methodSigs) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredTrueBranches()).thenReturn(trueBranches);
        when(trace.getCoveredFalseBranches()).thenReturn(falseBranches);
        List<MethodCall> calls = new ArrayList<>();
        for (String sig : methodSigs) {
            String[] parts = sig.split("\\.");
            MethodCall mc = mock(MethodCall.class);
            mc.className = parts[0];
            mc.methodName = parts[1];
            calls.add(mc);
        }
        when(trace.getMethodCalls()).thenReturn(calls);
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }
}
