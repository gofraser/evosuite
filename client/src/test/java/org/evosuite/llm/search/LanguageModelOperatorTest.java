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
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.SyncTargetPlan;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
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
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODE = prevMode;
        Properties.LLM_MUTATION_PROBABILITY = prevMutProb;
        Properties.LLM_CROSSOVER_PROBABILITY = prevCrossProb;
    }

    private static TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
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
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome parent = makeChromosome();
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(mutationTarget(parent, makeGoal("g"))));

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertEquals(OperatorAttemptResult.SkipReason.DISABLED, result.getSkipReason());
        assertFalse(result.isAttemptedSemantic());
        assertEquals(1, plan.remaining(), "target must be untouched when the mode is off");
        verifyNoInteractions(mockSemantic);
    }

    @Test
    void probabilityGateRejectsBeforeTakingTarget() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 0.0;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        TestChromosome parent = makeChromosome();
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(mutationTarget(parent, makeGoal("g"))));

        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertEquals(OperatorAttemptResult.SkipReason.PROBABILITY, result.getSkipReason());
        assertEquals(1, plan.remaining(),
                "a rejected parent must keep its target for a later selection this generation");
        verifyNoInteractions(mockSemantic);
    }

    @Test
    void noTargetForParentSkipsWithNoTargetReason() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);

        OperatorAttemptResult result =
                mutation.tryMutateWithResult(makeChromosome(), makeChromosome(), SyncTargetPlan.EMPTY);

        assertEquals(OperatorAttemptResult.SkipReason.NO_TARGET, result.getSkipReason());
        verifyNoInteractions(mockSemantic);
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
        TestChromosome mutant = makeChromosome();
        mutant.setTestCase(new DefaultTestCase());

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutate(target)).thenReturn(mutant);

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);

        OperatorAttemptResult result = mutation.tryMutateWithResult(offspring, parent, plan);

        assertTrue(result.isAppliedSemantic());
        assertSame(target, result.getTarget());
        assertNotNull(result.getAttemptId());
        assertSame(mutant.getTestCase(), offspring.getTestCase());
        assertTrue(offspring.isChanged());
        assertEquals(1, mutation.getAppliedCount());
        assertEquals(0, mutation.getFallbackCount());
        assertEquals(0, plan.remaining(), "the target must be consumed on a successful attempt");
    }

    @Test
    void invokeReturningNullFallsBackAndRecordsNoGain() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        OperatorTarget target = mutationTarget(parent, makeGoal("g"));
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutate(target)).thenReturn(null);

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
        when(mockSemantic.mutate(target)).thenThrow(new RuntimeException("boom"));

        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(1, mutation.getFallbackCount());
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

        TestChromosome mutant = makeChromosome();
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutate(target)).thenReturn(mutant);

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        TestChromosome evaluatedOffspring = makeChromosome();
        evaluatedOffspring.setFitness(goal, 0.0);
        mutation.recordOutcome(result, evaluatedOffspring);

        verify(memory).recordAttemptOutcome(result.getAttemptId(), 1);
    }

    @Test
    void recordOutcomeRecordsNoGainWhenOffspringIsNull() {
        Properties.LLM_OPERATOR_MODE = Properties.LlmOperatorMode.SYNC;
        Properties.LLM_MUTATION_PROBABILITY = 1.0;

        TestChromosome parent = makeChromosome();
        TestFitnessFunction goal = makeGoal("g");
        OperatorTarget target = mutationTarget(parent, goal);
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        TestChromosome mutant = makeChromosome();
        LlmSemanticMutation mockSemantic = mock(LlmSemanticMutation.class);
        when(mockSemantic.mutate(target)).thenReturn(mutant);

        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LanguageModelMutation mutation = new LanguageModelMutation(mockSemantic, memory);
        OperatorAttemptResult result = mutation.tryMutateWithResult(makeChromosome(), parent, plan);

        mutation.recordOutcome(result, null);

        verify(memory).recordAttemptOutcome(result.getAttemptId(), 0);
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
        TestChromosome child = makeChromosome();
        child.setTestCase(new DefaultTestCase());

        LlmSemanticCrossover mockSemantic = mock(LlmSemanticCrossover.class);
        when(mockSemantic.crossover(crossover)).thenReturn(child);

        LanguageModelCrossover crossoverHook = new LanguageModelCrossover(mockSemantic);
        OperatorAttemptResult result = crossoverHook.tryCrossoverWithResult(offspring1, parent1, plan);

        assertTrue(result.isAppliedSemantic());
        assertSame(crossover, result.getTarget());
        assertSame(child.getTestCase(), offspring1.getTestCase());
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
        when(mockSemantic.crossover(target)).thenReturn(null);

        LanguageModelCrossover crossoverHook = new LanguageModelCrossover(mockSemantic);
        OperatorAttemptResult result = crossoverHook.tryCrossoverWithResult(makeChromosome(), parent1, plan);

        assertFalse(result.isAppliedSemantic());
        assertTrue(result.isFallbackUsed());
        assertEquals(1, crossoverHook.getFallbackCount());
    }
}
