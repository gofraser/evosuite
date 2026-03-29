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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestRelevanceRankerTest {

    private boolean originalRelevanceSelection;
    private double originalMethodOverlapWeight;

    @BeforeEach
    void saveProperties() {
        originalRelevanceSelection = Properties.LLM_RELEVANCE_BASED_TEST_SELECTION;
        originalMethodOverlapWeight = Properties.LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT;
        Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = true;
        Properties.LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT = 2.0;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = originalRelevanceSelection;
        Properties.LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT = originalMethodOverlapWeight;
    }

    @Test
    void rankByRelevance_emptyInputs() {
        TestFitnessFunction goal = mockGoal("Foo", "bar");
        Collection<TestFitnessFunction> goals = Collections.singletonList(goal);

        assertTrue(TestRelevanceRanker.rankByRelevance(null, goals, 3).isEmpty());
        assertTrue(TestRelevanceRanker.rankByRelevance(Collections.emptyList(), goals, 3).isEmpty());
        assertTrue(TestRelevanceRanker.rankByRelevance(
                Collections.singletonList(mockChromosome(10.0, null)),
                null, 3).size() <= 1);
        assertTrue(TestRelevanceRanker.rankByRelevance(
                Collections.singletonList(mockChromosome(10.0, null)),
                Collections.emptyList(), 3).size() <= 1);
    }

    @Test
    void rankByRelevance_respectsMaxTests() {
        TestFitnessFunction goal = mockGoal("Foo", "bar");
        List<TestChromosome> candidates = Arrays.asList(
                mockChromosomeWithExec(1.0, goal, Collections.emptySet()),
                mockChromosomeWithExec(2.0, goal, Collections.emptySet()),
                mockChromosomeWithExec(3.0, goal, Collections.emptySet()),
                mockChromosomeWithExec(4.0, goal, Collections.emptySet()));

        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                candidates, Collections.singletonList(goal), 2);
        assertEquals(2, result.size());
    }

    @Test
    void rankByRelevance_prefersNearMissTests() {
        TestFitnessFunction goal = mockGoal("Foo", "bar");

        TestChromosome nearMiss = mockChromosomeWithExec(0.01, goal, Collections.emptySet());
        TestChromosome farAway = mockChromosomeWithExec(10.0, goal, Collections.emptySet());

        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                Arrays.asList(farAway, nearMiss),
                Collections.singletonList(goal), 2);

        assertSame(nearMiss, result.get(0), "Near-miss test should rank first");
        assertSame(farAway, result.get(1));
    }

    @Test
    void rankByRelevance_prefersMethodOverlap() {
        TestFitnessFunction goal = mockGoal("com.example.Foo", "bar");

        // Both have same fitness, but one exercises the target method
        Set<String> overlapping = new HashSet<>(Collections.singletonList("com.example.Foo.bar"));
        Set<String> unrelated = new HashSet<>(Collections.singletonList("com.example.Baz.qux"));

        TestChromosome withOverlap = mockChromosomeWithExec(5.0, goal, overlapping);
        TestChromosome noOverlap = mockChromosomeWithExec(5.0, goal, unrelated);

        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                Arrays.asList(noOverlap, withOverlap),
                Collections.singletonList(goal), 2);

        assertSame(withOverlap, result.get(0), "Test exercising target method should rank first");
    }

    @Test
    void rankByRelevance_combinedScoring() {
        TestFitnessFunction goal = mockGoal("com.example.Foo", "bar");

        // nearMiss has great fitness but no method overlap
        TestChromosome nearMiss = mockChromosomeWithExec(0.01, goal, Collections.emptySet());
        // methodMatch has poor fitness but exercises the target method
        Set<String> overlapping = new HashSet<>(Collections.singletonList("com.example.Foo.bar"));
        TestChromosome methodMatch = mockChromosomeWithExec(5.0, goal, overlapping);

        // With weight=2.0: methodMatch score = 1/(1+5) + 2*1 = 2.167
        //                   nearMiss score   = 1/(1+0.01) + 2*0 = 0.99
        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                Arrays.asList(nearMiss, methodMatch),
                Collections.singletonList(goal), 2);

        assertSame(methodMatch, result.get(0),
                "Method overlap (weighted 2x) should outweigh near-miss fitness");
    }

    @Test
    void rankByRelevance_fallbackOnMissingExecutionResult() {
        TestFitnessFunction goal = mockGoal("Foo", "bar");

        // Chromosomes with NO execution result
        TestChromosome first = mockChromosome(5.0, null);
        TestChromosome second = mockChromosome(1.0, null);

        // Should return in input order (first-N fallback)
        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                Arrays.asList(first, second),
                Collections.singletonList(goal), 2);

        assertEquals(2, result.size());
        assertSame(first, result.get(0), "Should preserve input order when no execution results");
        assertSame(second, result.get(1));
    }

    @Test
    void rankByRelevance_disabledByProperty() {
        Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = false;

        TestFitnessFunction goal = mockGoal("Foo", "bar");
        TestChromosome farAway = mockChromosomeWithExec(10.0, goal, Collections.emptySet());
        TestChromosome nearMiss = mockChromosomeWithExec(0.01, goal, Collections.emptySet());

        // Should return in input order regardless of fitness
        List<TestChromosome> result = TestRelevanceRanker.rankByRelevance(
                Arrays.asList(farAway, nearMiss),
                Collections.singletonList(goal), 2);

        assertSame(farAway, result.get(0), "Should preserve input order when disabled");
    }

    @Test
    void stripDescriptor_handlesVariousFormats() {
        assertEquals("foo", TestRelevanceRanker.stripDescriptor("foo(I)V"));
        assertEquals("bar", TestRelevanceRanker.stripDescriptor("bar(Ljava/lang/String;Z)I"));
        assertEquals("baz", TestRelevanceRanker.stripDescriptor("baz"));
        assertEquals("<init>", TestRelevanceRanker.stripDescriptor("<init>(I)V"));
        assertEquals("", TestRelevanceRanker.stripDescriptor(null));
    }

    @Test
    void goalMethodKey_combinedClassAndMethod() {
        TestFitnessFunction goal = mockGoal("com.example.Foo", "bar(I)V");
        assertEquals("com.example.Foo.bar", TestRelevanceRanker.goalMethodKey(goal));
    }

    @Test
    void goalMethodKey_returnsNullForMissingInfo() {
        TestFitnessFunction noClass = mock(TestFitnessFunction.class);
        when(noClass.getTargetClass()).thenReturn(null);
        when(noClass.getTargetMethod()).thenReturn("bar");
        assertNull(TestRelevanceRanker.goalMethodKey(noClass));

        TestFitnessFunction noMethod = mock(TestFitnessFunction.class);
        when(noMethod.getTargetClass()).thenReturn("Foo");
        when(noMethod.getTargetMethod()).thenReturn(null);
        assertNull(TestRelevanceRanker.goalMethodKey(noMethod));
    }

    @Test
    void computeNearMissScore_higherForCloserFitness() {
        TestFitnessFunction goal = mockGoal("Foo", "bar");

        TestChromosome close = mockChromosome(0.01, null);
        when(close.getFitness(goal)).thenReturn(0.01);
        TestChromosome far = mockChromosome(10.0, null);
        when(far.getFitness(goal)).thenReturn(10.0);

        double closeScore = TestRelevanceRanker.computeNearMissScore(
                close, Collections.singletonList(goal));
        double farScore = TestRelevanceRanker.computeNearMissScore(
                far, Collections.singletonList(goal));

        assertTrue(closeScore > farScore,
                "Near-miss test (fitness=0.01) should score higher than far test (fitness=10)");
        // 1/(1+0.01) ≈ 0.99, 1/(1+10) ≈ 0.09
        assertTrue(closeScore > 0.9);
        assertTrue(farScore < 0.15);
    }

    @Test
    void computeMethodOverlap_countsMatchingMethods() {
        Set<String> goalKeys = new HashSet<>(Arrays.asList(
                "com.example.Foo.bar", "com.example.Foo.baz", "com.example.Bar.qux"));

        Set<String> executedMethods = new HashSet<>(Arrays.asList(
                "com.example.Foo.bar", "com.example.Bar.qux", "com.example.Other.nope"));

        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(executedMethods);
        ExecutionResult execResult = mock(ExecutionResult.class);
        when(execResult.getTrace()).thenReturn(trace);
        TestChromosome candidate = mock(TestChromosome.class);
        when(candidate.getLastExecutionResult()).thenReturn(execResult);

        int overlap = TestRelevanceRanker.computeMethodOverlap(candidate, goalKeys);
        assertEquals(2, overlap, "Should match bar and qux, not baz or nope");
    }

    // --- helpers ---

    private TestFitnessFunction mockGoal(String className, String method) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn(method);
        when(goal.toString()).thenReturn(className + "." + method);
        return goal;
    }

    /** Creates a mock chromosome with a specific fitness for the given goal, NO execution result. */
    private TestChromosome mockChromosome(double fitness,
                                          ExecutionResult execResult) {
        TestChromosome tc = mock(TestChromosome.class);
        when(tc.getLastExecutionResult()).thenReturn(execResult);
        return tc;
    }

    /** Creates a mock chromosome with execution result and covered methods. */
    private TestChromosome mockChromosomeWithExec(double fitnessForGoal,
                                                  TestFitnessFunction goal,
                                                  Set<String> coveredMethods) {
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredMethods()).thenReturn(coveredMethods);
        ExecutionResult execResult = mock(ExecutionResult.class);
        when(execResult.getTrace()).thenReturn(trace);

        TestChromosome tc = mock(TestChromosome.class);
        when(tc.getLastExecutionResult()).thenReturn(execResult);
        when(tc.getFitness(goal)).thenReturn(fitnessForGoal);
        return tc;
    }
}
