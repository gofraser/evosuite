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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.SyncTargetPlan;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testparser.ParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link LanguageModelMutation} and {@link LanguageModelCrossover}: the SYNC hooks take
 * their target from a {@link SyncTargetPlan} rather than choosing a goal themselves.
 */
class LanguageModelOperatorTest {

    private Properties.LlmOperatorMode prevMode;
    private double prevMutProb;
    private double prevCrossProb;

    @BeforeEach
    void saveProperties() {
        prevMode = Properties.LLM_OPERATOR_MODE;
        prevMutProb = Properties.LLM_MUTATION_PROBABILITY;
        prevCrossProb = Properties.LLM_CROSSOVER_PROBABILITY;
        OperatorAttemptLog.resetInstance();
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODE = prevMode;
        Properties.LLM_MUTATION_PROBABILITY = prevMutProb;
        Properties.LLM_CROSSOVER_PROBABILITY = prevCrossProb;
        OperatorAttemptLog.resetInstance();
    }

    private static TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    /** A delivered, successfully parsed response matching target 1 with {@code test}. */
    private static TargetedResponse matchedResponse(TestCase test) {
        ParseResult parsed = new ParseResult(test, TargetedPromptFactory.testMethodName(1));
        RepairResult repair = RepairResult.success(Collections.singletonList(parsed),
                Collections.<String>emptyList(), 1, Collections.<String>emptyList());
        return TargetedResponse.map(repair, 1);
    }

