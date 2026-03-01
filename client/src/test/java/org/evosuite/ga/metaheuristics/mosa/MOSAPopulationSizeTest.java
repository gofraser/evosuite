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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.operators.ranking.RankingFunction;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for MOSA population-size semantics when front-0 interacts
 * with speciation.  Verifies the explicit policy:
 * <ul>
 *   <li>Front-0 is always fully preserved.</li>
 *   <li>When front-0 exceeds base target, effective target grows to front-0 size.</li>
 *   <li>When front-0 &le; base target, remaining slots are filled from non-front-0.</li>
 *   <li>Non-speciation path preserves prior behavior.</li>
 * </ul>
 */
class MOSAPopulationSizeTest {

    private boolean savedSpeciationEnabled;
    private boolean savedBalanceParentSelection;
    private double savedSurvivalCap;
    private boolean savedTimelineEnabled;
    private boolean savedLargestShareTimeline;

    @BeforeEach
    void saveProperties() {
        savedSpeciationEnabled = Properties.SPECIATION_ENABLED;
        savedBalanceParentSelection = Properties.SPECIES_BALANCE_PARENT_SELECTION;
        savedSurvivalCap = Properties.SPECIES_SURVIVAL_CAP;
        savedTimelineEnabled = Properties.SPECIES_TIMELINE_ENABLED;
        savedLargestShareTimeline = Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED;
    }

    @AfterEach
    void restoreProperties() {
        Properties.SPECIATION_ENABLED = savedSpeciationEnabled;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = savedBalanceParentSelection;
        Properties.SPECIES_SURVIVAL_CAP = savedSurvivalCap;
        Properties.SPECIES_TIMELINE_ENABLED = savedTimelineEnabled;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = savedLargestShareTimeline;
    }

    /**
     * When front-0 is larger than the base population target, the effective
     * target grows to front-0 size and all front-0 members are preserved.
     */
    @Test
    void front0LargerThanTargetPreservesAllFront0Members() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        int basePopSize = 4;
        int front0Size = 6; // larger than base population

        List<TestChromosome> front0 = makeChromosomes(front0Size);
        List<TestChromosome> front1 = makeChromosomes(3);

        PopSizeTestMOSA mosa = new PopSizeTestMOSA(
                () -> new TestChromosome(), front0, front1, basePopSize);

        mosa.evolve();

