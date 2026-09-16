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

import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link LlmSemanticOperator#applyMapped} is exactly
 * {@code complete(prepare(targets), targets.size())} once the empty/null and
 * canCall() gates are cleared, and that those gates skip {@code prepare}
 * (and thus the LLM call) entirely.
 */
class LlmSemanticOperatorPrepareCompleteTest {

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

    @Test
    void applyMappedIsEmptyForNullOrEmptyTargetsWithoutCallingTheService() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);

        assertTrue(mutation.applyMapped(null).isEmpty());
        assertTrue(mutation.applyMapped(Collections.emptyList()).isEmpty());
        verify(llmService, never()).queryWithUsage(any(PromptResult.class), any(LlmFeature.class));
    }

    @Test
    void applyMappedIsEmptyWhenUnavailableWithoutCallingPrepare() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(false);
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();

        TargetedResponse response = mutation.applyMapped(Collections.singletonList(target));
        assertTrue(response.isEmpty());
        assertEquals(TargetedResponse.Status.NO_BUDGET, response.getStatus());
        verify(llmService, never()).queryWithUsage(any(PromptResult.class), any(LlmFeature.class));
    }

    @Test
    void completeReturnsNoBudgetWhenCanCallIsFalse() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(false);
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();

        TargetedResponse response = mutation.complete(mutation.prepare(Collections.singletonList(target)), 1);
        assertTrue(response.isEmpty());
        assertEquals(TargetedResponse.Status.NO_BUDGET, response.getStatus());
    }

    @Test
    void completeReturnsNoBudgetWhenTheServiceThrowsBudgetExceeded() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(llmService.queryWithUsage(any(PromptResult.class), any(LlmFeature.class)))
                .thenThrow(new LlmBudgetExceededException("no budget left"));
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();

        TargetedResponse response = mutation.complete(mutation.prepare(Collections.singletonList(target)), 1);
        assertTrue(response.isEmpty());
        assertEquals(TargetedResponse.Status.NO_BUDGET, response.getStatus());
    }

    @Test
    void completeReturnsFailedWhenTheServiceThrowsCallFailed() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(llmService.queryWithUsage(any(PromptResult.class), any(LlmFeature.class)))
                .thenThrow(new LlmCallFailedException("boom", null, false));
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();

        TargetedResponse response = mutation.complete(mutation.prepare(Collections.singletonList(target)), 1);
        assertTrue(response.isEmpty());
        assertEquals(TargetedResponse.Status.FAILED, response.getStatus());
    }

    @Test
    void completeReturnsFailedOnAnyOtherRuntimeException() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(llmService.queryWithUsage(any(PromptResult.class), any(LlmFeature.class)))
                .thenThrow(new IllegalStateException("unexpected"));
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();

        TargetedResponse response = mutation.complete(mutation.prepare(Collections.singletonList(target)), 1);
        assertTrue(response.isEmpty());
        assertEquals(TargetedResponse.Status.FAILED, response.getStatus());
    }

    @Test
    void applyMappedMatchesCompleteOfPrepareOnceGatesAreClear() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        when(llmService.queryWithUsage(any(PromptResult.class), any(LlmFeature.class))).thenReturn(
                new LlmService.QueryResult(
                        "```java\n"
                                + "import org.junit.Test;\n"
                                + "public class GeneratedLlmTest {\n"
                                + "  @Test\n"
                                + "  public void test_target_1() {\n"
                                + "    int one = 1;\n"
                                + "  }\n"
                                + "}\n"
                                + "```",
                        0, 0, 0, 0L));

        List<OperatorTarget> targets = Collections.singletonList(
                OperatorTarget.builder(makeChromosome(), makeGoal("g")).build());

        LlmSemanticMutation viaApplyMapped = new LlmSemanticMutation(llmService);
        TargetedResponse fromApplyMapped = viaApplyMapped.applyMapped(targets);

        LlmSemanticMutation viaPrepareComplete = new LlmSemanticMutation(llmService);
        TargetedResponse fromPrepareComplete =
                viaPrepareComplete.complete(viaPrepareComplete.prepare(targets), targets.size());

        assertTrue(fromApplyMapped.forTarget(1).isPresent());
        assertTrue(fromPrepareComplete.forTarget(1).isPresent());
        assertEquals(TargetedResponse.Status.DELIVERED, fromApplyMapped.getStatus());
        assertEquals(TargetedResponse.Status.DELIVERED, fromPrepareComplete.getStatus());
    }

    @Test
    void prepareRejectsEmptyTargetsAsTheFactoryDoes() {
        LlmSemanticMutation mutation = new LlmSemanticMutation(mock(LlmService.class));
        assertThrows(IllegalArgumentException.class, () -> mutation.prepare(Collections.emptyList()));
    }

    @Test
    void completeDeferringExpansionFlagsResolutionErrorsAndReparseFinishesWithoutAnotherCall() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        String response =
                "```java\n"
                        + "import org.junit.Test;\n"
                        + "public class GeneratedLlmTest {\n"
                        + "  @Test\n"
                        + "  public void test_target_1() {\n"
                        + "    int result = undefinedVariableXyz123;\n"
                        + "  }\n"
                        + "}\n"
                        + "```";
        when(llmService.queryWithUsage(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(new LlmService.QueryResult(response, 1, 2, 3, 4L));

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        List<OperatorTarget> targets = Collections.singletonList(
                OperatorTarget.builder(makeChromosome(), makeGoal("g")).build());
        PromptResult prompt = mutation.prepare(targets);

        TargetedResponse deferred = mutation.completeDeferringExpansion(prompt, targets.size());

        assertTrue(deferred.needsClusterExpansion(),
                "an unresolved-symbol response must be handed back for the search thread to expand");
        assertEquals(response, deferred.getRawResponse());
        assertFalse(deferred.forTarget(1).isPresent());

        TargetedResponse finished = mutation.reparseWithExpansion(prompt, deferred.getRawResponse(),
                targets.size(), deferred.getUsage());

        assertFalse(finished.needsClusterExpansion());
        assertEquals(deferred.getUsage().getInputTokens(), finished.getUsage().getInputTokens(),
                "the original call's usage must be reused, since no new call is made");
        verify(llmService, times(1)).queryWithUsage(any(PromptResult.class), any(LlmFeature.class));
        verify(llmService, never())
                .query(anyList(), any(LlmFeature.class), anyInt(), anyBoolean(), anyList());
    }
}
