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
package org.evosuite.llm;

import org.evosuite.llm.prompt.PromptResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link LlmService#queryWithUsage(PromptResult, LlmFeature)}
 * exposes the same token usage and latency that is otherwise only recorded
 * into {@link LlmStatistics} and the trace file, with the same call/budget/
 * retry semantics as {@link LlmService#query(PromptResult, LlmFeature)}.
 */
class LlmServiceQueryWithUsageTest {

    @Test
    void returnsTextTokenUsageAndNonNegativeLatency() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            calls.incrementAndGet();
            return new LlmService.LlmResponse("ok", 11, 3, 7);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 0, 1,
                false, Paths.get("target/llm-test-traces"), "usage-test");
        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(1), configuration,
                statistics, new LlmTraceRecorder(configuration));

        PromptResult prompt = new PromptResult.Builder()
                .messages(Collections.singletonList(LlmMessage.user("generate")))
                .build();

        try {
            LlmService.QueryResult result = service.queryWithUsage(prompt, LlmFeature.SEMANTIC_MUTATION);

            assertEquals("ok", result.getText());
            assertEquals(11, result.getInputTokens());
            assertEquals(3, result.getCachedInputTokens());
            assertEquals(7, result.getOutputTokens());
            assertTrue(result.getLatencyMs() >= 0L);
            assertEquals(1, calls.get());
            assertEquals(1, statistics.getSuccessfulCalls());
            assertEquals(11, statistics.getInputTokens());
            assertEquals(3, statistics.getCachedInputTokens());
            assertEquals(7, statistics.getOutputTokens());
        } finally {
            service.close();
        }
    }

    @Test
    void behavesLikeQueryOnceRetriesSucceed() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("HTTP 429");
            }
            return new LlmService.LlmResponse("retried", 5, 2);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 2, 1,
                true, Paths.get("target/llm-test-traces"), "usage-retry-test");
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(2), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        PromptResult prompt = new PromptResult.Builder()
                .messages(Collections.singletonList(LlmMessage.user("generate")))
                .build();

        try {
            LlmService.QueryResult result = service.queryWithUsage(prompt, LlmFeature.SEMANTIC_CROSSOVER);

            assertEquals("retried", result.getText());
            assertEquals(5, result.getInputTokens());
            assertEquals(0, result.getCachedInputTokens());
            assertEquals(2, result.getOutputTokens());
            assertEquals(2, calls.get());
        } finally {
            service.close();
        }
    }
}
