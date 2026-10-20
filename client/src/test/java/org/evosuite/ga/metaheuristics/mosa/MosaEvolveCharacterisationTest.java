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
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Seeded characterisation of the generation step of {@link MOSA} and {@link DynaMOSA}, taken
 * before the two {@code evolve()} methods are merged.
 *
 * <p>Both algorithms run their real {@code evolve()}: union of parents and offspring, preference
 * sorting, crowding distance, survival. Only the sources of randomness and cost are replaced:
 * offspring are fresh chromosomes whose fitness vector comes from the seeded {@link Randomness}
 * (rounded to two decimals), and nothing is executed. The goals are minimisation goals that
 * simply read the fitness vector, so the ranking is a pure function of the seeded numbers.
 *
 * <p>Each case pins the population (in order) and the rank-0 front after generations 1, 2 and 3.
 * The default configuration (speciation off) is pinned for both algorithms, with more goals than
 * the population size and with fewer. The speciation case pins the population sizes over
 * several generations; see {@link #speciationRatchetsMosaButNotDynaMosa()}.
 */
class MosaEvolveCharacterisationTest extends MosaTestSupport {

    // ------------------------------------------------------------------ fixture

    private static final class Goal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final int id;

        Goal(int id) {
            this.id = id;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return individual.getFitness(this);
        }

        @Override
        public int compareTo(TestFitnessFunction o) {
            return Integer.compare(id, ((Goal) o).id);
        }

        @Override
        public boolean isMaximizationFunction() {
            return false;
        }

        @Override
        public String getTargetClass() {
            return "Fixture";
        }

        @Override
        public String getTargetMethod() {
            return "goal" + id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Goal && ((Goal) o).id == id;
        }
    }

    private static int nextId;
    private static final Map<TestChromosome, String> LABELS = new IdentityHashMap<>();

    /** Chromosomes with an empty test would all be equal, so each gets one distinct constant. */
    private static TestChromosome chromosome(String label, List<Goal> goals) {
        TestChromosome c = new TestChromosome();
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, nextId++));
        c.setTestCase(tc);
        LABELS.put(c, label);
        for (Goal g : goals) {
            c.addFitness(g, Math.round(Randomness.nextDouble() * 100) / 100.0);
        }
        return c;
    }

    /** Marks every third offspring as a member of an injected lineage, when lineage elitism is tested. */
    private static boolean tagLineages;

    private static void tagLineages(List<TestChromosome> offspring, int iteration) {
        if (!tagLineages) {
            return;
        }
        for (int i = 0; i < offspring.size(); i += 3) {
            offspring.get(i).setInjectionLineageId(1000L * (iteration + 1) + i);
            offspring.get(i).setInjectionGeneration(iteration);
        }
    }

    private static List<Goal> goals(int n) {
        List<Goal> goals = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            goals.add(new Goal(i));
        }
        return goals;
    }

    private static String labels(Collection<? extends TestChromosome> chromosomes) {
        StringBuilder sb = new StringBuilder();
        for (TestChromosome c : chromosomes) {
            sb.append(sb.length() == 0 ? "" : " ").append(LABELS.get(c));
        }
        return sb.toString();
    }

    private interface Harness {
        void step();

        List<TestChromosome> currentPopulation();

        List<TestChromosome> front0();

        void restrictGoals(int n);

        void setRanking(RankBasedPreferenceSorting<TestChromosome> ranking);
    }

    private static class MosaHarness extends MOSA implements Harness {
        private static final long serialVersionUID = 1L;
        private final List<Goal> allGoals;
        private List<Goal> goals;
        private int generation = 0;

        MosaHarness(List<Goal> goals, int initialPopulation) {
            super(() -> new TestChromosome());
            this.allGoals = goals;
            this.goals = goals;
            for (int i = 0; i < initialPopulation; i++) {
                population.add(chromosome("p" + i, goals));
            }
        }

        @Override
        protected Set<TestFitnessFunction> getUncoveredGoals() {
            return new LinkedHashSet<>(goals);
        }

        @Override
        protected List<TestChromosome> breedNextGeneration() {
            generation++;
            List<TestChromosome> offspring = new ArrayList<>();
            for (int i = 0; i < population.size(); i++) {
                offspring.add(chromosome("g" + generation + "o" + i, allGoals));
            }
            tagLineages(offspring, currentIteration);
            return offspring;
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // fitness vectors are assigned on creation
        }

        @Override
        public void step() {
            evolve();
        }

        @Override
        public List<TestChromosome> currentPopulation() {
            return getPopulation();
        }

        @Override
        public List<TestChromosome> front0() {
            return rankingFunction.getSubfront(0);
        }

        @Override
        public void restrictGoals(int n) {
            goals = allGoals.subList(0, n);
        }

        @Override
        public void setRanking(RankBasedPreferenceSorting<TestChromosome> ranking) {
            setRankingFunction(ranking);
        }
    }

    private static class DynaHarness extends DynaMOSA implements Harness {
        private static final long serialVersionUID = 1L;
        private final List<Goal> allGoals;
        private int generation = 0;

        DynaHarness(List<Goal> goals, int initialPopulation) {
            super(() -> new TestChromosome());
            this.allGoals = goals;
            this.goalsManager = new MultiCriteriaManager(Collections.emptyList());
            this.goalsManager.getCurrentGoals().addAll(goals);
            for (int i = 0; i < initialPopulation; i++) {
                population.add(chromosome("p" + i, goals));
            }
        }

        @Override
        protected List<TestChromosome> breedNextGeneration() {
            generation++;
            List<TestChromosome> offspring = new ArrayList<>();
            for (int i = 0; i < population.size(); i++) {
                offspring.add(chromosome("g" + generation + "o" + i, allGoals));
            }
            tagLineages(offspring, currentIteration);
            return offspring;
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // fitness vectors are assigned on creation
        }

        @Override
        public void step() {
            evolve();
        }

        @Override
        public List<TestChromosome> currentPopulation() {
            return getPopulation();
        }

        @Override
        public List<TestChromosome> front0() {
            return rankingFunction.getSubfront(0);
        }

        @Override
        public void restrictGoals(int n) {
            goalsManager.getCurrentGoals().clear();
            goalsManager.getCurrentGoals().addAll(allGoals.subList(0, n));
        }

        @Override
        public void setRanking(RankBasedPreferenceSorting<TestChromosome> ranking) {
            setRankingFunction(ranking);
        }
    }

    private static Harness newHarness(boolean dynamosa, int goalCount, int population) {
        nextId = 0;
        LABELS.clear();
        tagLineages = false;
        Properties.POPULATION = population;
        Randomness.setSeed(42L);
        return dynamosa
                ? new DynaHarness(goals(goalCount), population)
                : new MosaHarness(goals(goalCount), population);
    }

    /** Population size / rank-0 size after each generation, e.g. "6/6 7/7". */
    private static String sizes(Harness harness, int generations) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < generations; g++) {
            harness.step();
            sb.append(sb.length() == 0 ? "" : " ")
                    .append(harness.currentPopulation().size()).append('/').append(harness.front0().size());
        }
        return sb.toString();
    }

    private static String state(Harness harness) {
        return "pop=[" + labels(harness.currentPopulation()) + "] front0=[" + labels(harness.front0()) + "]";
    }

    /**
     * The state after each generation, and the next value of the seeded generator: it differs as
     * soon as a generation draws a different number of random values.
     */
    private static String trace(Harness harness, int generations) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < generations; g++) {
            harness.step();
            sb.append(g == 0 ? "" : "; ").append(state(harness));
        }
        return sb.append("; next=").append(Randomness.nextDouble()).toString();
    }

    @BeforeEach
    void configure() {
        Properties.getInstance().resetToDefaults();
        // MosaTestSupport isolation, re-applied after the reset
        Properties.CRITERION = new Properties.Criterion[0];
        Properties.SELECTION_FUNCTION = Properties.SelectionFunction.RANK_CROWD_DISTANCE_TOURNAMENT;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        // survivors are not cut by the species cap, as in MOSAPopulationSizeTest
        Properties.SPECIES_SURVIVAL_CAP = 1.0;
        Properties.SPECIES_TIMELINE_ENABLED = false;
        Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;
    }

    @AfterEach
    void reset() {
        Properties.getInstance().resetToDefaults();
    }

    // ------------------------------------------------------------------ default configuration

    @Test
    void mosaWithMoreGoalsThanPopulationKeepsPopulationSizeAndDropsAFrontZeroMember() {
        Harness mosa = newHarness(false, 8, 6);

        mosa.step();
        // rank 0 has seven members, the population six: p0 is rank 0 and is cut by crowding
        assertEquals("pop=[g1o3 g1o0 p1 g1o4 g1o2 p3] front0=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0]", state(mosa));
        mosa.step();
        assertEquals("pop=[g1o3 g1o0 p1 g1o4 g2o0 p3] front0=[g1o3 g1o0 p1 g1o4 g2o0 p3]", state(mosa));
        mosa.step();
        assertEquals("pop=[g1o3 g3o5 p1 g1o4 g3o0 p3] front0=[g1o3 g3o5 p1 g1o4 g3o0 p3 g3o3]", state(mosa));
    }

    @Test
    void dynaMosaWithMoreGoalsThanPopulationGrowsToTheSizeOfFrontZero() {
        Harness dyna = newHarness(true, 8, 6);

        dyna.step();
        assertEquals("pop=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0] front0=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0]", state(dyna));
        dyna.step();
        assertEquals("pop=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0] front0=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0]", state(dyna));
        dyna.step();
        assertEquals("pop=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0] front0=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0]", state(dyna));
    }

    @Test
    void mosaWithFewGoalsFillsThePopulationFromLowerFronts() {
        Harness mosa = newHarness(false, 3, 6);

        mosa.step();
        assertEquals("pop=[p2 p3 g1o3 p1 g1o1 g1o4] front0=[p2 p3 g1o3]", state(mosa));
        mosa.step();
        assertEquals("pop=[g2o2 p3 g1o3 p2 g1o4 g2o0] front0=[g2o2 p3 g1o3]", state(mosa));
        mosa.step();
        assertEquals("pop=[g2o2 p3 g1o3 p2 g3o1 g1o4] front0=[g2o2 p3 g1o3]", state(mosa));
    }

    @Test
    void dynaMosaWithFewGoalsFillsThePopulationFromLowerFronts() {
        Harness dyna = newHarness(true, 3, 6);

        dyna.step();
        assertEquals("pop=[p2 p3 g1o3 p1 g1o1 g1o4] front0=[p2 p3 g1o3]", state(dyna));
        dyna.step();
        assertEquals("pop=[g2o2 p3 g1o3 p2 g1o4 g2o0] front0=[g2o2 p3 g1o3]", state(dyna));
        dyna.step();
        assertEquals("pop=[g2o2 p3 g1o3 p2 g3o1 g1o4] front0=[g2o2 p3 g1o3]", state(dyna));
    }

    /**
     * With a rank-0 front that fits into the population the two algorithms are the same
     * procedure; the merge has to keep that. They differ exactly when rank 0 outgrows the
     * population (see the two tests above).
     */
    @Test
    void mosaAndDynaMosaAgreeWhenFrontZeroFitsThePopulation() {
        Harness mosa = newHarness(false, 3, 6);
        String mosaSizes = sizes(mosa, 5);
        String mosaState = state(mosa);
        Harness dyna = newHarness(true, 3, 6);
        String dynaSizes = sizes(dyna, 5);

        assertEquals("6/3 6/3 6/3 6/3 6/3", mosaSizes);
        assertEquals(mosaSizes, dynaSizes);
        assertEquals(mosaState, state(dyna));
    }

    @Test
    void mosaAndDynaMosaDifferWhenFrontZeroOutgrowsThePopulation() {
        String mosaSizes = sizes(newHarness(false, 8, 6), 3);
        String dynaSizes = sizes(newHarness(true, 8, 6), 3);

        assertEquals("6/7 6/6 6/7", mosaSizes);
        assertEquals("7/7 7/7 7/7", dynaSizes);
        assertNotEquals(mosaSizes, dynaSizes);
    }

    // ------------------------------------------------------------------ speciation

    /**
     * Population size / rank-0 size under speciation_enabled=true: eight generations with eight
     * goals and a base population of four, then three more generations after the goals shrink to
     * three.
     *
     * <p>Both algorithms select the survivors for {@code Properties.POPULATION} every generation
     * and survival keeps the whole rank-0 front, so the size is {@code max(POPULATION, rank 0)}
     * and the two algorithms follow the same sequence.
     */
    @Test
    void speciationKeepsTheSizeAtThePopulationOrTheFrontZero() {
        Properties.SPECIATION_ENABLED = true;

        Harness mosa = newHarness(false, 8, 4);
        String mosaSizes = sizes(mosa, 8);
        mosa.restrictGoals(3);
        mosaSizes += " | " + sizes(mosa, 3);

        Harness dyna = newHarness(true, 8, 4);
        String dynaSizes = sizes(dyna, 8);
        dyna.restrictGoals(3);
        dynaSizes += " | " + sizes(dyna, 3);

        assertEquals("6/6 7/7 7/7 6/6 7/7 6/6 6/6 6/6 | 4/3 4/3 4/3", mosaSizes);
        assertEquals(mosaSizes, dynaSizes);
    }

    /**
     * MOSA does not remember the largest rank-0 front it has seen: each generation selects for the
     * configured population size, so once rank 0 fits again the population is back at that size.
     */
    @Test
    void mosaPopulationReturnsToThePopulationSizeUnderSpeciation() {
        Properties.SPECIATION_ENABLED = true;
        int populationSize = 4;
        Harness mosa = newHarness(false, 8, populationSize);

        for (int generation = 0; generation < 8; generation++) {
            mosa.step();
            assertEquals(Math.max(populationSize, mosa.front0().size()), mosa.currentPopulation().size());
        }
        mosa.restrictGoals(3);
        for (int generation = 0; generation < 3; generation++) {
            mosa.step();
            assertEquals(populationSize, mosa.currentPopulation().size());
        }
    }

    /** The first generation does not yet ratchet: both algorithms start from the same base. */
    @Test
    void speciationFirstGenerationMatchesBetweenMosaAndDynaMosa() {
        Properties.SPECIATION_ENABLED = true;
        Harness mosa = newHarness(false, 8, 4);
        mosa.step();
        String mosaState = state(mosa);
        Harness dyna = newHarness(true, 8, 4);
        dyna.step();

        assertEquals(mosaState, state(dyna));
        assertEquals(6, dyna.currentPopulation().size());
    }

    // ------------------------------------------------------------------ order and random draws

    private static void withSpeciation() {
        Properties.SPECIATION_ENABLED = true;
    }

    private static void withLineageElitism() {
        Properties.LLM_LINEAGE_ELITISM_GENERATIONS = 3;
        Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION = 0.5;
    }

    /** Speciation off, no external candidates, no lineage elitism: the default configuration. */
    @Test
    void mosaDefaultPinsOrderAndRandomDraws() {
        assertEquals("pop=[g1o3 g1o0 p1 g1o4 g1o2 p3] front0=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0]"
                + "; pop=[g1o3 g1o0 p1 g1o4 g2o0 p3] front0=[g1o3 g1o0 p1 g1o4 g2o0 p3]"
                + "; pop=[g1o3 g3o5 p1 g1o4 g3o0 p3] front0=[g1o3 g3o5 p1 g1o4 g3o0 p3 g3o3]"
                + "; next=0.6331014512286649", trace(newHarness(false, 8, 6), 3));
    }

    @Test
    void dynaMosaDefaultPinsOrderAndRandomDraws() {
        assertEquals("pop=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0] front0=[g1o3 g1o0 p1 g1o4 g1o2 p3 p0]"
                + "; pop=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0] front0=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0]"
                + "; pop=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0] front0=[g1o3 g2o6 p1 g1o4 g2o0 p3 p0]"
                + "; next=0.2839209759355791", trace(newHarness(true, 8, 6), 3));
    }

    @Test
    void mosaSpeciationPinsOrderAndRandomDraws() {
        withSpeciation();
        assertEquals("pop=[g1o0 g1o2 p1 p2 p0 p3] front0=[g1o0 g1o2 p1 p2 p0 p3]"
                + "; pop=[g2o1 g1o2 p1 g2o2 g2o4 p3 p0] front0=[g2o1 g1o2 p1 g2o2 g2o4 p3 p0]"
                + "; pop=[g2o1 g1o2 p1 g2o2 g3o4 g3o1 p0] front0=[g2o1 g1o2 p1 g2o2 g3o4 g3o1 p0]"
                + "; next=0.5908929456168249", trace(newHarness(false, 8, 4), 3));
    }

    @Test
    void dynaMosaSpeciationPinsOrderAndRandomDraws() {
        withSpeciation();
        assertEquals("pop=[g1o0 g1o2 p1 p2 p0 p3] front0=[g1o0 g1o2 p1 p2 p0 p3]"
                + "; pop=[g2o1 g1o2 p1 g2o2 g2o4 p3 p0] front0=[g2o1 g1o2 p1 g2o2 g2o4 p3 p0]"
                + "; pop=[g2o1 g1o2 p1 g2o2 g3o4 g3o1 p0] front0=[g2o1 g1o2 p1 g2o2 g3o4 g3o1 p0]"
                + "; next=0.5908929456168249", trace(newHarness(true, 8, 4), 3));
    }

    @Test
    void mosaLineageElitismPinsOrderAndRandomDraws() {
        withLineageElitism();
        Harness mosa = newHarness(false, 3, 6);
        tagLineages = true;
        assertEquals("pop=[p2 p3 g1o3 g1o0 g1o1 g1o4] front0=[p2 p3 g1o3]"
                + "; pop=[g2o2 p3 g1o3 g2o3 g1o4 g2o0] front0=[g2o2 p3 g1o3]"
                + "; pop=[g2o2 p3 g1o3 g3o0 g3o3 g3o4] front0=[g2o2 p3 g1o3]"
                + "; next=0.005522117228505263", trace(mosa, 3));
    }

    @Test
    void dynaMosaLineageElitismPinsOrderAndRandomDraws() {
        withLineageElitism();
        Harness dyna = newHarness(true, 3, 6);
        tagLineages = true;
        assertEquals("pop=[p2 p3 g1o3 g1o0 g1o1 g1o4] front0=[p2 p3 g1o3]"
                + "; pop=[g2o2 p3 g1o3 g2o3 g1o4 g2o0] front0=[g2o2 p3 g1o3]"
                + "; pop=[g2o2 p3 g1o3 g3o0 g3o3 g3o4] front0=[g2o2 p3 g1o3]"
                + "; next=0.005522117228505263", trace(dyna, 3));
    }

    @Test
    void mosaSpeciationWithLineageElitismPinsOrderAndRandomDraws() {
        withSpeciation();
        withLineageElitism();
        Harness mosa = newHarness(false, 3, 4);
        tagLineages = true;
        assertEquals("pop=[p2 p3 p1 g1o0] front0=[p2 p3 p1]"
                + "; pop=[p2 p3 g2o1 g2o0] front0=[p2 p3 g2o1]"
                + "; pop=[g3o2 p3 g2o1 g3o0] front0=[g3o2 p3 g2o1]"
                + "; next=0.5467102761223961", trace(mosa, 3));
    }

    @Test
    void dynaMosaSpeciationWithLineageElitismPinsOrderAndRandomDraws() {
        withSpeciation();
        withLineageElitism();
        Harness dyna = newHarness(true, 3, 4);
        tagLineages = true;
        assertEquals("pop=[p2 p3 p1 g1o0] front0=[p2 p3 p1]"
                + "; pop=[p2 p3 g2o1 g2o0] front0=[p2 p3 g2o1]"
                + "; pop=[g3o2 p3 g2o1 g3o0] front0=[g3o2 p3 g2o1]"
                + "; next=0.5467102761223961", trace(dyna, 3));
    }

    // ------------------------------------------------------------------ ranking failure

    /** Records the species context handed to the ranking and fails the ranking itself. */
    private static final class FailingRanking extends RankBasedPreferenceSorting<TestChromosome> {
        private static final long serialVersionUID = 1L;
        Map<TestChromosome, Integer> context;

        @Override
        public void setSpeciesContext(Map<TestChromosome, Integer> speciesContext) {
            this.context = speciesContext;
            super.setSpeciesContext(speciesContext);
        }

        @Override
        public void computeRankingAssignment(List<TestChromosome> solutions,
                                             Set<? extends FitnessFunction<TestChromosome>> uncoveredGoals) {
            throw new IllegalStateException("ranking failed");
        }
    }

    @Test
    void rankingFailureDoesNotLeaveASpeciesContextBehind() {
        Properties.SPECIATION_ENABLED = true;
        FailingRanking failing = new FailingRanking();
        for (boolean dynamosa : new boolean[] {false, true}) {
            Harness harness = newHarness(dynamosa, 8, 4);
            harness.step();
            harness.setRanking(failing);
            failing.context = null;

            assertThrows(IllegalStateException.class, harness::step);
            assertNull(failing.context);
        }
    }
}