    /** A non-empty test case: {@link TargetedResponse#map} drops empty ones as unusable. */
    private static TestCase nonEmptyTestCase() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 0));
        return test;
    }

    /** A delivered response that matches no target (the semantic call produced nothing usable). */
    private static TargetedResponse unmatchedResponse() {
        RepairResult repair = RepairResult.success(Collections.<ParseResult>emptyList(),
                Collections.<String>emptyList(), 1, Collections.<String>emptyList());
        return TargetedResponse.map(repair, 1);
    }

    private static TestFitnessFunction makeGoal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn(name);
        return goal;
    }

    private static OperatorTarget mutationTarget(TestChromosome parent, TestFitnessFunction goal) {
        return OperatorTarget.builder(parent, goal).distance(0.5).build();
    }

    private static OperatorTarget crossoverTarget(TestChromosome parent, TestFitnessFunction goal,
                                                  TestChromosome partner) {
        return OperatorTarget.builder(parent, goal).distance(0.5).partner(partner).build();
    }

    // --- mode / probability / target gating (shared shape, exercised via mutation) --------------

    @Test
    void modeOffSkipsAndNeverTouchesSemanticOrPlan() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.OFF;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome parent = makeChromosome();
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(mutationTarget(parent, makeGoal("g"))));

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertEquals(OperatorAttemptResult.SkipReason.DISABLED, result.getSkipReason());
        assertFalse(result.isAttemptedSemantic());
        assertEquals(1, plan.remaining(), "target must be untouched when the mode is off");
        verify(mockSemantic, never()).applyMapped(anyList());
    }

    @Test
    void probabilityGateRejectsBeforeTakingTarget() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 0.0;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome parent = makeChromosome();
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(mutationTarget(parent, makeGoal("g"))));

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertEquals(OperatorAttemptResult.SkipReason.PROBABILITY, result.getSkipReason());
        assertEquals(1, plan.remaining(),
                "a rejected parent must keep its target for a later selection this generation");
        verify(mockSemantic, never()).applyMapped(anyList());
    }

    @Test
    void spentBudgetSkipsBeforeTakingTarget() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(false);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome parent = makeChromosome();
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(mutationTarget(parent, makeGoal("g"))));

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertEquals(OperatorAttemptResult.SkipReason.NO_BUDGET, result.getSkipReason());
        assertFalse(result.isAttemptedSemantic());
        assertEquals(1, plan.remaining());
        assertEquals(0, mutation.getFallbackCount(), "a skipped call is not a fallback");
        verify(mockSemantic, never()).applyMapped(anyList());
    }

    @Test
    void noTargetForParentSkipsWithNoTargetReason() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        OperatorAttemptResult result =
                mutation.tryMutateWithResult(makeChromosome(), makeChromosome(), SyncTargetPlan.EMPTY);

        assertEquals(OperatorAttemptResult.SkipReason.NO_TARGET, result.getSkipReason());
        verify(mockSemantic, never()).applyMapped(anyList());
    }

    // --- mutation: applied / fallback / exception -------------------------------------------

    @Test
    void appliedMutationReplacesOffspringAndCarriesTargetAndAttemptId() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        TestChromosome offspring = makeChromosome();
        TestCase mutantTest = nonEmptyTestCase();

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(matchedResponse(mutantTest));

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);

        OperatorAttemptResult result = mutation.tryMutateWithResult(offspring, parent, plan);

        assertTrue(result.isAppliedSemantic());
        assertSame(target, result.getTarget());
        assertNotNull(result.getAttemptId());
        assertSame(mutantTest, offspring.getTestCase());
        assertTrue(offspring.isChanged());
        assertEquals(1, mutation.getAppliedCount());
        assertEquals(0, mutation.getFallbackCount());
        assertEquals(0, plan.remaining(), "the target must be consumed on a successful attempt");

        // The SYNC attempt log row reflects the applied call: mode, PARSED status, and (once
        // the GA evaluates the offspring) the target distance and admission.
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        assertEquals(1, log.getAttempts());
        assertEquals(1, log.getOffspringParsed());
        offspring.setFitness(target.getGoal(), 0.0);
        mutation.recordOutcome(result, offspring);
        assertEquals(1, log.getOffspringAdmitted());
        assertEquals(1, log.getTargetHits());
    }

    @Test
    void invokeReturningNullFallsBackAndRecordsNoGain() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(unmatchedResponse());

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(0, mutation.getAppliedCount());
        assertEquals(1, mutation.getFallbackCount());
        verify(memory).recordAttemptOutcome(anyString(), eq(0));
    }

    @Test
    void mutationExceptionFallsBackGracefully() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenThrow(new RuntimeException("boom"));

        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(1, mutation.getFallbackCount());
    }

    // --- fallback releases vs. records a repeat-memory attempt, by response status ------------

    @Test
    void noBudgetResponseReleasesTheAttemptInsteadOfRecordingAGainlessOutcome() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(TargetedResponse.noBudget());

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        // The target never reached a completed turn, so its repeat-suppression cooldown must
        // not grow — same treatment the ASYNC executor gives an undelivered attempt.
        verify(memory).releaseUndeliveredAttempt(anyString());
        verify(memory, never()).recordAttemptOutcome(anyString(), anyInt());
    }

    @Test
    void failedResponseReleasesTheAttemptInsteadOfRecordingAGainlessOutcome() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(TargetedResponse.failed());

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        verify(memory).releaseUndeliveredAttempt(anyString());
        verify(memory, never()).recordAttemptOutcome(anyString(), anyInt());
    }

    @Test
    void callExceptionReleasesTheAttemptInsteadOfRecordingAGainlessOutcome() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenThrow(new RuntimeException("boom"));

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        verify(memory).releaseUndeliveredAttempt(anyString());
        verify(memory, never()).recordAttemptOutcome(anyString(), anyInt());
    }

    @Test
    void deliveredButUnmatchedResponseStillRecordsAGainlessOutcome() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(unmatchedResponse());

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        // The call actually completed a turn against this target, it just produced nothing
        // usable — that counts as a real, gainless attempt, not a released non-delivery.
        verify(memory).recordAttemptOutcome(anyString(), eq(0));
        verify(memory, never()).releaseUndeliveredAttempt(anyString());
    }

    // --- recordOutcome -------------------------------------------------------------------------

    @Test
    void recordOutcomeCreditsGainWhenEvaluatedOffspringCoversTheGoal() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        TestFitnessFunction goal = makeGoal("g");
        OperatorTarget target = mutationTarget(parent, goal);
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target)))
                .thenReturn(matchedResponse(nonEmptyTestCase()));

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        TestChromosome evaluatedOffspring = makeChromosome();
        evaluatedOffspring.setFitness(goal, 0.0);
        mutation.recordOutcome(result, evaluatedOffspring);

        verify(memory).recordAttemptOutcome(result.getAttemptId(), 1);
        OperatorAttemptLog log = OperatorAttemptLog.getInstance();
        assertEquals(1, log.getOffspringAdmitted());
        assertEquals(1, log.getTargetHits());
    }

    @Test
    void recordOutcomeRecordsNoGainWhenOffspringIsNull() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        TestFitnessFunction goal = makeGoal("g");
        OperatorTarget target = mutationTarget(parent, goal);
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target)))
                .thenReturn(matchedResponse(nonEmptyTestCase()));

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        mutation.recordOutcome(result, null);

        verify(memory).recordAttemptOutcome(result.getAttemptId(), 0);
        assertEquals(0, OperatorAttemptLog.getInstance().getOffspringAdmitted(),
                "a dropped (null) offspring must not be recorded as admitted");
    }

    // --- crossover: only takes CROSSOVER-kind targets --------------------------------------------

    @Test
    void crossoverTakesOnlyCrossoverKindTargets() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;

        TestChromosome parent1 = makeChromosome();
        TestChromosome mutationOnlyParent = makeChromosome();
        // A mutation-kind target for a different parent must be left untouched.
        OperatorTarget mutationOnly = mutationTarget(mutationOnlyParent, makeGoal("m"));
        OperatorTarget crossover = crossoverTarget(parent1, makeGoal("g"), makeChromosome());
        SyncTargetPlan plan = new SyncTargetPlan(java.util.Arrays.asList(mutationOnly, crossover));

        TestChromosome offspring1 = makeChromosome();
        TestCase childTest = nonEmptyTestCase();

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(crossover))).thenReturn(matchedResponse(childTest));

        LanguageModelCrossover crossoverHook = new LanguageModelCrossover(mockSemantic);
        OperatorAttemptResult result = crossoverHook.tryCrossoverWithResult(offspring1, parent1, plan);

        assertTrue(result.isAppliedSemantic());
        assertSame(crossover, result.getTarget());
        assertSame(childTest, offspring1.getTestCase());
        assertEquals(1, crossoverHook.getAppliedCount());
        assertEquals(1, plan.remaining(), "the untouched mutation-kind target must remain in the plan");

        OperatorAttemptResult secondAttempt =
                crossoverHook.tryCrossoverWithResult(makeChromosome(), mutationOnlyParent, plan);
        assertEquals(OperatorAttemptResult.SkipReason.NO_TARGET, secondAttempt.getSkipReason(),
                "a mutation-kind target must never be handed to the crossover hook");
    }

    @Test
    void crossoverFailureFallsBack() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_CROSSOVER_PROBABILITY = 1.0;

        TestChromosome parent1 = makeChromosome();
        OperatorTarget target = crossoverTarget(parent1, makeGoal("g"), makeChromosome());
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.canCall()).thenReturn(true);
        when(mockSemantic.applyMapped(Collections.singletonList(target))).thenReturn(unmatchedResponse());

        LanguageModelCrossover crossoverHook = new LanguageModelCrossover(mockSemantic);
        OperatorAttemptResult result = crossoverHook.tryCrossoverWithResult(makeChromosome(), parent1, plan);

        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(1, crossoverHook.getFallbackCount());
    }
}
