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
import org.evosuite.llm.LlmService;
import org.evosuite.llm.QueryResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.evosuite.llm.LlmRequestMatchers.text;
import static org.evosuite.llm.LlmRequestMatchers.anyRepairRequest;
import static org.evosuite.llm.LlmRequestMatchers.anyInitialRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link LlmSemanticMutation} and {@link LlmSemanticCrossover} make a
 * single LLM call per application and delegate repair of a malformed response to a
 * {@link TestRepairLoop} capped by {@link Properties#LLM_OPERATOR_REPAIR_ATTEMPTS}
 * rather than {@link Properties#LLM_REPAIR_ATTEMPTS}.
 *
 * <p>Also verifies the memoisation of {@code repairLoop()}: each operator instance builds
 * one {@link TestRepairLoop} and shares it between threads, since {@link
 * TestRepairLoop#attemptParse} keeps its conversation state per call.
 */
class LlmSemanticOperatorRepairTest {

    /**
     * A response that is wrapped as a Java code block (so it is not silently replaced
     * by the parser's empty-fallback class) but contains a syntax error, so it never
     * parses successfully and always drives the repair path.
     */
    private static final String MALFORMED_RESPONSE =
            "```java\n"
                    + "public class GeneratedLlmTest {\n"
                    + "  @org.junit.Test\n"
                    + "  public void test() { this is not valid java syntax !!! }\n"
                    + "}\n"
                    + "```";

    private int originalLlmRepairAttempts;
    private int originalOperatorRepairAttempts;
    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalLlmRepairAttempts = Properties.LLM_REPAIR_ATTEMPTS;
        originalOperatorRepairAttempts = Properties.LLM_OPERATOR_REPAIR_ATTEMPTS;
        originalTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_REPAIR_ATTEMPTS = originalLlmRepairAttempts;
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = originalOperatorRepairAttempts;
        Properties.TARGET_CLASS = originalTargetClass;
    }

    private LlmService mockAvailableService() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        return llmService;
    }

    private TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private TestFitnessFunction makeGoal() {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal");
        return goal;
    }

    private OperatorTarget mutationTarget() {
        return OperatorTarget.builder(makeChromosome(), makeGoal()).build();
    }

    private OperatorTarget crossoverTarget() {
        return OperatorTarget.builder(makeChromosome(), makeGoal()).partner(makeChromosome()).build();
    }

    @Test
    void mutationWithNoRepairBudgetQueriesOnce() {
        // Deliberately set LLM_REPAIR_ATTEMPTS higher than LLM_OPERATOR_REPAIR_ATTEMPTS
        // to prove the operator honours its own (smaller) cap.
        Properties.LLM_REPAIR_ATTEMPTS = 3;
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;

        LlmService llmService = mockAvailableService();
        when(llmService.query(anyInitialRequest()))
                .thenReturn(new QueryResult(MALFORMED_RESPONSE, 0, 0, 0, 0L));

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        TestCase result = mutation.applyMapped(Collections.singletonList(mutationTarget())).forTarget(1).orElse(null);

        assertNull(result, "a malformed response with no repair budget must not produce a mutant");
        verify(llmService, times(1)).query(anyInitialRequest());
        verify(llmService, never())
                .query(anyRepairRequest());
    }

    @Test
    void crossoverWithNoRepairBudgetQueriesOnce() {
        Properties.LLM_REPAIR_ATTEMPTS = 3;
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;

        LlmService llmService = mockAvailableService();
        when(llmService.query(anyInitialRequest()))
                .thenReturn(new QueryResult(MALFORMED_RESPONSE, 0, 0, 0, 0L));

        LlmSemanticCrossover crossover = new LlmSemanticCrossover(llmService);
        TestCase result = crossover.applyMapped(Collections.singletonList(crossoverTarget())).forTarget(1).orElse(null);

        assertNull(result, "a malformed response with no repair budget must not produce a child");
        verify(llmService, times(1)).query(anyInitialRequest());
        verify(llmService, never())
                .query(anyRepairRequest());
    }

    @Test
    void mutationWithOneRepairAttemptRetriesOnce() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 1;

        LlmService llmService = mockAvailableService();
        when(llmService.query(anyInitialRequest()))
                .thenReturn(new QueryResult(MALFORMED_RESPONSE, 0, 0, 0, 0L));
        when(llmService.query(anyRepairRequest()))
                .thenReturn(text(MALFORMED_RESPONSE));

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        TestCase result = mutation.applyMapped(Collections.singletonList(mutationTarget())).forTarget(1).orElse(null);

        assertNull(result);
        verify(llmService, times(1)).query(anyInitialRequest());
        verify(llmService, times(1))
                .query(anyRepairRequest());
    }

    @Test
    void mutationRepairLoopIsReused() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticMutation mutation = new LlmSemanticMutation(mock(LlmService.class));

        TestRepairLoop first = mutation.repairLoop();
        TestRepairLoop second = mutation.repairLoop();

        assertNotNull(first);
        assertSame(first, second, "repeated calls must get the same repair loop back");
    }

    @Test
    void crossoverRepairLoopIsReused() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticCrossover crossover = new LlmSemanticCrossover(mock(LlmService.class));

        TestRepairLoop first = crossover.repairLoop();
        TestRepairLoop second = crossover.repairLoop();

        assertNotNull(first);
        assertSame(first, second, "repeated calls must get the same repair loop back");
    }

    @Test
    void mutationRepairLoopIsSharedAcrossThreads() throws InterruptedException {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticMutation mutation = new LlmSemanticMutation(mock(LlmService.class));

        TestRepairLoop onThisThread = mutation.repairLoop();

        AtomicReference<TestRepairLoop> onOtherThread = new AtomicReference<>();
        Thread other = new Thread(() -> onOtherThread.set(mutation.repairLoop()));
        other.start();
        other.join();

        assertNotNull(onOtherThread.get());
        assertSame(onThisThread, onOtherThread.get(),
                "all threads must share the operator's repair loop");
    }

    @Test
    void mutationRepairLoopIsParseOnly() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticMutation mutation = new LlmSemanticMutation(mock(LlmService.class));

        boolean executeParsedTests = mutation.repairLoop().getRepairOptions().isExecuteParsedTests();

        assertFalse(executeParsedTests,
                "an operator's repair loop runs on a background worker and must not "
                        + "execute candidates itself; the GA evaluates every drained candidate");
    }

    @Test
    void crossoverRepairLoopIsParseOnly() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticCrossover crossover = new LlmSemanticCrossover(mock(LlmService.class));

        boolean executeParsedTests = crossover.repairLoop().getRepairOptions().isExecuteParsedTests();

        assertFalse(executeParsedTests,
                "an operator's repair loop runs on a background worker and must not "
                        + "execute candidates itself; the GA evaluates every drained candidate");
    }

    @Test
    void crossoverRepairLoopIsSharedAcrossThreads() throws InterruptedException {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticCrossover crossover = new LlmSemanticCrossover(mock(LlmService.class));

        TestRepairLoop onThisThread = crossover.repairLoop();

        AtomicReference<TestRepairLoop> onOtherThread = new AtomicReference<>();
        Thread other = new Thread(() -> onOtherThread.set(crossover.repairLoop()));
        other.start();
        other.join();

        assertNotNull(onOtherThread.get());
        assertSame(onThisThread, onOtherThread.get(),
                "all threads must share the operator's repair loop");
    }
}
