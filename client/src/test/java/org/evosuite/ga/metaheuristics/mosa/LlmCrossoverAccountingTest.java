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
import org.evosuite.llm.search.DisruptionEvent;
import org.evosuite.llm.search.DisruptionRecorder;
import org.evosuite.llm.search.LanguageModelCrossover;
import org.evosuite.llm.search.LanguageModelMutation;
import org.evosuite.llm.search.LlmSemanticCrossover;
import org.evosuite.llm.search.LlmSemanticMutation;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.OperatorTargetSelector;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Exercises the real {@link AbstractMOSA#breedNextGeneration()} (not a stand-in) to verify
 * two things at once:
 *
 * <ul>
 *   <li>lineage accounting: when semantic crossover succeeds, only the offspring derived
 *       from parent1 (the one the LLM child replaces) carries both parents' lineages — and,
 *       since the crossover partner need not be parent2, specifically the target's partner's
 *       lineage — while the offspring derived from parent2 stays tagged with parent2's lineage
 *       only; when semantic crossover does not apply and standard crossover runs instead, both
 *       offspring carry both lineages;
 *   <li>goal accounting: the mocked {@link LlmSemanticCrossover} and {@link
 *       LlmSemanticMutation} receive only targets from {@code syncTargetSelector}, whose goal is
 *       always one of DynaMOSA's frontier goals (never an archive-only goal, since the selector
 *       only ever sees {@code goalsManager.getCurrentGoals()}).
 * </ul>
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
    private double savedPTestDelete;
    private double savedPTestChange;
    private double savedPTestInsert;
    private int savedPartnerMinNewGoals;
    private Properties.LlmOperatorTargeting savedTargeting;
    private String savedTargetClass;

    @BeforeEach
    void saveProperties() {
        savedLlmOperatorMode = Properties.LLM_OPERATOR_MODE;
        savedCrossoverProbability = Properties.LLM_CROSSOVER_PROBABILITY;
        savedMutationProbability = Properties.LLM_MUTATION_PROBABILITY;
        savedPopulation = Properties.POPULATION;
        savedTestInsertion = Properties.P_TEST_INSERTION;
        savedCrossoverRate = Properties.CROSSOVER_RATE;
        savedPTestDelete = Properties.P_TEST_DELETE;
        savedPTestChange = Properties.P_TEST_CHANGE;
        savedPTestInsert = Properties.P_TEST_INSERT;
        savedPartnerMinNewGoals = Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS;
        savedTargeting = Properties.LLM_OPERATOR_TARGETING;
        savedTargetClass = Properties.TARGET_CLASS;
        Archive.getArchiveInstance().reset();

        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;
        Properties.POPULATION = 2;
        Properties.P_TEST_INSERTION = 0.0;
        Properties.CROSSOVER_RATE = 1.0;
        Properties.LLM_OPERATOR_TARGETING = Properties.LlmOperatorTargeting.DIRECTED;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 0;
        Properties.TARGET_CLASS = "java.lang.Object";
        // Every seeded parent's test case carries one statement declared on TARGET_CLASS (see
        // #testCaseWithMethodCall), so standard mutation's "ensure has method call" fallback never
        // needs to synthesize a new statement; zeroing these keeps it a pure no-op on top of that.
        Properties.P_TEST_DELETE = 0.0;
        Properties.P_TEST_CHANGE = 0.0;
        Properties.P_TEST_INSERT = 0.0;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODE = savedLlmOperatorMode;
        Properties.LLM_CROSSOVER_PROBABILITY = savedCrossoverProbability;
        Properties.LLM_MUTATION_PROBABILITY = savedMutationProbability;
        Properties.POPULATION = savedPopulation;
        Properties.P_TEST_INSERTION = savedTestInsertion;
        Properties.CROSSOVER_RATE = savedCrossoverRate;
        Properties.P_TEST_DELETE = savedPTestDelete;
        Properties.P_TEST_CHANGE = savedPTestChange;
        Properties.P_TEST_INSERT = savedPTestInsert;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = savedPartnerMinNewGoals;
        Properties.LLM_OPERATOR_TARGETING = savedTargeting;
        Properties.TARGET_CLASS = savedTargetClass;
        Archive.getArchiveInstance().reset();
    }

    @Test
    void semanticCrossoverAppliesOnlyToParent1DerivedOffspringAndSeesOnlyTheFrontier()
            throws Exception {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        AccountingDynaMOSA harness = new AccountingDynaMOSA();
        harness.setSelectionFunction(new AlternatingSelection());
        harness.setCrossOverFunction(new NoOpCrossOverFunction());
        harness.syncTargetSelector = new OperatorTargetSelector(new RepeatedInjectionMemory());

        // frontier1: both parents have a cached distance, so the crossover partner search finds
        // parent2 eligible -> parent1's target is a CROSSOVER target with partner=parent2.
        // frontier2: only parent2 has a cached distance, so no partner is eligible -> parent2's
        // own target is a MUTATION target, exercising the mutation hook on a different goal.
        TestFitnessFunction frontier1 = CurrentGoalsSupplierTest.simpleGoal("frontier1");
        TestFitnessFunction frontier2 = CurrentGoalsSupplierTest.simpleGoal("frontier2");
        harness.goalsManager.getCurrentGoals().add(frontier1);
        harness.goalsManager.getCurrentGoals().add(frontier2);
        harness.publishCurrentGoals();

        TestFitnessFunction archiveOnlyGoal = mock(MethodCoverageTestFitness.class);
        Archive.getArchiveInstance().addTarget(archiveOnlyGoal);

        LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
        when(mockSemanticCrossover.canCall()).thenReturn(true);
        LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
        when(mockSemanticMutation.canCall()).thenReturn(true);
        harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover);
        harness.llmMutation = new LanguageModelMutation(mockSemanticMutation);

        when(mockSemanticCrossover.crossover(any(OperatorTarget.class)))
                .thenAnswer(invocation -> freshChromosome());
        when(mockSemanticMutation.mutate(any(OperatorTarget.class)))
                .thenAnswer(invocation -> freshChromosome());

        TestChromosome parent1 = lineageParent(PARENT1_LINEAGE);
        TestChromosome parent2 = lineageParent(PARENT2_LINEAGE);
        parent1.setFitness(frontier1, 0.2);
        parent2.setFitness(frontier1, 0.2);
        parent2.setFitness(frontier2, 0.2);
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

        // Goal accounting: every semantic call must have seen a frontier goal and never the
        // archive-only goal (which is structurally impossible here since the selector only ever
        // sees goalsManager.getCurrentGoals(), but is asserted directly for regression safety).
        ArgumentCaptor<OperatorTarget> crossoverTargets = ArgumentCaptor.forClass(OperatorTarget.class);
        verify(mockSemanticCrossover, times(1)).crossover(crossoverTargets.capture());
        assertEquals(frontier1, crossoverTargets.getValue().getGoal());
        assertNotEquals(archiveOnlyGoal, crossoverTargets.getValue().getGoal());

        ArgumentCaptor<OperatorTarget> mutationTargets = ArgumentCaptor.forClass(OperatorTarget.class);
        verify(mockSemanticMutation, times(1)).mutate(mutationTargets.capture());
        assertEquals(frontier2, mutationTargets.getValue().getGoal());
        assertNotEquals(archiveOnlyGoal, mutationTargets.getValue().getGoal());
    }

    @Test
    void semanticCrossoverIsJudgedOnItsOwnResultBeforeAnyMutationRuns() throws Exception {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());

        AccountingDynaMOSA harness = new AccountingDynaMOSA();
        harness.setSelectionFunction(new AlternatingSelection());
        harness.setCrossOverFunction(new NoOpCrossOverFunction());
        harness.syncTargetSelector = new OperatorTargetSelector(memory);

        // Covered by any test with at least two statements: the LLM crossover child below has
        // two, the parents and the LLM mutation child have one.
        TestFitnessFunction frontier1 = coveredAtStatements("frontier1", 2);
        TestFitnessFunction frontier2 = coveredAtStatements("frontier2", 99);
        harness.goalsManager.getCurrentGoals().add(frontier1);
        harness.goalsManager.getCurrentGoals().add(frontier2);
        harness.publishCurrentGoals();

        LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
        when(mockSemanticCrossover.canCall()).thenReturn(true);
        LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
        when(mockSemanticMutation.canCall()).thenReturn(true);
        harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover, memory);
        harness.llmMutation = new LanguageModelMutation(mockSemanticMutation, memory);
        when(mockSemanticCrossover.crossover(any(OperatorTarget.class)))
                .thenAnswer(invocation -> freshChromosome(2));
        when(mockSemanticMutation.mutate(any(OperatorTarget.class)))
                .thenAnswer(invocation -> freshChromosome(1));

        TestChromosome parent1 = lineageParent(PARENT1_LINEAGE);
        TestChromosome parent2 = lineageParent(PARENT2_LINEAGE);
        parent1.setFitness(frontier1, 0.2);
        parent2.setFitness(frontier1, 0.2);
        parent2.setFitness(frontier2, 0.2);
        harness.seedPopulation(parent1, parent2);

        harness.breedNextGeneration();

        // The crossover attempt is closed with a gain read from its own child, and before the
        // breeding loop moves on to any mutation call.
        InOrder inOrder = inOrder(memory, mockSemanticMutation);
        inOrder.verify(memory).recordAttemptOutcome(any(String.class), eq(1));
        inOrder.verify(mockSemanticMutation).mutate(any(OperatorTarget.class));
        assertTrue(parent1.getFitnessValues().get(frontier1) == 0.2,
                "isolated probes must not touch the parents' cached fitness");
        assertTrue(harness.goalsManager.getCurrentGoals().contains(frontier1),
                "isolated probes must not advance the goal manager");
    }

    @Test
    void pairedProbeEvaluatesTheTwinInIsolationAndRecordsBothSides(@TempDir Path tempDir) throws Exception {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};
        boolean savedAnalysis = Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED;
        String savedOutputDir = Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR;
        boolean savedPairedProbe = Properties.LLM_OPERATOR_PAIRED_PROBE;
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();
        Properties.LLM_OPERATOR_PAIRED_PROBE = true;
        DisruptionRecorder.resetInstance();
        try {
            AccountingDynaMOSA harness = new AccountingDynaMOSA();
            harness.setSelectionFunction(new AlternatingSelection());
            harness.setCrossOverFunction(new NoOpCrossOverFunction());
            harness.syncTargetSelector = new OperatorTargetSelector(new RepeatedInjectionMemory());
            TestFitnessFunction frontier1 = coveredAtStatements("frontier1", 2);
            TestFitnessFunction frontier2 = coveredAtStatements("frontier2", 99);
            harness.goalsManager.getCurrentGoals().add(frontier1);
            harness.goalsManager.getCurrentGoals().add(frontier2);
            harness.publishCurrentGoals();

            LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
            when(mockSemanticCrossover.canCall()).thenReturn(true);
            LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
            when(mockSemanticMutation.canCall()).thenReturn(true);
            harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover);
            harness.llmMutation = new LanguageModelMutation(mockSemanticMutation);
            when(mockSemanticCrossover.crossover(any(OperatorTarget.class)))
                    .thenAnswer(invocation -> freshChromosome(2));
            when(mockSemanticMutation.mutate(any(OperatorTarget.class)))
                    .thenAnswer(invocation -> freshChromosome(1));

            TestChromosome parent1 = lineageParent(PARENT1_LINEAGE);
            TestChromosome parent2 = lineageParent(PARENT2_LINEAGE);
            parent1.setFitness(frontier1, 0.2);
            parent2.setFitness(frontier1, 0.2);
            parent2.setFitness(frontier2, 0.2);
            harness.seedPopulation(parent1, parent2);

            harness.breedNextGeneration();

            List<DisruptionEvent> semantic = DisruptionRecorder.getInstance().getEvents().stream()
                    .filter(e -> e.getOperatorSource() == DisruptionEvent.OperatorSource.SEMANTIC)
                    .collect(Collectors.toList());
            assertEquals(2, semantic.size(), "one semantic crossover and one semantic mutation event");
            DisruptionEvent crossover = semantic.stream()
                    .filter(e -> e.getOperatorKind() == DisruptionEvent.OperatorKind.CROSSOVER).findFirst().get();
            DisruptionEvent mutation = semantic.stream()
                    .filter(e -> e.getOperatorKind() == DisruptionEvent.OperatorKind.MUTATION).findFirst().get();

            // Crossover: the two-statement LLM child covers frontier1; the no-op crossover twin
            // (a clone of parent1, one statement) does not. The partner is parent2 on record.
            assertEquals(0.0, crossover.getTargetDistanceAfter());
            assertEquals(0.0, crossover.getSemanticTargetDistanceIsolated());
            assertTrue(crossover.isPairedProbe());
            assertEquals(1.0, crossover.getTwinTargetDistance());
            assertEquals(System.identityHashCode(parent2), crossover.getParent2Hash());
            // Mutation: neither the LLM child nor the unchanged standard twin covers frontier2.
            assertTrue(mutation.isPairedProbe());
            assertFalse(mutation.isTwinChanged(), "a standard mutation that changed nothing is still a twin");
            assertEquals(1.0, mutation.getSemanticTargetDistanceIsolated());
            assertEquals(1.0, mutation.getTwinTargetDistance());
            assertTrue(harness.goalsManager.getCurrentGoals().contains(frontier1),
                    "probes must not cover goals away from the search");
        } finally {
            Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = savedAnalysis;
            Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = savedOutputDir;
            Properties.LLM_OPERATOR_PAIRED_PROBE = savedPairedProbe;
            DisruptionRecorder.resetInstance();
        }
    }

    @Test
    void standardCrossoverFallbackGivesBothOffspringBothLineages() throws Exception {
        AccountingDynaMOSA harness = new AccountingDynaMOSA();
        harness.setSelectionFunction(new AlternatingSelection());
        harness.setCrossOverFunction(new NoOpCrossOverFunction());
        // No syncTargetSelector: every SYNC hook attempt gets NO_TARGET, so breedNextGeneration()
        // must fall back to the standard CrossOverFunction and standard mutation for both offspring.
        harness.goalsManager.getCurrentGoals().add(CurrentGoalsSupplierTest.simpleGoal("frontier"));
        harness.publishCurrentGoals();

        LlmSemanticCrossover mockSemanticCrossover = mock(LlmSemanticCrossover.class);
        when(mockSemanticCrossover.canCall()).thenReturn(true);
        LlmSemanticMutation mockSemanticMutation = mock(LlmSemanticMutation.class);
        when(mockSemanticMutation.canCall()).thenReturn(true);
        harness.llmCrossover = new LanguageModelCrossover(mockSemanticCrossover);
        harness.llmMutation = new LanguageModelMutation(mockSemanticMutation);

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
        verify(mockSemanticCrossover, never()).crossover(any());
        verify(mockSemanticMutation, never()).mutate(any());
    }

    private static TestChromosome lineageParent(long lineageId) throws Exception {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(testCaseWithMethodCall());
        tc.setInjectionLineageId(lineageId);
        return tc;
    }

    private static TestChromosome freshChromosome() {
        return freshChromosome(1);
    }

    private static TestChromosome freshChromosome(int statements) {
        try {
            TestChromosome tc = new TestChromosome();
            TestCase testCase = testCaseWithMethodCall();
            for (int i = 1; i < statements; i++) {
                TestFactory.getInstance().addConstructor(testCase,
                        new GenericConstructor(Object.class.getDeclaredConstructor(), Object.class), i, 0);
            }
            tc.setTestCase(testCase);
            return tc;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Goal that is covered (distance 0) by any test with at least {@code statements} statements. */
    private static TestFitnessFunction coveredAtStatements(String name, int statements) {
        return new TestFitnessFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                double fitness = individual.getTestCase().size() >= statements ? 0.0 : 1.0;
                updateIndividual(individual, fitness);
                return fitness;
            }

            @Override
            public int compareTo(TestFitnessFunction o) {
                return 0;
            }

            @Override
            public String getTargetClass() {
                return "Test";
            }

            @Override
            public String getTargetMethod() {
                return name;
            }

            @Override
            public int hashCode() {
                return name.hashCode();
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /**
     * A test case with one statement declared on {@link Properties#TARGET_CLASS}, so {@code
     * AbstractMOSA.hasMethodCall} is satisfied and standard mutation's fallback never needs to
     * synthesize a statement via a fully wired {@code TestFactory} (which this harness does not
     * set up).
     */
    private static TestCase testCaseWithMethodCall() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(testCase,
                new GenericConstructor(Object.class.getDeclaredConstructor(), Object.class), 0, 0);
        return testCase;
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
