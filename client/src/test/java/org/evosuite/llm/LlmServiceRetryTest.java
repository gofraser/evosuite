package org.evosuite.llm;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceRetryTest {

    @Test
    void retries429ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("HTTP 429");
            }
            return new LlmService.LlmResponse("ok", 11, 7);
        };

        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                2,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "run-1");

        LlmStatistics statistics = new LlmStatistics();
        LlmBudgetCoordinator.Local budget = new LlmBudgetCoordinator.Local(2);
        LlmService service = new LlmService(model,
                budget,
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            String output = service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);

            assertEquals("ok", output);
            assertEquals(2, calls.get());
            assertEquals(1, statistics.getSuccessfulCalls());
            assertEquals(0, statistics.getFailedCalls());
            assertEquals(0, budget.getRemaining());
        } finally {
            service.close();
        }
    }

    @Test
    void nonRetryableFailureDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            calls.incrementAndGet();
            throw new RuntimeException("invalid api key");
        };

        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                3,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "run-2");

        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(1),
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            LlmCallFailedException thrown = assertThrows(LlmCallFailedException.class,
                    () -> service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));

            assertFalse(thrown.isRetryable());
            assertEquals(1, calls.get());
            assertEquals(1, statistics.getFailedCalls());
        } finally {
            service.close();
        }
    }

    @Test
    void retryConsumesBudgetPerProviderAttempt() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("HTTP 429");
            }
            return new LlmService.LlmResponse("ok", 0, 0);
        };

        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                2,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "run-3");

        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(1),
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            LlmBudgetExceededException thrown = assertThrows(LlmBudgetExceededException.class,
                    () -> service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));

            assertTrue(thrown.getMessage().contains("attempt 2"));
            assertEquals(1, calls.get());
        } finally {
            service.close();
        }
    }

    @Test
    void retryClassificationUnwrapsNestedTimeoutCause() {
        RuntimeException wrapped = new RuntimeException("wrapper", new RuntimeException(new TimeoutException("slow")));
        assertTrue(LlmService.isRetryable(wrapped));
    }

    @Test
    void retryClassificationDetectsNestedNonRetryableCause() {
        RuntimeException wrapped = new RuntimeException("wrapper", new IllegalArgumentException("bad request"));
        assertFalse(LlmService.isRetryable(wrapped));
    }
}
