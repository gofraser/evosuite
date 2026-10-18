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
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.GoalId;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link LlmTestChromosomeFactory} renders through {@link
 * org.evosuite.llm.prompt.TargetedPromptFactory} once a selector target is
 * available, and falls back to the generic goal-list prompt otherwise.
 */
class LlmTestChromosomeFactoryTest {

    /**
     * A response wrapped as a Java code block (so the parser's empty-fallback
     * class recovery does not silently turn it into a trivially "successful"
     * empty test) but containing a syntax error, so it never parses
     * successfully and always drives the repair-then-fail path.
     */
    private static final String UNPARSEABLE_RESPONSE =
            "```java\n"
                    + "public class GeneratedLlmTest {\n"
                    + "  @org.junit.Test\n"
                    + "  public void test() { this is not valid java syntax !!! }\n"
                    + "}\n"
                    + "```";

    private static final String PARSEABLE_RESPONSE =
            "```java\n"
                    + "public class GeneratedLlmTest {\n"
                    + "  @org.junit.Test\n"
                    + "  public void test_target_1() {\n"
                    + "    int one = 1;\n"
                    + "  }\n"
                    + "}\n"
                    + "```";

    private boolean originalLlmTestFactory;
    private double originalProbability;
    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalLlmTestFactory = Properties.LLM_TEST_FACTORY;
        originalProbability = Properties.LLM_TEST_FACTORY_PROBABILITY;
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.LLM_TEST_FACTORY = true;
        Properties.LLM_TEST_FACTORY_PROBABILITY = 1.0;
        Properties.TARGET_CLASS = "com.example.Foo";
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_TEST_FACTORY = originalLlmTestFactory;
        Properties.LLM_TEST_FACTORY_PROBABILITY = originalProbability;
        Properties.TARGET_CLASS = originalTargetClass;
    }

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        return chromosome;
    }

    private static LlmService mockAvailableService() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        return llmService;
    }

    private static String userMessageText(PromptResult prompt) {
        StringBuilder sb = new StringBuilder();
        for (org.evosuite.llm.LlmMessage message : prompt.getMessages()) {
            if (message.getRole() == org.evosuite.llm.LlmMessage.Role.USER) {
                sb.append(message.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void seedsTowardSelectorTargetWhenPopulationIsAvailable() {
        TestFitnessFunction goal = new StubGoal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(goal, 0.25);
        List<TestChromosome> population = Collections.singletonList(parent);

        LlmService llmService = mockAvailableService();
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);
        when(llmService.query(promptCaptor.capture(), any(LlmFeature.class))).thenReturn(UNPARSEABLE_RESPONSE);

        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        TestChromosome fallbackChromosome = chromosome(2);
        when(fallback.getChromosome()).thenReturn(fallbackChromosome);

        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(fallback, llmService, Collections::emptyList);
        factory.setSearchState(() -> Collections.singleton(goal), () -> population, () -> 1L, null);

        TestChromosome result = factory.getChromosome();

        assertSame(fallbackChromosome, result, "an unparseable response must fall back to the non-LLM factory");
        String userText = userMessageText(promptCaptor.getValue());
        assertTrue(userText.contains("Target #1"), "prompt must render through TargetedPromptFactory");
        assertTrue(userText.contains("fitness distance 0.2500"));
        assertTrue(userText.contains("Existing test #1"));
        assertTrue(userText.contains("start from it"), "must use TargetedPromptFactory.GENERATE_SENTENCE wording");
    }

    @Test
    void fallsBackToGenericPromptWhenNoPopulationIsWired() {
        TestFitnessFunction goal = new StubGoal("g");

        LlmService llmService = mockAvailableService();
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);
        when(llmService.query(promptCaptor.capture(), any(LlmFeature.class))).thenReturn(UNPARSEABLE_RESPONSE);

        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        TestChromosome fallbackChromosome = chromosome(2);
        when(fallback.getChromosome()).thenReturn(fallbackChromosome);

        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(
                fallback, llmService, () -> Collections.singleton(goal));
        // populationSupplier defaults to Collections::emptyList

        TestChromosome result = factory.getChromosome();

        assertSame(fallbackChromosome, result, "an unparseable response must fall back to the non-LLM factory");
        String userText = userMessageText(promptCaptor.getValue());
        assertTrue(userText.contains("Generate one JUnit test that is likely to improve coverage."));
        assertFalse(userText.contains("Target #1"));
    }

    private static LlmTestChromosomeFactory targetedFactory(LlmService llmService, RepeatedInjectionMemory memory,
                                                            TestFitnessFunction goal) {
        TestChromosome parent = chromosome(1);
        parent.setFitness(goal, 0.25);
        List<TestChromosome> population = Collections.singletonList(parent);
        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        when(fallback.getChromosome()).thenReturn(chromosome(2));
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(fallback, llmService, Collections::emptyList);
        factory.setSearchState(() -> Collections.singleton(goal), () -> population, () -> 1L, memory);
        return factory;
    }

    @Test
    void registersTheTargetAsAnAttemptAndRecordsNoGainWhenNothingParses() {
        TestFitnessFunction goal = new StubGoal("g");
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(UNPARSEABLE_RESPONSE);
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());

        targetedFactory(llmService, memory, goal).getChromosome();

        ArgumentCaptor<List<RepeatedInjectionTarget>> targets = ArgumentCaptor.forClass(List.class);
        verify(memory).registerAttempt(targets.capture(), eq(true));
        assertEquals(1, targets.getValue().size());
        assertTrue(targets.getValue().get(0).getKey().startsWith("operator|"));
        verify(memory).recordAttemptOutcome(any(String.class), eq(0));
        verify(memory, never()).releaseUndeliveredAttempt(any(String.class));
    }

    @Test
    void releasesTheAttemptWhenTheCallNeverWentOut() {
        TestFitnessFunction goal = new StubGoal("g");
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenThrow(new LlmBudgetExceededException("spent"));
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());

        targetedFactory(llmService, memory, goal).getChromosome();

        verify(memory).registerAttempt(any(List.class), eq(true));
        verify(memory).releaseUndeliveredAttempt(any(String.class));
        verify(memory, never()).recordAttemptOutcome(any(String.class), any(Integer.class));
    }

    @Test
    void parsedTestKeepsTheAttemptOpenUntilTheGaEvaluatesIt() {
        TestFitnessFunction goal = new StubGoal("g");
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(PARSEABLE_RESPONSE);
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LlmTestChromosomeFactory factory = targetedFactory(llmService, memory, goal);

        TestChromosome seeded = factory.getChromosome();

        assertTrue(seeded.getTestCase().size() > 0, "the parsed test must be returned, not the fallback");
        assertTrue(seeded.getFitnessValues().isEmpty(), "the factory must not execute the test itself");
        verify(memory).registerAttempt(any(List.class), eq(true));
        verify(memory, never()).recordAttemptOutcome(any(String.class), any(Integer.class));

        seeded.setFitness(goal, 0.0);
        factory.<TestChromosome>evaluationListener().fitnessEvaluation(seeded);

        verify(memory).recordAttemptOutcome(any(String.class), eq(1));
    }

    @Test
    void evaluationOfAnUnrelatedIndividualClosesNothing() {
        TestFitnessFunction goal = new StubGoal("g");
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(PARSEABLE_RESPONSE);
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        LlmTestChromosomeFactory factory = targetedFactory(llmService, memory, goal);
        factory.getChromosome();

        factory.<TestChromosome>evaluationListener().fitnessEvaluation(chromosome(9));

        verify(memory, never()).recordAttemptOutcome(any(String.class), any(Integer.class));
    }

    @Test
    void populatedSearchWhoseOnlyTargetIsInFlightUsesTheFallbackWithoutACall() {
        TestFitnessFunction goal = new StubGoal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(goal, 0.25); // targetable, but suppressed below
        List<TestChromosome> population = Collections.singletonList(parent);
        LlmService llmService = mockAvailableService();
        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        TestChromosome fallbackChromosome = chromosome(2);
        when(fallback.getChromosome()).thenReturn(fallbackChromosome);
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        memory.registerAttempt(Collections.singletonList(
                new RepeatedInjectionTarget("operator|" + GoalId.of(goal), "elsewhere")), true);
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(fallback, llmService, Collections::emptyList);
        factory.setSearchState(() -> Collections.singleton(goal), () -> population, () -> 1L, memory);

        assertSame(fallbackChromosome, factory.getChromosome());
        verify(llmService, never()).query(any(PromptResult.class), any(LlmFeature.class));
    }

    /**
     * A whole-suite search never stores per-goal fitness on its test chromosomes, so the
     * selector has nothing to target; the factory must still prompt (via the goal list)
     * rather than silently degrade to the non-LLM factory for the rest of the run.
     */
    @Test
    void populatedSearchWithNothingTargetableStillSendsTheGoalListPrompt() {
        TestFitnessFunction goal = new StubGoal("g");
        List<TestChromosome> population = Collections.singletonList(chromosome(1)); // no cached fitness
        LlmService llmService = mockAvailableService();
        ArgumentCaptor<PromptResult> promptCaptor = ArgumentCaptor.forClass(PromptResult.class);
        when(llmService.query(promptCaptor.capture(), any(LlmFeature.class))).thenReturn(UNPARSEABLE_RESPONSE);
        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        TestChromosome fallbackChromosome = chromosome(2);
        when(fallback.getChromosome()).thenReturn(fallbackChromosome);
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(fallback, llmService, Collections::emptyList);
        factory.setSearchState(() -> Collections.singleton(goal), () -> population, () -> 1L,
                new RepeatedInjectionMemory());

        assertSame(fallbackChromosome, factory.getChromosome());
        verify(llmService).query(any(PromptResult.class), any(LlmFeature.class));
        assertFalse(userMessageText(promptCaptor.getValue()).contains("Target #1"),
                "with nothing targetable the generic goal-list prompt is sent, not a targeted one");
    }

    @Test
    void goalListPromptRegistersNoAttempt() {
        TestFitnessFunction goal = new StubGoal("g");
        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(UNPARSEABLE_RESPONSE);
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        ChromosomeFactory<TestChromosome> fallback = mock(ChromosomeFactory.class);
        when(fallback.getChromosome()).thenReturn(chromosome(2));
        LlmTestChromosomeFactory factory = new LlmTestChromosomeFactory(fallback, llmService, Collections::emptyList);
        factory.setSearchState(() -> Collections.singleton(goal), Collections::emptyList, () -> 1L, memory);

        factory.getChromosome();

        // registerAttempt with no targets is the memory's own no-op; nothing to close afterwards
        verify(memory, never()).recordAttemptOutcome(any(String.class), any(Integer.class));
        verify(memory, never()).releaseUndeliveredAttempt(any(String.class));
    }

    /** Minimal goal stub with a cache-only fitness map, mirroring the pattern used across the llm test packages. */
    private static final class StubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;

        StubGoal(String name) {
            this.name = name;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return individual.getFitnessValues().getOrDefault(this, 1.0);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return other instanceof StubGoal ? name.compareTo(((StubGoal) other).name) : compareClassName(other);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof StubGoal && name.equals(((StubGoal) other).name));
        }

        @Override
        public String getTargetClass() {
            return "com.example.Foo";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }

        @Override
        public String toString() {
            return "StubGoal[" + name + "]";
        }
    }
}
