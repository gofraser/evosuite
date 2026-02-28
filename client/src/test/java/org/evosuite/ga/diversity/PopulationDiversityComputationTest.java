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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PopulationDiversityComputation utility.
 */
class PopulationDiversityComputationTest {

    @BeforeEach
    void setup() {
        Properties.SPECIATION_METRIC = SpeciationMetric.TRACE_BRANCH_JACCARD;
        Properties.DIVERSITY_SAMPLE_SIZE = 0;
        Properties.STF_ENABLED = false;
        Properties.STF_JACCARD_WEIGHT = 0.0;
    }

    @Test
    void emptyPopulationReturnsZero() {
        assertEquals(0.0, PopulationDiversityComputation.computeDiversity(
                Collections.emptyList()), 1e-9);
    }

    @Test
    void singleIndividualReturnsZero() {
        TestChromosome tc = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2)));
        assertEquals(0.0, PopulationDiversityComputation.computeDiversity(
                Collections.singletonList(tc)), 1e-9);
    }

    @Test
    void identicalIndividualsReturnZeroDiversity() {
        TestChromosome a = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2, 3)));
        TestChromosome b = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2, 3)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        assertEquals(0.0, diversity, 1e-9);
    }

    @Test
    void disjointIndividualsReturnMaxDiversity() {
        TestChromosome a = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(3, 4)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        assertEquals(1.0, diversity, 1e-9);
    }

    @Test
    void partialOverlapReturnsExpectedDiversity() {
        TestChromosome a = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2, 3)));
        TestChromosome b = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(2, 3, 4)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        // Jaccard distance = 1 - 2/4 = 0.5
        assertEquals(0.5, diversity, 1e-9);
    }

    @Test
    void resultIsBoundedZeroOne() {
        TestChromosome a = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(3, 4)));
        TestChromosome c = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 3)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b, c));
        assertTrue(diversity >= 0.0 && diversity <= 1.0,
                "Diversity should be in [0,1]: " + diversity);
    }

    @Test
    void threeIndividualsMeanPairwiseDistance() {
        TestChromosome a = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(2, 3)));
        TestChromosome c = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(3, 4)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b, c));
        // d(a,b)=1-1/3, d(a,c)=1.0, d(b,c)=1-1/3
        // mean = (2/3 + 1.0 + 2/3) / 3 = (7/3)/3 = 7/9
        assertEquals(7.0 / 9.0, diversity, 1e-9);
    }

    @Test
    void respectsSpeciationMetricSetting() {
        Properties.SPECIATION_METRIC = SpeciationMetric.TRACE_LINE_JACCARD;
        TestChromosome a = makeChromosomeWithLines(new HashSet<>(Arrays.asList(10, 20)));
        TestChromosome b = makeChromosomeWithLines(new HashSet<>(Arrays.asList(20, 30)));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        // Line Jaccard: intersection={20}, union={10,20,30} → 1-1/3
        assertEquals(1.0 - 1.0 / 3.0, diversity, 1e-9);
    }

    @Test
    void samplingProducesBoundedResult() {
        Properties.DIVERSITY_SAMPLE_SIZE = 5;
        List<TestChromosome> pop = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pop.add(makeChromosomeWithBranches(new HashSet<>(Arrays.asList(i, i + 1))));
        }
        double diversity = PopulationDiversityComputation.computeDiversity(pop);
        assertTrue(diversity >= 0.0 && diversity <= 1.0,
                "Sampled diversity should be in [0,1]: " + diversity);
    }

    @Test
    void explicitDistanceFunctionIsUsed() {
        SpeciesDistance always05 = (a, b) -> 0.5;
        TestChromosome x = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(1)));
        TestChromosome y = makeChromosomeWithBranches(new HashSet<>(Arrays.asList(2)));
        double diversity = PopulationDiversityComputation.computeDiversity(
                Arrays.asList(x, y), always05, 0);
        assertEquals(0.5, diversity, 1e-9);
    }

    @Test
    void stfDisabledUsesJaccard() {
        Properties.STF_ENABLED = false;
        TestChromosome a = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(1, 2)), Arrays.asList(1, 2, 3));
        TestChromosome b = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(2, 3)), Arrays.asList(4, 5, 6));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        // Should use Jaccard: intersection={2}, union={1,2,3} → 1-1/3
        assertEquals(1.0 - 1.0 / 3.0, diversity, 1e-9);
    }

    @Test
    void stfEnabledPureUsesSTFDistance() {
        Properties.STF_ENABLED = true;
        Properties.STF_JACCARD_WEIGHT = 0.0;
        List<Integer> seqA = Arrays.asList(1, 2, 3);
        List<Integer> seqB = Arrays.asList(1, 2, 3);
        TestChromosome a = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(1, 2)), seqA);
        TestChromosome b = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(3, 4)), seqB);
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        // Identical branch sequences → STF distance = 0.0
        assertEquals(0.0, diversity, 1e-9);
    }

    @Test
    void stfEnabledHybridCombinesMetrics() {
        Properties.STF_ENABLED = true;
        Properties.STF_JACCARD_WEIGHT = 0.5;
        TestChromosome a = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(1, 2)), Arrays.asList(1, 2, 3));
        TestChromosome b = makeChromosomeWithBranchesAndSequence(
                new HashSet<>(Arrays.asList(3, 4)), Arrays.asList(4, 5, 6));
        double diversity = PopulationDiversityComputation.computeDiversity(Arrays.asList(a, b));
        // Should be a blend of Jaccard (=1.0 disjoint branches) and STF (=1.0 disjoint transitions)
        assertTrue(diversity >= 0.0 && diversity <= 1.0,
                "Hybrid diversity should be in [0,1]: " + diversity);
    }

    // --- helpers ---

    private TestChromosome makeChromosomeWithBranches(Set<Integer> branches) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredTrueBranches()).thenReturn(branches);
        when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
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
        when(trace.getCoveredTrueBranches()).thenReturn(Collections.emptySet());
        when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }

    private TestChromosome makeChromosomeWithBranchesAndSequence(
            Set<Integer> branches, List<Integer> branchSeq) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredTrueBranches()).thenReturn(branches);
        when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
        MethodCall mc = new MethodCall("Test", "test", 0, 0, 0);
        mc.branchTrace = new ArrayList<>(branchSeq);
        when(trace.getMethodCalls()).thenReturn(Collections.singletonList(mc));
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }
}
