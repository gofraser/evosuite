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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.InjectionSource;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AbstractMOSA#applyLineageElitism}: speciation-free incubation
 * that re-inserts the best member of a dropped injected lineage, never evicts
 * front-0 (rank 0) members, honors the population share cap with
 * oldest-lineages-lose-first semantics, and is inert when disabled.
 */
class LineageElitismTest {

    private int savedGenerations;
    private double savedMaxFraction;

    @BeforeEach
    void saveProperties() {
        savedGenerations = Properties.LLM_LINEAGE_ELITISM_GENERATIONS;
        savedMaxFraction = Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = savedGenerations;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = savedMaxFraction;
    }

    @Test
    void reinsertsDroppedLineageMemberEvictingWorstUnprotected() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 2;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 0.5;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(0);

        TestChromosome front0 = ranked(0, 1.0);
        TestChromosome filler = ranked(2, 0.1);
        TestChromosome lineageMember = lineageMember(5L, 0, 1, 0.5);

        mosa.seed(front0, filler);
        mosa.runElitism(union(front0, filler, lineageMember));

        assertEquals(2, mosa.pop().size(), "re-insertion replaces, never grows");
        assertTrue(containsIdentity(mosa.pop(), lineageMember),
                "the dropped lineage's best member must be re-inserted");
        assertTrue(containsIdentity(mosa.pop(), front0), "front-0 member must survive");
        assertFalse(containsIdentity(mosa.pop(), filler),
                "the worst unprotected non-front-0 member is evicted");
    }

    @Test
    void neverEvictsFront0Members() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 2;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 1.0;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(0);

        TestChromosome front0a = ranked(0, 1.0);
        TestChromosome front0b = ranked(0, 0.5);
        TestChromosome lineageMember = lineageMember(5L, 0, 1, 0.5);

        mosa.seed(front0a, front0b);
        mosa.runElitism(union(front0a, front0b, lineageMember));

        assertTrue(containsIdentity(mosa.pop(), front0a));
        assertTrue(containsIdentity(mosa.pop(), front0b));
        assertFalse(containsIdentity(mosa.pop(), lineageMember),
                "with only front-0 members present there is nothing evictable");
    }

    @Test
    void shareCapLimitsProtectionYoungestLineagesFirst() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 3;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 0.25;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(1);

        TestChromosome front0 = ranked(0, 1.0);
        TestChromosome fillerA = ranked(2, 0.3);
        TestChromosome fillerB = ranked(2, 0.2);
        TestChromosome fillerC = ranked(2, 0.1);
        // Two dropped lineages; cap allows floor(0.25 * 4) = 1 protected slot.
        TestChromosome olderLineage = lineageMember(1L, 0, 1, 0.5);
        TestChromosome youngerLineage = lineageMember(2L, 1, 1, 0.5);

        mosa.seed(front0, fillerA, fillerB, fillerC);
        mosa.runElitism(union(front0, fillerA, fillerB, fillerC,
                olderLineage, youngerLineage));

        assertTrue(containsIdentity(mosa.pop(), youngerLineage),
                "the youngest lineage gets the single protected slot");
        assertFalse(containsIdentity(mosa.pop(), olderLineage),
                "oldest lineages lose protection first at the cap");
        assertEquals(4, mosa.pop().size());
    }

    @Test
    void lineagesAlreadyRepresentedAreNotReinserted() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 2;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 1.0;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(0);

        TestChromosome survivor = lineageMember(5L, 0, 1, 0.9);
        TestChromosome filler = ranked(2, 0.1);
        TestChromosome betterTwin = lineageMember(5L, 0, 1, 0.5);

        mosa.seed(survivor, filler);
        mosa.runElitism(union(survivor, filler, betterTwin));

        assertTrue(containsIdentity(mosa.pop(), filler),
                "a represented lineage must not trigger eviction for a second member");
        assertFalse(containsIdentity(mosa.pop(), betterTwin));
    }

    @Test
    void expiredLineagesAreNotProtected() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 2;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 1.0;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(5);

        TestChromosome front0 = ranked(0, 1.0);
        TestChromosome filler = ranked(2, 0.1);
        TestChromosome expired = lineageMember(5L, 0, 1, 0.5); // age 5 > G=2

        mosa.seed(front0, filler);
        mosa.runElitism(union(front0, filler, expired));

        assertTrue(containsIdentity(mosa.pop(), filler), "population must be untouched");
        assertFalse(containsIdentity(mosa.pop(), expired));
    }

    @Test
    void disabledAtZeroGenerationsLeavesPopulationUntouched() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 0;
        ElitismHarnessMOSA mosa = new ElitismHarnessMOSA();
        mosa.setIteration(0);

        TestChromosome front0 = ranked(0, 1.0);
        TestChromosome filler = ranked(2, 0.1);
        TestChromosome lineageMember = lineageMember(5L, 0, 1, 0.5);

        mosa.seed(front0, filler);
        List<TestChromosome> before = new ArrayList<>(mosa.pop());
        mosa.runElitism(union(front0, filler, lineageMember));

        assertEquals(before, mosa.pop(), "G=0 disables lineage elitism entirely");
    }

    // ---------------------------------------------------------------- helpers

    private static TestChromosome ranked(int rank, double distance) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        tc.setRank(rank);
        tc.setDistance(distance);
        return tc;
    }

    private static TestChromosome lineageMember(long lineageId, int injectedAt,
                                                int rank, double distance) {
        TestChromosome tc = ranked(rank, distance);
        tc.setInjectionSource(InjectionSource.LLM_STAGNATION);
        tc.setInjectionLineageId(lineageId);
        tc.setInjectionGeneration(injectedAt);
        return tc;
    }

    private static List<TestChromosome> union(TestChromosome... members) {
        return new ArrayList<>(Arrays.asList(members));
    }

    private static boolean containsIdentity(List<TestChromosome> list, TestChromosome target) {
        for (TestChromosome tc : list) {
            if (tc == target) {
                return true;
            }
        }
        return false;
    }

    private static class ElitismHarnessMOSA extends MOSA {
        private static final long serialVersionUID = 1L;

        ElitismHarnessMOSA() {
            super(TestChromosome::new);
        }

        void setIteration(int iteration) {
            this.currentIteration = iteration;
        }

        void seed(TestChromosome... members) {
            population.clear();
            population.addAll(Arrays.asList(members));
        }

        List<TestChromosome> pop() {
            return this.population;
        }

        void runElitism(List<TestChromosome> union) {
            applyLineageElitism(union);
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // no-op
        }
    }
}
