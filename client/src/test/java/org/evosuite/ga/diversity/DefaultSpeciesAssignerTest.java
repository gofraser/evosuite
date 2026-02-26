package org.evosuite.ga.diversity;

import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for deterministic species assignment.
 */
class DefaultSpeciesAssignerTest {

    @Test
    void singleIndividualFormsSingleSpecies() {
        TestChromosome tc = makeChromosome(new HashSet<>(Arrays.asList(1, 2, 3)));
        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.3);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(
                Collections.singletonList(tc));

        assertEquals(1, species.size());
        assertEquals(1, species.get(0).size());
        assertSame(tc, species.get(0).get(0));
    }

    @Test
    void identicalIndividualsSameSpecies() {
        TestChromosome a = makeChromosome(new HashSet<>(Arrays.asList(1, 2, 3)));
        TestChromosome b = makeChromosome(new HashSet<>(Arrays.asList(1, 2, 3)));

        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.5);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(Arrays.asList(a, b));

        assertEquals(1, species.size(), "Identical individuals should be in same species");
        assertEquals(2, species.get(0).size());
    }

    @Test
    void disjointIndividualsFormDifferentSpecies() {
        TestChromosome a = makeChromosome(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosome(new HashSet<>(Arrays.asList(3, 4)));

        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        // threshold 0.5 means distance must be ≤ 0.5 to belong; disjoint = 1.0 → different
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.5);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(Arrays.asList(a, b));

        assertEquals(2, species.size(), "Disjoint individuals should be in different species");
    }

    @Test
    void thresholdOneBoundaryMergesAll() {
        TestChromosome a = makeChromosome(new HashSet<>(Arrays.asList(1)));
        TestChromosome b = makeChromosome(new HashSet<>(Arrays.asList(2)));

        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 1.0);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(Arrays.asList(a, b));

        assertEquals(1, species.size(), "Threshold 1.0 should merge all into one species");
    }

    @Test
    void thresholdZeroSeparatesAll() {
        TestChromosome a = makeChromosome(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosome(new HashSet<>(Arrays.asList(1, 3)));

        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.0);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(Arrays.asList(a, b));

        // distance = 1-1/3 ≈ 0.33 > 0.0, so different species
        assertEquals(2, species.size());
    }

    @Test
    void deterministic_orderMatters() {
        // First individual always becomes leader of species 0
        TestChromosome a = makeChromosome(new HashSet<>(Arrays.asList(1, 2)));
        TestChromosome b = makeChromosome(new HashSet<>(Arrays.asList(3, 4)));
        TestChromosome c = makeChromosome(new HashSet<>(Arrays.asList(1, 2))); // same as a

        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.5);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(
                Arrays.asList(a, b, c));

        assertEquals(2, species.size());
        assertTrue(species.get(0).contains(a));
        assertTrue(species.get(0).contains(c)); // c matches a's species
        assertTrue(species.get(1).contains(b));
    }

    @Test
    void emptyPopulationReturnsEmptyMap() {
        SpeciesDistance dist = new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7);
        DefaultSpeciesAssigner assigner = new DefaultSpeciesAssigner(dist, 0.3);

        Map<Integer, List<TestChromosome>> species = assigner.groupBySpecies(Collections.emptyList());
        assertTrue(species.isEmpty());
    }

    private TestChromosome makeChromosome(Set<Integer> trueBranches) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        ExecutionResult result = new ExecutionResult(tc.getTestCase());
        ExecutionTrace trace = mock(ExecutionTrace.class);
        when(trace.getCoveredTrueBranches()).thenReturn(trueBranches);
        when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
        result.setTrace(trace);
        tc.setLastExecutionResult(result);
        return tc;
    }
}
