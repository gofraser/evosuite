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
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * Verifies that {@link LlmSemanticMutation} and {@link LlmSemanticCrossover} make a
 * single LLM call per application and delegate repair of a malformed response to a
 * {@link TestRepairLoop} capped by {@link Properties#LLM_OPERATOR_REPAIR_ATTEMPTS}
 * rather than {@link Properties#LLM_REPAIR_ATTEMPTS}.
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
        when(llmService.hasBudget()).thenReturn(true);
        return llmService;
    }

    private TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    @Test
    void mutationWithNoRepairBudgetQueriesOnce() {
        // Deliberately set LLM_REPAIR_ATTEMPTS higher than LLM_OPERATOR_REPAIR_ATTEMPTS
        // to prove the operator honours its own (smaller) cap.
        Properties.LLM_REPAIR_ATTEMPTS = 3;
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;

        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MALFORMED_RESPONSE);

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        TestChromosome result = mutation.mutateSemantically(makeChromosome(), Collections.emptySet());

        assertNull(result, "a malformed response with no repair budget must not produce a mutant");
        verify(llmService, times(1)).query(any(PromptResult.class), any(LlmFeature.class));
        verify(llmService, never())
                .query(anyList(), any(LlmFeature.class), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void crossoverWithNoRepairBudgetQueriesOnce() {
        Properties.LLM_REPAIR_ATTEMPTS = 3;
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;

        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MALFORMED_RESPONSE);

        LlmSemanticCrossover crossover = new LlmSemanticCrossover(llmService);
        TestChromosome result = crossover.crossoverSemantically(
                makeChromosome(), makeChromosome(), Collections.emptySet());

        assertNull(result, "a malformed response with no repair budget must not produce a child");
        verify(llmService, times(1)).query(any(PromptResult.class), any(LlmFeature.class));
        verify(llmService, never())
                .query(anyList(), any(LlmFeature.class), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void mutationWithOneRepairAttemptRetriesOnce() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 1;

        LlmService llmService = mockAvailableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenReturn(MALFORMED_RESPONSE);
        when(llmService.query(anyList(), any(LlmFeature.class), anyInt(), anyBoolean(), anyList()))
                .thenReturn(MALFORMED_RESPONSE);

        LlmSemanticMutation mutation = new LlmSemanticMutation(llmService);
        TestChromosome result = mutation.mutateSemantically(makeChromosome(), Collections.emptySet());

        assertNull(result);
        verify(llmService, times(1)).query(any(PromptResult.class), any(LlmFeature.class));
        verify(llmService, times(1))
                .query(anyList(), any(LlmFeature.class), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void mutationRepairLoopIsCachedPerOperatorInstance() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticMutation mutation = new LlmSemanticMutation(mock(LlmService.class));

        TestRepairLoop first = mutation.repairLoop();
        TestRepairLoop second = mutation.repairLoop();

        assertNotNull(first);
        assertSame(first, second, "the repair loop must be built once and reused");
    }

    @Test
    void crossoverRepairLoopIsCachedPerOperatorInstance() {
        Properties.LLM_OPERATOR_REPAIR_ATTEMPTS = 0;
        LlmSemanticCrossover crossover = new LlmSemanticCrossover(mock(LlmService.class));

        TestRepairLoop first = crossover.repairLoop();
        TestRepairLoop second = crossover.repairLoop();

        assertNotNull(first);
        assertSame(first, second, "the repair loop must be built once and reused");
    }
}
