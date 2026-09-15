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
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link LlmSemanticOperator} (via its {@link LlmSemanticMutation} and
 * {@link LlmSemanticCrossover} subclasses) renders a batch of targets through
 * {@code TargetedPromptFactory}, makes a single LLM call, and maps the parsed
 * response back to targets by method name; and verifies {@link OperatorTarget#closestGoal}.
 */
class LlmSemanticOperatorTest {

    /** Two @Test methods, out of target order, each with one statement (an empty test would be dropped as a code-less response). */
    private static final String MULTI_TARGET_RESPONSE =
            "```java\n"
                    + "import org.junit.Test;\n"
                    + "public class GeneratedLlmTest {\n"
                    + "  @Test\n"
                    + "  public void test_target_2() {\n"
                    + "    int two = 2;\n"
                    + "  }\n"
                    + "  @Test\n"
                    + "  public void test_target_1() {\n"
                    + "    int one = 1;\n"
                    + "  }\n"
                    + "}\n"
                    + "```";

    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = "com.example.Foo";
    }

    @AfterEach
    void restoreProperties() {
        Properties.TARGET_CLASS = originalTargetClass;
    }

    private static LlmService mockAvailableService() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget()).thenReturn(true);
        return llmService;
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

    // --- apply(): batch of targets mapped back in target order -----------------

    @Test
    void applyMapsTwoMutationTargetsInTargetOrder() {
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MULTI_TARGET_RESPONSE);

        OperatorTarget target1 = OperatorTarget.builder(makeChromosome(), makeGoal("g1")).build();
        OperatorTarget target2 = OperatorTarget.builder(makeChromosome(), makeGoal("g2")).build();

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        TargetedResponse mapped = mutation.applyMapped(Arrays.asList(target1, target2));

        assertTrue(mapped.forTarget(1).isPresent(), "target 1 must be matched by test_target_1");
        assertTrue(mapped.forTarget(2).isPresent(), "target 2 must be matched by test_target_2");

        List<TestChromosome> result = mutation.apply(Arrays.asList(target1, target2));
        assertEquals(2, result.size());
        for (TestChromosome chromosome : result) {
            assertTrue(chromosome.isChanged());
        }
    }

    @Test
    void applyReturnsEmptyListForEmptyTargets() {
        LlmSemanticMutation mutation = new LlmSemanticMutation(mockAvailableService());
        assertTrue(mutation.apply(Collections.emptyList()).isEmpty());
    }

    @Test
    void applyReturnsEmptyListWhenServiceUnavailable() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(false);
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();
        assertTrue(mutation.apply(Collections.singletonList(target)).isEmpty());
    }

    // --- prompt content ----------------------------------------------------------

    @Test
    void mutationPromptContainsTargetHeadingAndStartFromWording() {
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MULTI_TARGET_RESPONSE);
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);

        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g")).build();
        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        mutation.apply(Collections.singletonList(target));

        verify(llmService).query(promptCaptor.capture(), any(LlmFeature.class));
        String userText = userText(promptCaptor.getValue());

        assertTrue(userText.contains("Target #1"), userText);
        assertTrue(userText.contains("Start from Existing test #1."), userText);
        assertTrue(userText.contains("changes as little as necessary"), userText);
    }

    @Test
    void crossoverPromptContainsCombineAndRecombineWording() {
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MULTI_TARGET_RESPONSE);
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);

        OperatorTarget target = OperatorTarget.builder(makeChromosome(), makeGoal("g"))
                .partner(makeChromosome()).build();
        LlmSemanticCrossover crossover = new LlmSemanticCrossover(llmService);
        crossover.apply(Collections.singletonList(target));

        verify(llmService).query(promptCaptor.capture(), any(LlmFeature.class));
        String userText = userText(promptCaptor.getValue());

        assertTrue(userText.contains("Combine Existing test #1 (A) with Existing test #2 (B)."), userText);
        assertTrue(userText.contains("combines the setup from"), userText);
    }

    @Test
    void mutateSemanticallySelectsCachedGoalAndReportsItsDistance() {
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MULTI_TARGET_RESPONSE);
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);

        TestChromosome parent = makeChromosome();
        TestFitnessFunction g = makeGoal("g");
        parent.setFitness(g, 0.25);

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        mutation.mutateSemantically(parent, Collections.singletonList(g));

        verify(llmService).query(promptCaptor.capture(), any(LlmFeature.class));
        String userText = userText(promptCaptor.getValue());

        assertTrue(userText.contains("fitness distance 0.2500"), userText);
    }

    private static String userText(PromptResult prompt) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage message : prompt.getMessages()) {
            if (message.getRole() == LlmMessage.Role.USER) {
                sb.append(message.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    // --- OperatorTarget.closestGoal --------------------------------------------

    @Test
    void closestGoalPicksSmallestPositiveCachedFitness() {
        TestChromosome parent = makeChromosome();
        TestFitnessFunction near = makeGoal("near");
        TestFitnessFunction far = makeGoal("far");
        parent.setFitness(far, 0.8);
        parent.setFitness(near, 0.2);

        OperatorTarget target = OperatorTarget.closestGoal(parent, null, Arrays.asList(near, far));

        assertNotNull(target);
        assertEquals(near, target.getGoal());
        assertEquals(0.2, target.getDistance(), 1e-9);
    }

    @Test
    void closestGoalIgnoresZeroFitnessGoals() {
        TestChromosome parent = makeChromosome();
        TestFitnessFunction covered = makeGoal("covered");
        TestFitnessFunction uncovered = makeGoal("uncovered");
        parent.setFitness(covered, 0.0);
        parent.setFitness(uncovered, 0.4);

        OperatorTarget target = OperatorTarget.closestGoal(parent, null, Arrays.asList(covered, uncovered));

        assertNotNull(target);
        assertEquals(uncovered, target.getGoal());
        assertEquals(0.4, target.getDistance(), 1e-9);
    }

    @Test
    void closestGoalFallsBackToRandomGoalWithNaNDistanceWhenNothingCached() {
        TestChromosome parent = makeChromosome();
        TestFitnessFunction a = makeGoal("a");
        TestFitnessFunction b = makeGoal("b");

        OperatorTarget target = OperatorTarget.closestGoal(parent, null, Arrays.asList(a, b));

        assertNotNull(target);
        assertTrue(target.getGoal() == a || target.getGoal() == b);
        assertTrue(Double.isNaN(target.getDistance()));
    }

    @Test
    void closestGoalReturnsNullForEmptyGoals() {
        assertNull(OperatorTarget.closestGoal(makeChromosome(), null, Collections.emptyList()));
    }

    @Test
    void closestGoalReturnsNullForNullParent() {
        assertNull(OperatorTarget.closestGoal(null, null, Collections.singletonList(makeGoal("g"))));
    }
}
