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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DefaultSpeciesPolicy survival caps and parent balancing.
 */
class DefaultSpeciesPolicyTest {

    private DefaultSpeciesPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultSpeciesPolicy();
    }

    @Test
    void survivalCapNoCapWhenCapIsOne() {
        List<TestChromosome> survivors = makeChromosomes(6);
        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, survivors.subList(0, 4));
        speciesMap.put(1, survivors.subList(4, 6));

        List<TestChromosome> result = policy.applySurvivalCaps(
                survivors, speciesMap, 6, 1.0);

        assertEquals(6, result.size());
        // All original survivors kept in order
        assertEquals(survivors, result);
    }

    @Test
    void survivalCapEnforcesPerSpeciesLimit() {
        // 5 from species 0, 5 from species 1, target=6, cap=0.5 → max 3 per species
        // Strict cap: first pass admits 3 from each. Second pass should also respect cap.
        List<TestChromosome> s0 = makeChromosomes(5);
        List<TestChromosome> s1 = makeChromosomes(5);
        List<TestChromosome> all = new ArrayList<>();
        all.addAll(s0);
        all.addAll(s1);

        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, s0);
        speciesMap.put(1, s1);

        List<TestChromosome> result = policy.applySurvivalCaps(all, speciesMap, 6, 0.5);

        assertEquals(6, result.size());
        // Use identity to count species members
        Set<TestChromosome> s0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s0Set.addAll(s0);
        Set<TestChromosome> s1Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s1Set.addAll(s1);
        int spec0Count = 0, spec1Count = 0;
        for (TestChromosome tc : result) {
            if (s0Set.contains(tc)) spec0Count++;
            if (s1Set.contains(tc)) spec1Count++;
        }
        // Cap = ceil(6 * 0.5) = 3; both species have enough members to fill
        assertEquals(3, spec0Count, "Species 0 should have exactly 3 (strict cap)");
        assertEquals(3, spec1Count, "Species 1 should have exactly 3 (strict cap)");
    }

    @Test
    void survivalCapWithStrictCapLimitsCorrectly() {
        // 5 from species 0, 5 from species 1, target=6, cap=0.5 → max 3 per species
        List<TestChromosome> s0 = makeChromosomes(5);
        List<TestChromosome> s1 = makeChromosomes(5);
        List<TestChromosome> all = new ArrayList<>();
        all.addAll(s0);
        all.addAll(s1);

        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, s0);
        speciesMap.put(1, s1);

        List<TestChromosome> result = policy.applySurvivalCaps(all, speciesMap, 6, 0.5);

        assertEquals(6, result.size());
        // Use identity sets to count
        Set<TestChromosome> s0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s0Set.addAll(s0);
        Set<TestChromosome> s1Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s1Set.addAll(s1);
        int spec0InResult = 0, spec1InResult = 0;
        for (TestChromosome tc : result) {
            if (s0Set.contains(tc)) spec0InResult++;
            if (s1Set.contains(tc)) spec1InResult++;
        }
        assertEquals(3, spec0InResult, "Species 0 should have exactly 3 survivors");
        assertEquals(3, spec1InResult, "Species 1 should have exactly 3 survivors");
    }

    @Test
    void survivalCapRespectsTargetSize() {
        List<TestChromosome> all = makeChromosomes(10);
        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, all);

        List<TestChromosome> result = policy.applySurvivalCaps(all, speciesMap, 5, 1.0);
        assertEquals(5, result.size());
    }

    @Test
    void survivalCapEmptySpeciesMap() {
        List<TestChromosome> all = makeChromosomes(4);
        Map<Integer, List<TestChromosome>> speciesMap = Collections.emptyMap();

        List<TestChromosome> result = policy.applySurvivalCaps(all, speciesMap, 4, 0.5);
        assertEquals(4, result.size());
    }

    @Test
    void balanceParentPoolDisabledReturnsSafeCopy() {
        boolean prev = Properties.SPECIES_BALANCE_PARENT_SELECTION;
        try {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
            List<TestChromosome> pop = makeChromosomes(6);
            Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
            speciesMap.put(0, pop.subList(0, 3));
            speciesMap.put(1, pop.subList(3, 6));

            List<TestChromosome> result = policy.balanceParentPool(pop, speciesMap);
            // Must be a safe copy (not same reference) to prevent population wipe
            assertNotSame(pop, result);
            assertEquals(pop, result);
        } finally {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = prev;
        }
    }

    @Test
    void balanceParentPoolRoundRobins() {
        boolean prev = Properties.SPECIES_BALANCE_PARENT_SELECTION;
        try {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = true;
            List<TestChromosome> s0 = makeChromosomes(3);
            List<TestChromosome> s1 = makeChromosomes(3);
            List<TestChromosome> all = new ArrayList<>();
            all.addAll(s0);
            all.addAll(s1);

            Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
            speciesMap.put(0, s0);
            speciesMap.put(1, s1);

            List<TestChromosome> result = policy.balanceParentPool(all, speciesMap);

            assertEquals(6, result.size());
            // Round-robin: s0[0], s1[0], s0[1], s1[1], s0[2], s1[2]
            assertSame(s0.get(0), result.get(0));
            assertSame(s1.get(0), result.get(1));
            assertSame(s0.get(1), result.get(2));
            assertSame(s1.get(1), result.get(3));
        } finally {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = prev;
        }
    }

    @Test
    void balanceParentPoolSingleSpeciesReturnsSafeCopy() {
        boolean prev = Properties.SPECIES_BALANCE_PARENT_SELECTION;
        try {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = true;
            List<TestChromosome> pop = makeChromosomes(4);
            Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
            speciesMap.put(0, pop);

            List<TestChromosome> result = policy.balanceParentPool(pop, speciesMap);
            // Must be a safe copy (not same reference)
            assertNotSame(pop, result);
            assertEquals(pop.size(), result.size());
        } finally {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = prev;
        }
    }

    @Test
    void survivalCapStrictEnforcementWithOverflow() {
        // 8 from species 0, 2 from species 1, target=8, cap=0.5 → max 4 per species
        // First pass: 4 from s0, 2 from s1 = 6 accepted, 4 s0 overflow
        // Second pass: s0 already at cap, overflow skipped; fallback fills 2 from overflow
        List<TestChromosome> s0 = makeChromosomes(8);
        List<TestChromosome> s1 = makeChromosomes(2);
        List<TestChromosome> all = new ArrayList<>();
        all.addAll(s0);
        all.addAll(s1);

        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, s0);
        speciesMap.put(1, s1);

        List<TestChromosome> result = policy.applySurvivalCaps(all, speciesMap, 8, 0.5);

        assertEquals(8, result.size());
        // Count species 0 members - should be 4 from first pass + 2 from fallback = 6
        // (not 8 as old code would allow)
        Set<TestChromosome> s0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s0Set.addAll(s0);
        int spec0Count = 0;
        for (TestChromosome tc : result) {
            if (s0Set.contains(tc)) spec0Count++;
        }
        // Species 1 only has 2 members, so can't fill to cap=4.
        // Second pass allows s0 overflow up to cap (already at 4), so they go to fallback.
        // Fallback fills remaining 2 slots from s0 overflow.
        assertEquals(6, spec0Count, "Species 0: 4 (cap) + 2 (fallback for unfilled slots)");
    }

    @Test
    void balanceParentPoolClearAddAllSafe() {
        // Simulates the MOSA caller pattern: population.clear(); population.addAll(balanced)
        // This must NOT wipe the population when balancing is disabled.
        boolean prev = Properties.SPECIES_BALANCE_PARENT_SELECTION;
        try {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
            List<TestChromosome> population = new ArrayList<>(makeChromosomes(6));
            int originalSize = population.size();

            Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
            speciesMap.put(0, new ArrayList<>(population.subList(0, 3)));
            speciesMap.put(1, new ArrayList<>(population.subList(3, 6)));

            List<TestChromosome> balanced = policy.balanceParentPool(population, speciesMap);
            // Simulate MOSA caller pattern
            population.clear();
            population.addAll(balanced);

            assertEquals(originalSize, population.size(),
                    "Population must not be wiped by clear+addAll pattern");
        } finally {
            Properties.SPECIES_BALANCE_PARENT_SELECTION = prev;
        }
    }

    @Test
    void survivalCapsOnNonFront0OnlyPreservesPreferenceFront() {
        // Simulates the MOSA caller pattern: front-0 is passed separately,
        // caps apply only to non-front-0 candidates.
        // 3 front-0 from species 0, 3 non-front-0 from species 0, 4 non-front-0 from species 1
        // target=6 (after front-0), remaining=3, cap=0.5 → max 2 per species
        List<TestChromosome> front0 = makeChromosomes(3);   // species 0
        List<TestChromosome> rest0 = makeChromosomes(3);    // species 0
        List<TestChromosome> rest1 = makeChromosomes(4);    // species 1

        List<TestChromosome> nonFront0 = new ArrayList<>();
        nonFront0.addAll(rest0);
        nonFront0.addAll(rest1);

        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        List<TestChromosome> allS0 = new ArrayList<>(front0);
        allS0.addAll(rest0);
        speciesMap.put(0, allS0);
        speciesMap.put(1, rest1);

        int remainingSlots = 3;
        List<TestChromosome> capped = policy.applySurvivalCaps(
                nonFront0, speciesMap, remainingSlots, 0.5);

        // Front-0 (3 members) + capped (3 members) = 6 total population
        List<TestChromosome> finalPop = new ArrayList<>(front0);
        finalPop.addAll(capped);
        assertEquals(6, finalPop.size());

        // All 3 front-0 members are preserved (they weren't subject to caps)
        Set<TestChromosome> f0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        f0Set.addAll(front0);
        for (TestChromosome tc : front0) {
            assertTrue(finalPop.contains(tc), "Front-0 member must be in final population");
        }
    }

    private List<TestChromosome> makeChromosomes(int n) {
        List<TestChromosome> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestChromosome tc = new TestChromosome();
            tc.setTestCase(new DefaultTestCase());
            list.add(tc);
        }
        return list;
    }
}
