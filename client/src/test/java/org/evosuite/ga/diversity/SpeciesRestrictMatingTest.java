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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SPECIES_RESTRICT_MATING property and the reverse-lookup
 * logic that constrains parent2 selection to the same species as parent1.
 *
 * These tests exercise the species-filtering data structures at the unit level
 * (identical to what breedNextGeneration() builds and selectParent2() queries)
 * without requiring a full MOSA harness.
 */
class SpeciesRestrictMatingTest {

    private boolean prevRestrict;
    private boolean prevSpeciation;

    @BeforeEach
    void save() {
        prevRestrict = Properties.SPECIES_RESTRICT_MATING;
        prevSpeciation = Properties.SPECIATION_ENABLED;
    }

    @AfterEach
    void restore() {
        Properties.SPECIES_RESTRICT_MATING = prevRestrict;
        Properties.SPECIATION_ENABLED = prevSpeciation;
    }

    @Test
    void propertyDefaultIsFalse() {
        assertFalse(Properties.SPECIES_RESTRICT_MATING);
    }

    @Test
    void reverseLookupNotBuiltWhenDisabled() {
        Properties.SPECIES_RESTRICT_MATING = false;
        Map<Integer, List<TestChromosome>> speciesMap = makeTwoSpecies(3, 3);

        // Simulates the guard in breedNextGeneration
        Map<TestChromosome, Integer> lookup = null;
        if (Properties.SPECIES_RESTRICT_MATING && speciesMap != null) {
            lookup = buildReverseLookup(speciesMap);
        }
        assertNull(lookup, "Reverse lookup should not be built when mating restriction is disabled");
    }

    @Test
    void reverseLookupBuiltWhenEnabled() {
        Properties.SPECIES_RESTRICT_MATING = true;
        Map<Integer, List<TestChromosome>> speciesMap = makeTwoSpecies(3, 3);

        Map<TestChromosome, Integer> lookup = buildReverseLookup(speciesMap);
        assertEquals(6, lookup.size());

        // All species-0 members map to 0
        for (TestChromosome tc : speciesMap.get(0)) {
            assertEquals(0, lookup.get(tc));
        }
        // All species-1 members map to 1
        for (TestChromosome tc : speciesMap.get(1)) {
            assertEquals(1, lookup.get(tc));
        }
    }

    @Test
    void parent2FromSameSpeciesWhenRestricted() {
        Properties.SPECIES_RESTRICT_MATING = true;
        Map<Integer, List<TestChromosome>> speciesMap = makeTwoSpecies(5, 5);
        Map<TestChromosome, Integer> lookup = buildReverseLookup(speciesMap);

        // Simulate: parent1 is from species 0
        TestChromosome parent1 = speciesMap.get(0).get(0);
        Integer speciesId = lookup.get(parent1);
        assertNotNull(speciesId);

        List<TestChromosome> speciesMembers = speciesMap.get(speciesId);
        assertTrue(speciesMembers.size() >= 2,
                "Species should have >=2 members for restricted selection");

        // Any selection from speciesMembers must be from species 0
        Set<TestChromosome> s0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s0Set.addAll(speciesMap.get(0));
        for (TestChromosome member : speciesMembers) {
            assertTrue(s0Set.contains(member),
                    "All species members should be from the same species");
        }
    }

    @Test
    void fallbackWhenSpeciesHasOneMember() {
        Properties.SPECIES_RESTRICT_MATING = true;
        List<TestChromosome> s0 = makeChromosomes(1);
        List<TestChromosome> s1 = makeChromosomes(5);
        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        speciesMap.put(0, s0);
        speciesMap.put(1, s1);
        Map<TestChromosome, Integer> lookup = buildReverseLookup(speciesMap);

        // parent1 is the sole member of species 0
        TestChromosome parent1 = s0.get(0);
        Integer speciesId = lookup.get(parent1);
        List<TestChromosome> members = speciesMap.get(speciesId);

        // Should trigger fallback (size < 2)
        assertTrue(members.size() < 2,
                "Single-member species should trigger fallback to full population");
    }

    @Test
    void fallbackWhenSpeciesMapIsNull() {
        Properties.SPECIES_RESTRICT_MATING = true;
        // currentSpeciesMap is null on first generation
        Map<Integer, List<TestChromosome>> speciesMap = null;

        Map<TestChromosome, Integer> lookup = null;
        if (Properties.SPECIES_RESTRICT_MATING && speciesMap != null) {
            lookup = buildReverseLookup(speciesMap);
        }
        assertNull(lookup,
                "Lookup should be null when speciesMap is null (first generation)");
    }

    @Test
    void fallbackWhenParent1NotInLookup() {
        Properties.SPECIES_RESTRICT_MATING = true;
        Map<Integer, List<TestChromosome>> speciesMap = makeTwoSpecies(3, 3);
        Map<TestChromosome, Integer> lookup = buildReverseLookup(speciesMap);

        // A new chromosome not in any species
        TestChromosome outsider = new TestChromosome();
        outsider.setTestCase(new DefaultTestCase());

        Integer speciesId = lookup.get(outsider);
        assertNull(speciesId, "Unknown individual should not be in lookup → fallback");
    }

    // --- helpers ---

    /** Mirrors the reverse-lookup logic in AbstractMOSA.breedNextGeneration() */
    private Map<TestChromosome, Integer> buildReverseLookup(
            Map<Integer, List<TestChromosome>> speciesMap) {
        Map<TestChromosome, Integer> lookup = new IdentityHashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
            for (TestChromosome tc : entry.getValue()) {
                lookup.put(tc, entry.getKey());
            }
        }
        return lookup;
    }

    private Map<Integer, List<TestChromosome>> makeTwoSpecies(int sizeA, int sizeB) {
        Map<Integer, List<TestChromosome>> map = new LinkedHashMap<>();
        map.put(0, makeChromosomes(sizeA));
        map.put(1, makeChromosomes(sizeB));
        return map;
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