        // All front-0 members must be in the population
        Set<TestChromosome> popSet = Collections.newSetFromMap(new IdentityHashMap<>());
        popSet.addAll(mosa.getPopulation());
        for (TestChromosome tc : front0) {
            assertTrue(popSet.contains(tc),
                    "Every front-0 member must survive when front-0 > base target");
        }
        // Population = front0Size (effective target = front0Size, remainingSlots = 0)
        assertEquals(front0Size, mosa.getPopulation().size(),
                "Population should equal front-0 size when front-0 > base target");
    }

    /**
     * When front-0 fits within the base target, remaining slots are filled
     * from non-front-0 candidates and population matches base target.
     */
    @Test
    void front0SmallerThanTargetFillsRemainingSlots() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        int basePopSize = 6;
        int front0Size = 2;

        List<TestChromosome> front0 = makeChromosomes(front0Size);
        List<TestChromosome> front1 = makeChromosomes(8);

        PopSizeTestMOSA mosa = new PopSizeTestMOSA(
                () -> new TestChromosome(), front0, front1, basePopSize);

        mosa.evolve();

        // All front-0 members must be preserved
        Set<TestChromosome> popSet = Collections.newSetFromMap(new IdentityHashMap<>());
        popSet.addAll(mosa.getPopulation());
        for (TestChromosome tc : front0) {
            assertTrue(popSet.contains(tc),
                    "Front-0 members must be preserved when front-0 <= base target");
        }
        // Population should be basePopSize (front0 + filled from non-front0)
        assertEquals(basePopSize, mosa.getPopulation().size(),
                "Population should equal base target when front-0 <= base target");
    }

    /**
     * When front-0 exactly equals the base target, population is exactly that
     * size with no non-front-0 members.
     */
    @Test
    void front0ExactlyEqualsTargetProducesExactSize() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        int size = 5;
        List<TestChromosome> front0 = makeChromosomes(size);
        List<TestChromosome> front1 = makeChromosomes(3);

        PopSizeTestMOSA mosa = new PopSizeTestMOSA(
                () -> new TestChromosome(), front0, front1, size);

        mosa.evolve();

        assertEquals(size, mosa.getPopulation().size(),
                "Population should equal base target when front-0 == base target");
    }

    /**
     * Behavior is deterministic: repeated evolve() calls produce the same result.
     */
    @Test
    void populationSizeIsDeterministic() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        int basePopSize = 4;
        List<TestChromosome> front0 = makeChromosomes(7);
        List<TestChromosome> front1 = makeChromosomes(5);

        int[] sizes = new int[3];
        for (int i = 0; i < sizes.length; i++) {
            PopSizeTestMOSA mosa = new PopSizeTestMOSA(
                    () -> new TestChromosome(), front0, front1, basePopSize);
            mosa.evolve();
            sizes[i] = mosa.getPopulation().size();
        }

        assertEquals(sizes[0], sizes[1], "Population size must be deterministic across runs");
        assertEquals(sizes[1], sizes[2], "Population size must be deterministic across runs");
    }

    /**
     * Non-speciation path: population is bounded to the ranked candidates
     * (base target) with no speciation logic applied.
     */
    @Test
    void nonSpeciationPathPreservesPriorBehavior() {
        Properties.SPECIATION_ENABLED = false;

        int basePopSize = 4;
        List<TestChromosome> front0 = makeChromosomes(6);
        List<TestChromosome> front1 = makeChromosomes(3);

        // Non-speciation MOSA: candidateLimit = baseTarget, so rankedCandidates
        // is bounded to basePopSize. No speciation block runs.
        PopSizeTestMOSA mosa = new PopSizeTestMOSA(
                () -> new TestChromosome(), front0, front1, basePopSize);

        mosa.evolve();

        // Non-speciation path: population = rankedCandidates (bounded to candidateLimit)
        assertTrue(mosa.getPopulation().size() <= basePopSize,
                "Non-speciation path should not exceed base target");
    }

    /**
     * Even if speciation processing fails mid-way, fallback must still preserve
     * all front-0 members.
     */
    @Test
    void speciationFailureFallbackStillPreservesFront0() {
        Properties.SPECIATION_ENABLED = true;
        Properties.SPECIES_BALANCE_PARENT_SELECTION = false;
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = true;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

        int basePopSize = 4;
        int front0Size = 6;

        List<TestChromosome> front0 = makeChromosomes(front0Size);
        List<TestChromosome> front1 = makeChromosomes(4);

        PopSizeTestMOSA mosa = new ThrowingTimelineMOSA(
                () -> new TestChromosome(), front0, front1, basePopSize);

        mosa.evolve();

        Set<TestChromosome> popSet = Collections.newSetFromMap(new IdentityHashMap<>());
        popSet.addAll(mosa.getPopulation());
        for (TestChromosome tc : front0) {
            assertTrue(popSet.contains(tc),
                    "Fallback path must keep all front-0 members");
        }
        assertEquals(front0Size, mosa.getPopulation().size(),
                "Fallback path should still use effective target based on front-0");
    }

    // ------- helpers -------

    private static List<TestChromosome> makeChromosomes(int n) {
        List<TestChromosome> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestChromosome tc = new TestChromosome();
            tc.setTestCase(new DefaultTestCase());
            list.add(tc);
        }
        return list;
    }

    /**
     * MOSA subclass with a controlled ranking function for population-size testing.
     * Stubs out breeding, fitness, and uncovered goals so that only the
     * population-assembly logic is exercised.
     */
    private static class PopSizeTestMOSA extends MOSA {
        private static final long serialVersionUID = 1L;
        private final Set<TestFitnessFunction> goals;

        PopSizeTestMOSA(ChromosomeFactory<TestChromosome> factory,
                         List<TestChromosome> front0,
                         List<TestChromosome> front1,
                         int initialPopSize) {
            super(factory);
            // Seed the population to establish base target
            this.population.clear();
            for (int i = 0; i < initialPopSize; i++) {
                TestChromosome tc = new TestChromosome();
                tc.setTestCase(new DefaultTestCase());
                this.population.add(tc);
            }
            // Install a controlled ranking function
            this.setRankingFunction(new FixedRankingFunction(front0, front1));
            // Dummy uncovered goal (returned via overridden getUncoveredGoals)
            this.goals = new LinkedHashSet<>();
            this.goals.add(new DummyTestFitnessFunction());
        }

        @Override
        protected Set<TestFitnessFunction> getUncoveredGoals() {
            return goals;
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

    /**
     * Test variant that forces an exception inside the speciation block.
     */
    private static class ThrowingTimelineMOSA extends PopSizeTestMOSA {
        private static final long serialVersionUID = 1L;

        ThrowingTimelineMOSA(ChromosomeFactory<TestChromosome> factory,
                             List<TestChromosome> front0,
                             List<TestChromosome> front1,
                             int initialPopSize) {
            super(factory, front0, front1, initialPopSize);
        }

        @Override
        protected void emitSpeciesTimeline(Map<Integer, List<TestChromosome>> speciesMap) {
            throw new RuntimeException("forced speciation failure");
        }
    }

    /**
     * Ranking function that returns fixed front-0 and front-1 regardless of input.
     */
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
            // no-op: fronts are pre-determined
        }

        @Override
        public List<TestChromosome> getSubfront(int rank) {
            if (rank == 0) return new ArrayList<>(front0);
            if (rank == 1) return new ArrayList<>(front1);
            return Collections.emptyList();
        }

        @Override
        public int getNumberOfSubfronts() {
            return 2;
        }
    }

    /**
     * Minimal fitness function for testing.
     */
    private static class DummyTestFitnessFunction extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestChromosome individual, org.evosuite.testcase.execution.ExecutionResult result) {
            return 1.0;
        }

        @Override
        public String getTargetClass() {
            return "Dummy";
        }

        @Override
        public String getTargetMethod() {
            return "dummy()";
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public int hashCode() {
            return 42;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DummyTestFitnessFunction;
        }
    }
}
