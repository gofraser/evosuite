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
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.diversity.StableSpeciesAssigner;
import org.evosuite.ga.operators.ranking.RankingFunction;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the species-protection path in AbstractMOSA.
 */
class SpeciesProtectionIntegrationTest {

    private boolean savedSpeciationEnabled;
    private double savedSpeciationThreshold;
    private double savedSpeciesSurvivalCap;
    private int savedSpeciesMinSurvivors;
    private int savedNewbornProtection;
    private boolean savedSpeciesTimelineEnabled;
    private boolean savedLargestShareTimelineEnabled;

    @BeforeEach
    void saveProperties() {
        savedSpeciationEnabled = Properties.SPECIATION_ENABLED;
        savedSpeciationThreshold = Properties.SPECIATION_THRESHOLD;
        savedSpeciesSurvivalCap = Properties.SPECIES_SURVIVAL_CAP;
        savedSpeciesMinSurvivors = Properties.SPECIES_MIN_SURVIVORS_PER_SPECIES;
        savedNewbornProtection = Properties.SPECIES_NEWBORN_PROTECTION_GENERATIONS;
        savedSpeciesTimelineEnabled = Properties.SPECIES_TIMELINE_ENABLED;
        savedLargestShareTimelineEnabled = Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED;
    }

    @AfterEach
    void restoreProperties() {
        Properties.SPECIATION_ENABLED = savedSpeciationEnabled;
        Properties.SPECIATION_THRESHOLD = savedSpeciationThreshold;
        Properties.SPECIES_SURVIVAL_CAP = savedSpeciesSurvivalCap;
        Properties.SPECIES_MIN_SURVIVORS_PER_SPECIES = savedSpeciesMinSurvivors;
        Properties.SPECIES_NEWBORN_PROTECTION_GENERATIONS = savedNewbornProtection;
        Properties.SPECIES_TIMELINE_ENABLED = savedSpeciesTimelineEnabled;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = savedLargestShareTimelineEnabled;
    }

    @Test
    void newbornProtectionKeepsNewSpeciesSurvivorAcrossGenerations() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIATION_THRESHOLD = 0.3;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_MIN_SURVIVORS_PER_SPECIES = 0;
        Properties.SPECIES_NEWBORN_PROTECTION_GENERATIONS = 3;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        ProtectionHarnessMOSA mosa = new ProtectionHarnessMOSA();

        TestChromosome old0a = chromosomeWithTrueBranches(setOf(1));
        TestChromosome old0b = chromosomeWithTrueBranches(setOf(1));
        mosa.runSurvivalStep(Collections.singletonList(old0a), Arrays.asList(old0a, old0b), 2);
        mosa.advanceSpeciesGeneration();

        TestChromosome old1a = chromosomeWithTrueBranches(setOf(1));
        TestChromosome old1b = chromosomeWithTrueBranches(setOf(1));
        TestChromosome newcomer = chromosomeWithTrueBranches(setOf(2));
        mosa.runSurvivalStep(Collections.singletonList(old1a), Arrays.asList(old1a, old1b, newcomer), 2);

        assertTrue(containsIdentity(mosa.getPopulation(), newcomer),
                "Newborn species member should survive protected admission");
        assertFalse(containsIdentity(mosa.getPopulation(), old1b),
                "Incumbent non-front candidate should lose the slot to newborn protection");
    }

    @Test
    void withoutNewbornProtectionIncumbentKeepsSlot() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIATION_THRESHOLD = 0.3;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_MIN_SURVIVORS_PER_SPECIES = 0;
        Properties.SPECIES_NEWBORN_PROTECTION_GENERATIONS = 0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        ProtectionHarnessMOSA mosa = new ProtectionHarnessMOSA();

        TestChromosome old0a = chromosomeWithTrueBranches(setOf(1));
        TestChromosome old0b = chromosomeWithTrueBranches(setOf(1));
        mosa.runSurvivalStep(Collections.singletonList(old0a), Arrays.asList(old0a, old0b), 2);
        mosa.advanceSpeciesGeneration();

        TestChromosome old1a = chromosomeWithTrueBranches(setOf(1));
        TestChromosome old1b = chromosomeWithTrueBranches(setOf(1));
        TestChromosome newcomer = chromosomeWithTrueBranches(setOf(2));
        mosa.runSurvivalStep(Collections.singletonList(old1a), Arrays.asList(old1a, old1b, newcomer), 2);

        assertFalse(containsIdentity(mosa.getPopulation(), newcomer),
                "Without newborn protection, newcomer should not displace better-ranked incumbent");
        assertTrue(containsIdentity(mosa.getPopulation(), old1b));
    }

    private static Set<Integer> setOf(Integer... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static TestChromosome chromosomeWithTrueBranches(Set<Integer> trueBranches) {
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

    private static boolean containsIdentity(List<TestChromosome> pop, TestChromosome target) {
        for (TestChromosome tc : pop) {
            if (tc == target) {
                return true;
            }
        }
        return false;
    }

    private static class ProtectionHarnessMOSA extends MOSA {
        private static final long serialVersionUID = 1L;

        ProtectionHarnessMOSA() {
            super(TestChromosome::new);
        }

        void runSurvivalStep(List<TestChromosome> front0,
                             List<TestChromosome> rankedCandidates,
                             int targetSize) {
            List<TestChromosome> front1 = new ArrayList<>();
            Set<TestChromosome> front0Set = Collections.newSetFromMap(new IdentityHashMap<>());
            front0Set.addAll(front0);
            for (TestChromosome tc : rankedCandidates) {
                if (!front0Set.contains(tc)) {
                    front1.add(tc);
                }
            }
            this.setRankingFunction(new FixedRankingFunction(front0, front1));
            applySpeciationSurvival(rankedCandidates, targetSize);
        }

        void advanceSpeciesGeneration() {
            if (this.speciesAssigner instanceof StableSpeciesAssigner) {
                ((StableSpeciesAssigner) this.speciesAssigner).advanceGeneration();
            }
            this.currentIteration++;
        }

        @Override
        protected List<TestChromosome> breedNextGeneration() {
            return Collections.emptyList();
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // no-op
        }
    }

    private static class FixedRankingFunction implements RankingFunction<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private final List<TestChromosome> front0;
        private final List<TestChromosome> front1;

        FixedRankingFunction(List<TestChromosome> front0, List<TestChromosome> front1) {
            this.front0 = front0;
            this.front1 = front1;
        }

        @Override
        public void computeRankingAssignment(List<TestChromosome> solutions,
                                             Set<? extends FitnessFunction<TestChromosome>> uncoveredGoals) {
            // no-op
        }

        @Override
        public List<TestChromosome> getSubfront(int rank) {
            if (rank == 0) {
                return new ArrayList<>(front0);
            }
            if (rank == 1) {
                return new ArrayList<>(front1);
            }
            return Collections.emptyList();
        }

        @Override
        public int getNumberOfSubfronts() {
            return 2;
        }
    }

}
