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
package org.evosuite.ga.diversity;

import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StableSpeciesAssigner}: ID stability across generations,
 * dormancy revival, and ID retirement.
 */
class StableSpeciesAssignerTest {

    @Test
    void idsArePreservedAcrossGenerationsWhenSameClusters() {
        TestChromosome a1 = makeChromosome(setOf(1, 2));
        TestChromosome b1 = makeChromosome(setOf(3, 4));

        StableSpeciesAssigner assigner = new StableSpeciesAssigner(
                new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7), 0.4, 5);

        Map<Integer, List<TestChromosome>> gen0 = assigner.groupBySpecies(Arrays.asList(a1, b1));
        int idA = lookupSpecies(gen0, a1);
        int idB = lookupSpecies(gen0, b1);
        assertNotEquals(idA, idB);

        assigner.advanceGeneration();

        // New chromosomes near the original cluster centers should reuse the same IDs.
        TestChromosome a2 = makeChromosome(setOf(1, 2));
        TestChromosome b2 = makeChromosome(setOf(3, 4));
        Map<Integer, List<TestChromosome>> gen1 = assigner.groupBySpecies(Arrays.asList(a2, b2));

        assertEquals(idA, lookupSpecies(gen1, a2),
                "Cluster near species A leader should keep id " + idA);
        assertEquals(idB, lookupSpecies(gen1, b2),
                "Cluster near species B leader should keep id " + idB);
    }

    @Test
    void newClusterMintsNewMonotonicId() {
        TestChromosome a = makeChromosome(setOf(1, 2));
        TestChromosome b = makeChromosome(setOf(3, 4));
        StableSpeciesAssigner assigner = new StableSpeciesAssigner(
                new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7), 0.4, 5);

        Map<Integer, List<TestChromosome>> gen0 = assigner.groupBySpecies(Arrays.asList(a, b));
        int idA = lookupSpecies(gen0, a);
        int idB = lookupSpecies(gen0, b);

        assigner.advanceGeneration();

        TestChromosome c = makeChromosome(setOf(7, 8));
        Map<Integer, List<TestChromosome>> gen1 = assigner.groupBySpecies(
                Arrays.asList(a, b, c));
        int idC = lookupSpecies(gen1, c);

        assertNotEquals(idA, idC);
        assertNotEquals(idB, idC);
        assertTrue(idC > Math.max(idA, idB),
                "New species ID should be strictly greater than any existing ID");
    }

    @Test
    void dormantSpeciesRevivesIfClusterReappearsWithinWindow() {
        TestChromosome a = makeChromosome(setOf(1, 2));
        TestChromosome b = makeChromosome(setOf(3, 4));
        StableSpeciesAssigner assigner = new StableSpeciesAssigner(
                new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7), 0.4, 3);

        // Gen 0: both species present.
        Map<Integer, List<TestChromosome>> gen0 = assigner.groupBySpecies(Arrays.asList(a, b));
        int idA = lookupSpecies(gen0, a);
        int idB = lookupSpecies(gen0, b);
        assigner.advanceGeneration();

        // Gen 1: species B vanishes.
        assigner.groupBySpecies(Collections.singletonList(a));
        assigner.advanceGeneration();

        // Gen 2: still no B; still within dormant window (3).
        assigner.groupBySpecies(Collections.singletonList(a));
        assigner.advanceGeneration();

        // Gen 3: B-like cluster reappears.
        TestChromosome bRevived = makeChromosome(setOf(3, 4));
        Map<Integer, List<TestChromosome>> gen3 = assigner.groupBySpecies(
                Arrays.asList(a, bRevived));
        assertEquals(idA, lookupSpecies(gen3, a));
        assertEquals(idB, lookupSpecies(gen3, bRevived),
                "Cluster reappearing within dormant window should keep original ID");
    }

    @Test
    void dormantSpeciesRetiredAfterWindowExpires() {
        TestChromosome a = makeChromosome(setOf(1, 2));
        TestChromosome b = makeChromosome(setOf(3, 4));
        StableSpeciesAssigner assigner = new StableSpeciesAssigner(
                new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7), 0.4, 1);

        Map<Integer, List<TestChromosome>> gen0 = assigner.groupBySpecies(Arrays.asList(a, b));
        int idB = lookupSpecies(gen0, b);
        assigner.advanceGeneration();
        assertEquals(2, assigner.registrySize());

        // Two generations without B → exceeds window (1) → B retired.
        assigner.groupBySpecies(Collections.singletonList(a));
        assigner.advanceGeneration();
        assigner.groupBySpecies(Collections.singletonList(a));
        assigner.advanceGeneration();

        assertEquals(1, assigner.registrySize(), "Dormant ID should be retired after window");

        TestChromosome bAgain = makeChromosome(setOf(3, 4));
        Map<Integer, List<TestChromosome>> gen3 = assigner.groupBySpecies(
                Arrays.asList(a, bAgain));
        int newIdForB = lookupSpecies(gen3, bAgain);
        assertNotEquals(idB, newIdForB, "Retired ID must not be reused");
    }

    @Test
    void multipleGroupBySpeciesCallsInOneGenerationDoNotAffectDormancy() {
        TestChromosome a = makeChromosome(setOf(1, 2));
        TestChromosome b = makeChromosome(setOf(3, 4));
        StableSpeciesAssigner assigner = new StableSpeciesAssigner(
                new JaccardSpeciesDistance(SpeciationMetric.TRACE_BRANCH_JACCARD, 0.7), 0.4, 5);

        Map<Integer, List<TestChromosome>> first = assigner.groupBySpecies(Arrays.asList(a, b));
        int idA = lookupSpecies(first, a);
        int idB = lookupSpecies(first, b);

        // Same generation, second call with only A — must not retire B.
        assigner.groupBySpecies(Collections.singletonList(a));
        // Third call with both back — IDs must persist.
        Map<Integer, List<TestChromosome>> third = assigner.groupBySpecies(Arrays.asList(a, b));
        assertEquals(idA, lookupSpecies(third, a));
        assertEquals(idB, lookupSpecies(third, b));

        assertEquals(2, assigner.registrySize());
    }

    private static int lookupSpecies(Map<Integer, List<TestChromosome>> map, TestChromosome tc) {
        IdentityHashMap<TestChromosome, Integer> idx = new IdentityHashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> e : map.entrySet()) {
            for (TestChromosome m : e.getValue()) {
                idx.put(m, e.getKey());
            }
        }
        Integer id = idx.get(tc);
        assertNotNull(id, "Chromosome must be present in species map");
        return id;
    }

    private static Set<Integer> setOf(Integer... ints) {
        return new HashSet<>(Arrays.asList(ints));
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
