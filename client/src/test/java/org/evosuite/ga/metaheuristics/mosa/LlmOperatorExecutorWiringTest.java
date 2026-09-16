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
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.LanguageModelCrossover;
import org.evosuite.llm.search.LanguageModelMutation;
import org.evosuite.llm.search.operator.LlmOperatorExecutor;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.InjectionSource;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the ASYNC {@link LlmOperatorExecutor} wiring in {@link AbstractMOSA}:
 * {@link AbstractMOSA#initializeLlmAssistance} creates the right combination of hooks per {@link
 * Properties.LlmOperatorMode}, {@link AbstractMOSA#registerExternalCandidateSources} registers a
 * source that drives the executor and tags its output {@link InjectionSource#LLM_OPERATOR}, and
 * {@link AbstractMOSA#shutdownLlmAssistance} stops the executor before nulling the shared
 * repeated-injection memory.
 */
class LlmOperatorExecutorWiringTest extends MosaTestSupport {

    private Properties.LlmOperatorMode savedMode;
    private int savedCallsPerGeneration;
    private int savedBatchSize;
    private Properties.LlmProvider savedProvider;

    @BeforeEach
    void saveProperties() {
        savedMode = Properties.LLM_OPERATOR_MODE;
        savedCallsPerGeneration = Properties.LLM_OPERATOR_CALLS_PER_GENERATION;
        savedBatchSize = Properties.LLM_OPERATOR_BATCH_SIZE;
        savedProvider = Properties.LLM_PROVIDER;
        Archive.getArchiveInstance().reset();
        OperatorAttemptLog.resetInstance();
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODE = savedMode;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = savedCallsPerGeneration;
        Properties.LLM_OPERATOR_BATCH_SIZE = savedBatchSize;
        Properties.LLM_PROVIDER = savedProvider;
        Archive.getArchiveInstance().reset();
        OperatorAttemptLog.resetInstance();
    }

    @Test
    void asyncModeCreatesTheExecutorAndSelectorButNotTheSyncHooks() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);

        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        assertEquals(mockExecutor, harness.operatorExecutor);
        assertNotNull(harness.operatorTargetSelector);
        assertNull(harness.llmMutation);
        assertNull(harness.llmCrossover);
    }

    @Test
    void syncModeCreatesTheHooksButNotTheExecutor() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        OperatorExecutorHarness harness = new OperatorExecutorHarness(null);

        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        assertNotNull(harness.llmMutation);
        assertNotNull(harness.llmCrossover);
        assertNotNull(harness.operatorTargetSelector);
        assertNull(harness.operatorExecutor);
    }

    @Test
    void initializeLlmAssistanceResetsTheAttemptLogEvenWhenTheNewRunIsOff() throws Exception {
        // A JVM that runs an operator-enabled search followed by an OFF search (e.g. two runs of
        // the same client process) must not publish the previous run's counts, or flush its rows
        // under the new run's target class/sidecar name.
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        OperatorExecutorHarness onHarness = new OperatorExecutorHarness(null);
        onHarness.initializeLlmAssistance(onHarness::getCurrentGoals, false);

        TestChromosome parent = arrayListConstructorChromosome(1);
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        OperatorTarget target = OperatorTarget.builder(parent, goal).build();
        OperatorAttemptLog.getInstance().open("stale-run-attempt", 0, target, "SYNC", 1);
        assertEquals(1, OperatorAttemptLog.getInstance().getAttempts());

        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        OperatorExecutorHarness offHarness = new OperatorExecutorHarness(null);
        offHarness.initializeLlmAssistance(offHarness::getCurrentGoals, false);

        @SuppressWarnings("unchecked")
        ClientNodeLocal<TestChromosome> clientNode = mock(ClientNodeLocal.class);
        offHarness.emitOperatorStats(clientNode);

        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Targets_Selected, 0L);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Offspring_Admitted, 0L);
    }

    @Test
    void offModeCreatesNothing() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        OperatorExecutorHarness harness = new OperatorExecutorHarness(null);

        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        assertNull(harness.llmMutation);
        assertNull(harness.llmCrossover);
        assertNull(harness.operatorTargetSelector);
        assertNull(harness.operatorExecutor);
    }

    @Test
    void collectExternalCandidatesDrainsTagsAndReportsThroughTheExecutor() throws Exception {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        TestChromosome candidate1 = arrayListConstructorChromosome(1);
        TestChromosome candidate2 = arrayListConstructorChromosome(2);
        when(mockExecutor.drain()).thenReturn(Arrays.asList(candidate1, candidate2));
        InjectionAttemptMetadata metadata = new InjectionAttemptMetadata("attempt-1", Collections.emptyList());
        when(mockExecutor.consumeAttemptMetadata(candidate1)).thenReturn(metadata);
        when(mockExecutor.consumeAttemptMetadata(candidate2)).thenReturn(null);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        List<TestChromosome> union = new ArrayList<>();
        harness.collectExternalCandidates(union);

        verify(mockExecutor).tickGeneration();
        verify(mockExecutor).drain();
        assertTrue(union.contains(candidate1));
        assertTrue(union.contains(candidate2));
        assertEquals(InjectionSource.LLM_OPERATOR, candidate1.getInjectionSource());
        assertEquals(InjectionSource.LLM_OPERATOR, candidate2.getInjectionSource());
        verify(mockExecutor, times(1)).reportAttemptOutcome(eq("attempt-1"), eq(0));
        verify(mockExecutor).submit(anyList(), anyInt(), anyInt());
    }

    @Test
    void aCandidateThatThrowsDuringFitnessDoesNotPreventTheOthersFromBeingReportedOrAdmitted() throws Exception {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        TestChromosome candidate1 = arrayListConstructorChromosome(1);
        TestChromosome candidate2 = arrayListConstructorChromosome(2);
        harness.throwOnFitness = candidate1;
        when(mockExecutor.drain()).thenReturn(Arrays.asList(candidate1, candidate2));
        InjectionAttemptMetadata metadata1 = new InjectionAttemptMetadata("attempt-1", Collections.emptyList());
        InjectionAttemptMetadata metadata2 = new InjectionAttemptMetadata("attempt-2", Collections.emptyList());
        when(mockExecutor.consumeAttemptMetadata(candidate1)).thenReturn(metadata1);
        when(mockExecutor.consumeAttemptMetadata(candidate2)).thenReturn(metadata2);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        List<TestChromosome> union = new ArrayList<>();
        harness.collectExternalCandidates(union);

        assertFalse(union.contains(candidate1),
                "the candidate whose fitness evaluation threw must not be admitted");
        assertTrue(union.contains(candidate2), "the other candidate must still be admitted");
        // Both attempts must still be reported (from the finally), including the one whose
        // candidate never got past calculateFitness — it keeps the 0 seeded up front.
        verify(mockExecutor).reportAttemptOutcome(eq("attempt-1"), eq(0));
        verify(mockExecutor).reportAttemptOutcome(eq("attempt-2"), eq(0));
    }

    @Test
    void submitIsSkippedWithoutCapacity() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(false);
        when(mockExecutor.drain()).thenReturn(Collections.emptyList());
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        harness.collectExternalCandidates(new ArrayList<>());

        verify(mockExecutor, never()).submit(anyList(), anyInt(), anyInt());
    }

    @Test
    void submitIsSkippedWhenCallsPerGenerationIsZero() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 0;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        when(mockExecutor.drain()).thenReturn(Collections.emptyList());
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        harness.collectExternalCandidates(new ArrayList<>());

        verify(mockExecutor, never()).submit(anyList(), anyInt(), anyInt());
    }

    @Test
    void shutdownStopsTheExecutorAndIsIdempotent() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        harness.callShutdownLlmAssistance();
        assertNull(harness.operatorExecutor);
        verify(mockExecutor, times(1)).shutdown();

        // A second shutdown must be safe (no NPE, no second shutdown() call on the executor
        // that was already nulled).
        harness.callShutdownLlmAssistance();
        verify(mockExecutor, times(1)).shutdown();
    }

    @Test
    void deduplicatesTwoDrainedOperatorCandidatesWithIdenticalCallSequences() throws Exception {
        Properties.CRITERION = new Properties.Criterion[0];
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 0;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(false);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        TestChromosome first = arrayListConstructorChromosome(5);
        TestChromosome duplicate = arrayListConstructorChromosome(5);
        when(mockExecutor.drain()).thenReturn(Arrays.asList(first, duplicate));

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        List<TestChromosome> union = new ArrayList<>();
        harness.collectExternalCandidates(union);

        assertEquals(1, harness.lastGenInjectedCandidatesAdmittedCount,
                "only one of two byte-identical candidates must be admitted");
        assertEquals(1, harness.lastGenInjectedCandidatesDeduplicatedCount,
                "the byte-identical duplicate must be counted as deduplicated");
    }

    @Test
    void driveOperatorExecutorSubmitsWithTheCurrentGeneration() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        when(mockExecutor.drain()).thenReturn(Collections.emptyList());
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);
        harness.setCurrentIterationForTest(5);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        harness.collectExternalCandidates(new ArrayList<>());

        verify(mockExecutor).submit(anyList(), anyInt(), eq(5));
    }

    @Test
    void emitOperatorStatsTracksAllNineteenOperatorVariables() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.getDroppedBudget()).thenReturn(1L);
        when(mockExecutor.getDroppedInFlight()).thenReturn(2L);
        when(mockExecutor.getDroppedTime()).thenReturn(3L);
        when(mockExecutor.getFailedCalls()).thenReturn(4L);
        when(mockExecutor.getInFlightGenerations()).thenReturn(5L);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);
        // ASYNC mode leaves the SYNC hooks null; set them directly too, so the four
        // pre-existing LLM_Semantic_* counters are exercised in the same pass.
        harness.llmMutation = new LanguageModelMutation();
        harness.llmCrossover = new LanguageModelCrossover();

        @SuppressWarnings("unchecked")
        ClientNodeLocal<TestChromosome> clientNode = mock(ClientNodeLocal.class);
        harness.emitOperatorStats(clientNode);

        RuntimeVariable[] expected = {
                RuntimeVariable.LLM_Semantic_Mutations,
                RuntimeVariable.LLM_Semantic_Mutation_Fallbacks,
                RuntimeVariable.LLM_Semantic_Crossovers,
                RuntimeVariable.LLM_Semantic_Crossover_Fallbacks,
                RuntimeVariable.LLM_Operator_Targets_Selected,
                RuntimeVariable.LLM_Operator_Targets_Mutation,
                RuntimeVariable.LLM_Operator_Targets_Crossover,
                RuntimeVariable.LLM_Operator_Calls_Dropped_Budget,
                RuntimeVariable.LLM_Operator_Calls_Dropped_InFlight,
                RuntimeVariable.LLM_Operator_Calls_Dropped_Time,
                RuntimeVariable.LLM_Operator_Calls_Failed,
                RuntimeVariable.LLM_Operator_InFlight_Generations,
                RuntimeVariable.LLM_Operator_Offspring_Parsed,
                RuntimeVariable.LLM_Operator_Offspring_Admitted,
                RuntimeVariable.LLM_Operator_Offspring_Survived,
                RuntimeVariable.LLM_Operator_Target_Hits,
                RuntimeVariable.LLM_Operator_Target_Improved,
                RuntimeVariable.LLM_Operator_Candidates_StaleTarget,
                RuntimeVariable.LLM_Operator_Sidecar_Path,
        };
        assertEquals(19, expected.length);
        for (RuntimeVariable variable : expected) {
            verify(clientNode).trackOutputVariable(eq(variable), any());
        }
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_Budget, 1L);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_InFlight, 2L);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Dropped_Time, 3L);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Calls_Failed, 4L);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_InFlight_Generations, 5L);
    }

    @Test
    void collectExternalCandidatesRecordsEvaluationAndAdmissionInTheAttemptLog() throws Exception {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        TestChromosome parent = arrayListConstructorChromosome(1);
        TestChromosome candidate = arrayListConstructorChromosome(2);
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        OperatorTarget target = OperatorTarget.builder(parent, goal).build();
        OperatorAttemptLog.getInstance().open("attempt-log-1", 0, target, "ASYNC", 1);

        when(mockExecutor.drain()).thenReturn(Collections.singletonList(candidate));
        InjectionAttemptMetadata metadata = new InjectionAttemptMetadata(
                "attempt-log-1", Collections.emptyList(), Collections.singletonList(goal));
        when(mockExecutor.consumeAttemptMetadata(candidate)).thenReturn(metadata);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        List<TestChromosome> union = new ArrayList<>();
        harness.collectExternalCandidates(union);

        assertTrue(union.contains(candidate));
        // The mocked goal reports a default (unstubbed) fitness of 0.0, i.e. a hit, once
        // calculateFitness (a no-op in this harness) has "run".
        assertEquals(1, OperatorAttemptLog.getInstance().getTargetHits());
        assertEquals(1, OperatorAttemptLog.getInstance().getOffspringAdmitted());
    }

    @Test
    void secondDrainedCandidateForTheSameLiveGoalIsNotCountedAsStale() throws Exception {
        // remainingUncoveredBeforeInjection is mutated (goal removed) by the first candidate
        // that covers it; the staleness check must use an immutable snapshot taken before the
        // drain loop instead, or the second candidate targeting the same still-live goal is
        // wrongly counted stale just because the first one got there first.
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.ASYNC;
        Properties.LLM_OPERATOR_CALLS_PER_GENERATION = 1;
        Properties.LLM_OPERATOR_BATCH_SIZE = 1;

        LlmOperatorExecutor mockExecutor = mock(LlmOperatorExecutor.class);
        when(mockExecutor.hasCapacity()).thenReturn(true);
        OperatorExecutorHarness harness = new OperatorExecutorHarness(mockExecutor);
        harness.initializeLlmAssistance(harness::getCurrentGoals, false);

        TestFitnessFunction goal = mock(MethodCoverageTestFitness.class);
        when(goal.isCovered(org.mockito.ArgumentMatchers.any(TestChromosome.class))).thenReturn(true);
        Archive.getArchiveInstance().addTarget(goal);

        TestChromosome candidate1 = arrayListConstructorChromosome(1);
        TestChromosome candidate2 = arrayListConstructorChromosome(2);
        when(mockExecutor.drain()).thenReturn(Arrays.asList(candidate1, candidate2));
        InjectionAttemptMetadata metadata1 = new InjectionAttemptMetadata(
                "attempt-1", Collections.emptyList(), Collections.singletonList(goal));
        InjectionAttemptMetadata metadata2 = new InjectionAttemptMetadata(
                "attempt-2", Collections.emptyList(), Collections.singletonList(goal));
        when(mockExecutor.consumeAttemptMetadata(candidate1)).thenReturn(metadata1);
        when(mockExecutor.consumeAttemptMetadata(candidate2)).thenReturn(metadata2);

        harness.registerExternalCandidateSources(() -> 0, harness::getCurrentGoals);
        harness.collectExternalCandidates(new ArrayList<>());

        @SuppressWarnings("unchecked")
        ClientNodeLocal<TestChromosome> clientNode = mock(ClientNodeLocal.class);
        harness.emitOperatorStats(clientNode);
        verify(clientNode).trackOutputVariable(RuntimeVariable.LLM_Operator_Candidates_StaleTarget, 0L);
    }

    /** Builds a one-statement {@code new ArrayList<>(capacity)} test chromosome. */
    private static TestChromosome arrayListConstructorChromosome(int capacity) throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference capacityRef = test.addStatement(new IntPrimitiveStatement(test, capacity));
        GenericConstructor constructor = new GenericConstructor(
                ArrayList.class.getConstructor(int.class), ArrayList.class);
        test.addStatement(new ConstructorStatement(test, constructor,
                Collections.singletonList(capacityRef)));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        return chromosome;
    }

    /**
     * {@code DynaMOSA} harness that substitutes a caller-supplied (typically mocked) {@link
     * LlmOperatorExecutor} for {@link AbstractMOSA#createOperatorExecutor()}, exposes {@code
     * initializeLlmAssistance}/{@code shutdownLlmAssistance} for direct invocation, and stubs
     * fitness evaluation so an empty/near-empty candidate test case never has to run for real.
     */
    private static class OperatorExecutorHarness extends DynaMOSA {
        private static final long serialVersionUID = 1L;

        private final LlmOperatorExecutor stubExecutor;

        /** When set, {@link #calculateFitness} throws for this one candidate instead of no-op'ing. */
        private TestChromosome throwOnFitness;

        OperatorExecutorHarness(LlmOperatorExecutor stubExecutor) {
            super(TestChromosome::new);
            this.goalsManager = new MultiCriteriaManager(Collections.emptyList());
            this.stubExecutor = stubExecutor;
        }

        @Override
        protected LlmOperatorExecutor createOperatorExecutor() {
            return stubExecutor;
        }

        void callShutdownLlmAssistance() {
            shutdownLlmAssistance();
        }

        /** Sets the generation {@link #driveOperatorExecutor} reads via {@code currentIteration}. */
        void setCurrentIterationForTest(int generation) {
            this.currentIteration = generation;
        }

        @Override
        protected void calculateFitness(TestChromosome c) {
            if (c == throwOnFitness) {
                throw new IllegalStateException("simulated fitness evaluation failure");
            }
            // No-op otherwise: this harness only exercises wiring, never real coverage evaluation.
        }
    }
}
