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
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.ga.operators.selection.SelectionFunction;
import org.evosuite.llm.search.LanguageModelCrossover;
import org.evosuite.llm.search.LanguageModelMutation;
import org.evosuite.llm.search.LlmSemanticCrossover;
import org.evosuite.llm.search.LlmSemanticMutation;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real {@link AbstractMOSA#breedNextGeneration()} (not a stand-in) to verify
 * two things at once:
 *
 * <ul>
 *   <li>lineage accounting: when semantic crossover succeeds, only the offspring derived
 *       from parent1 (the one the LLM child replaces) carries both parents' lineages, while
 *       the offspring derived from parent2 stays tagged with parent2's lineage only; when
 *       semantic crossover does not apply and standard crossover runs instead, both
 *       offspring carry both lineages;
 *   <li>goal accounting: the mocked {@link LlmSemanticCrossover} and {@link
 *       LlmSemanticMutation} receive exactly DynaMOSA's frontier — a goal that is only an
 *       uncovered archive target (not in {@code goalsManager.getCurrentGoals()}) must never
 *       reach them.
 * </ul>
 *
 * <p>Standard mutation is never exercised here: it needs a fully wired {@code TestFactory}
 * that this harness does not set up. {@code LLM_MUTATION_PROBABILITY=1.0} together with a
 * mocked {@link LlmSemanticMutation} that always returns a fresh chromosome guarantees the
 * semantic path is always taken instead.
 */
class LlmCrossoverAccountingTest extends MosaTestSupport {

    private static final long PARENT1_LINEAGE = 101L;
    private static final long PARENT2_LINEAGE = 202L;

    private Properties.LlmOperatorMode savedLlmOperatorMode;
    private double savedCrossoverProbability;
    private double savedMutationProbability;
    private int savedPopulation;
    private double savedTestInsertion;
    private double savedCrossoverRate;

    @BeforeEach
    void saveProperties() {
        savedLlmOperatorMode = Properties.LLM_OPERATOR_MODE;
        savedCrossoverProbability = Properties.LLM_CROSSOVER_PROBABILITY;
        savedMutationProbability = Properties.LLM_MUTATION_PROBABILITY;
        savedPopulation = Properties.POPULATION;
        savedTestInsertion = Properties.P_TEST_INSERTION;
        savedCrossoverRate = Properties.CROSSOVER_RATE;
        Archive.getArchiveInstance().reset();

        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;
        Properties.POPULATION = 2;
        Properties.P_TEST_INSERTION = 0.0;
        Properties.CROSSOVER_RATE = 1.0;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODE = savedLlmOperatorMode;
        Properties.LLM_CROSSOVER_PROBABILITY = savedCrossoverProbability;
        Properties.LLM_MUTATION_PROBABILITY = savedMutationProbability;
        Properties.POPULATION = savedPopulation;
        Properties.P_TEST_INSERTION = savedTestInsertion;
        Properties.CROSSOVER_RATE = savedCrossoverRate;
        Archive.getArchiveInstance().reset();
    }

    @Test
    void semanticCrossoverAppliesOnlyToParent1DerivedOffspringAndSeesOnlyTheFrontier() {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        AccountingDynaMOSA harness = new AccountingDynaMOSA();
        harness.setSelectionFunction(new AlternatingSelection());
        harness.setCrossOverFunction(new NoOpCrossOverFunction());

        TestFitnessFunction frontierGoal = CurrentGoalsSupplierTest.simpleGoal("frontier");
        harness.goalsManager.getCurrentGoals().add(frontierGoal);
        harness.publishCurrentGoals();

        TestFitnessFunction archiveOnlyGoal = mock(MethodCoverageTestFitness.class);
        Archive.getArchiveInstance().addTarget(archiveOnlyGoal);

        LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
        LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
        harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover);
        harness.llmMutation = new LanguageModelMutation(mockSemanticMutation);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TestFitnessFunction>> crossoverGoals =
                ArgumentCaptor.forClass(Collection.class);
        when(mockSemanticCrossover.crossoverSemantically(
                any(TestChromosome.class), any(TestChromosome.class), crossoverGoals.capture()))
                .thenAnswer(invocation -> freshChromosome());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TestFitnessFunction>> mutationGoals =
                ArgumentCaptor.forClass(Collection.class);
        when(mockSemanticMutation.mutateSemantically(any(TestChromosome.class), mutationGoals.capture()))
                .thenAnswer(invocation -> freshChromosome());

        TestChromosome parent1 = lineageParent(PARENT1_LINEAGE);
        TestChromosome parent2 = lineageParent(PARENT2_LINEAGE);
        harness.seedPopulation(parent1, parent2);

        List<TestChromosome> offspring = harness.breedNextGeneration();

        assertEquals(2, offspring.size(), "both offspring should survive breeding");
        TestChromosome fromParent1 = offspring.get(0);
        TestChromosome fromParent2 = offspring.get(1);

        assertTrue(fromParent1.effectiveLineages().contains(PARENT1_LINEAGE)
                        && fromParent1.effectiveLineages().contains(PARENT2_LINEAGE),
                "the offspring the LLM child replaced must carry both parents' lineages: "
                        + fromParent1.effectiveLineages());
        assertTrue(fromParent2.effectiveLineages().contains(PARENT2_LINEAGE)
                        && !fromParent2.effectiveLineages().contains(PARENT1_LINEAGE),
                "the untouched second offspring must carry only parent2's lineage: "
                        + fromParent2.effectiveLineages());

        // Goal accounting: every semantic call must have seen the frontier and never the
        // archive-only goal.
        assertFalse(crossoverGoals.getAllValues().isEmpty(), "crossover must have been attempted");
        for (Collection<TestFitnessFunction> goals : crossoverGoals.getAllValues()) {
            assertTrue(goals.contains(frontierGoal), "crossover must see the frontier goal");
            assertFalse(goals.contains(archiveOnlyGoal),
                    "crossover must not see an archive-only goal outside the frontier");
        }
        verify(mockSemanticMutation, times(2))
                .mutateSemantically(any(TestChromosome.class), any());
        assertFalse(mutationGoals.getAllValues().isEmpty(), "mutation must have been attempted");
        for (Collection<TestFitnessFunction> goals : mutationGoals.getAllValues()) {
            assertTrue(goals.contains(frontierGoal), "mutation must see the frontier goal");
            assertFalse(goals.contains(archiveOnlyGoal),
                    "mutation must not see an archive-only goal outside the frontier");
        }
    }

    @Test
    void standardCrossoverFallbackGivesBothOffspringBothLineages() {
        AccountingDynaMOSA harness = new AccountingDynaMOSA();
        harness.setSelectionFunction(new AlternatingSelection());
        harness.setCrossOverFunction(new NoOpCrossOverFunction());
        harness.goalsManager.getCurrentGoals().add(CurrentGoalsSupplierTest.simpleGoal("frontier"));
        harness.publishCurrentGoals();

        // The mocked semantic crossover reports "no child produced", the same outcome as an
        // unavailable/failed LLM call: breedNextGeneration() must fall back to the standard
        // CrossOverFunction. LLM_OPERATOR_MODE stays SYNC throughout so mutation keeps
        // taking the (mocked) semantic path, sidestepping standard mutation.
        LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
        LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
        harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover);
        harness.llmMutation = new LanguageModelMutation(mockSemanticMutation);

        when(mockSemanticCrossover.crossoverSemantically(
                any(TestChromosome.class), any(TestChromosome.class), any()))
                .thenReturn(null);
        when(mockSemanticMutation.mutateSemantically(any(TestChromosome.class), any()))
                .thenAnswer(invocation -> freshChromosome());

        TestChromosome parent1 = lineageParent(PARENT1_LINEAGE);
        TestChromosome parent2 = lineageParent(PARENT2_LINEAGE);
        harness.seedPopulation(parent1, parent2);

        List<TestChromosome> offspring = harness.breedNextGeneration();

        assertEquals(2, offspring.size(), "both offspring should survive breeding");
        for (TestChromosome child : offspring) {
            assertTrue(child.effectiveLineages().contains(PARENT1_LINEAGE)
                            && child.effectiveLineages().contains(PARENT2_LINEAGE),
                    "standard crossover recombines both parents, so both offspring must "
                            + "carry both lineages: " + child.effectiveLineages());
        }
    }

    private static TestChromosome lineageParent(long lineageId) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        tc.setInjectionLineageId(lineageId);
        return tc;
    }

    private static TestChromosome freshChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    /** Selects population members 0, 1, 0, 1, ... in call order — deterministic parent pairing. */
    private static class AlternatingSelection extends SelectionFunction<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private int calls = 0;

        @Override
        public int getIndex(List<TestChromosome> population) {
            return (calls++) % population.size();
        }
    }

    /** Succeeds without altering either parent; only the crossoverApplied bookkeeping matters. */
    private static class NoOpCrossOverFunction extends CrossOverFunction<TestChromosome> {
        private static final long serialVersionUID = 1L;

        @Override
        public void crossOver(TestChromosome parent1, TestChromosome parent2)
                throws ConstructionFailedException {
            // Intentionally does nothing.
        }
    }

    /**
     * Real {@code DynaMOSA} whose {@code breedNextGeneration()} runs unmodified; only fitness
     * evaluation is stubbed out, and population seeding is exposed for the test.
     */
    private static class AccountingDynaMOSA extends DynaMOSA {
        private static final long serialVersionUID = 1L;

        AccountingDynaMOSA() {
            super(TestChromosome::new);
            this.goalsManager = new MultiCriteriaManager(Collections.emptyList());
        }

        void seedPopulation(TestChromosome parent1, TestChromosome parent2) {
            population.clear();
            population.add(parent1);
            population.add(parent2);
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            // no-op: fitness evaluation is irrelevant to lineage/goal accounting
        }
    }
}
