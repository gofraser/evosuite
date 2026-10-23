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

import org.evosuite.llm.provider.LlmHttpException;
import org.evosuite.llm.provider.LlmProviderErrorException;
import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmServiceRetryTest {

    @TempDir
    Path tempDir;

    @Test
    void requestWorkerIsPrivilegedWhenSandboxIsAlreadyActive() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        try {
            Sandbox.initializeSecurityManagerForSUT();
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("Security Manager is unavailable on this JVM");
        }
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            // SocketOutputStream construction performs the same
            // writeFileDescriptor check as this constructor.
            new FileOutputStream(FileDescriptor.out);
            return new LlmResponse("ok", 0, 0);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 0, 1,
                false, Paths.get("target/llm-test-traces"), "sandbox-worker");
        LlmService service = new LlmService(model,
                LlmBudgetCoordinator.of(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));
        try {
            assertEquals("ok", LlmServiceTestQueries.query(service, 
                    Collections.singletonList(LlmMessage.user("generate")),
                    LlmFeature.TEST_REPAIR));
        } finally {
            service.close();
            Sandbox.resetDefaultSecurityManager();
        }
    }

    @Test
    void retries429ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("HTTP 429");
            }
            return new LlmResponse("ok", 11, 7);
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
                true,
                tempDir,
                "run-1");

        LlmStatistics statistics = new LlmStatistics();
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.of(2);
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmService service = new LlmService(model,
                budget,
                configuration,
                statistics,
                recorder);

        try {
            String output = LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);

            assertEquals("ok", output);
            assertEquals(2, calls.get());
            assertEquals(2, statistics.getTotalCalls());
            assertEquals(1, statistics.getSuccessfulCalls());
            assertEquals(0, statistics.getFailedCalls());
            assertEquals(0, budget.getRemaining(LlmFeature.POST_PROCESSING));
            assertEquals(2, Files.readAllLines(recorder.getTraceFile(), java.nio.charset.StandardCharsets.UTF_8).size());
            String trace = new String(Files.readAllBytes(recorder.getTraceFile()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(trace.contains("\"parse_status\":\"RETRYING\""));
            assertTrue(trace.contains("\"parse_status\":\"SUCCESS\""));
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
                LlmBudgetCoordinator.of(1),
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            LlmCallFailedException thrown = assertThrows(LlmCallFailedException.class,
                    () -> LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));

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
            return new LlmResponse("ok", 0, 0);
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
                LlmBudgetCoordinator.of(1),
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            LlmBudgetExceededException thrown = assertThrows(LlmBudgetExceededException.class,
                    () -> LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));

            assertTrue(thrown.getMessage().contains("attempt 2"));
            assertEquals(1, calls.get());
        } finally {
            service.close();
        }
    }

    @Test
    void retryClassificationUnwrapsNestedTimeoutCause() {
        RuntimeException wrapped = new RuntimeException("wrapper", new RuntimeException(new TimeoutException("slow")));
        assertTrue(LlmErrorClassifier.isRetryable(wrapped));
    }

    @Test
    void retryClassificationDetectsNestedNonRetryableCause() {
        RuntimeException wrapped = new RuntimeException("wrapper", new IllegalArgumentException("bad request"));
        assertFalse(LlmErrorClassifier.isRetryable(wrapped));
    }

    @Test
    void httpStatusCodeDecidesRetryabilityForRetryableCodes() {
        for (int status : new int[]{408, 409, 429, 500, 504, 529}) {
            RuntimeException wrapped = new RuntimeException("wrapper",
                    new LlmHttpException(status, "irrelevant body"));
            assertTrue(LlmErrorClassifier.isRetryable(wrapped), "status " + status + " should be retryable");
        }
    }

    @Test
    void httpStatusCodeDecidesRetryabilityForNonRetryableCodes() {
        for (int status : new int[]{400, 401, 404, 422}) {
            RuntimeException wrapped = new RuntimeException("wrapper",
                    new LlmHttpException(status, "irrelevant body"));
            assertFalse(LlmErrorClassifier.isRetryable(wrapped), "status " + status + " should not be retryable");
        }
    }

    @Test
    void httpStatusCodeOverridesMisleadingResponseBodyText() {
        // A cause-only wrapper repeats the HTTP exception's message, body
        // included, in its own message; the status must still win.
        RuntimeException retryableDespiteBody = new RuntimeException(
                new LlmHttpException(500, "invalid request"));
        assertTrue(LlmErrorClassifier.isRetryable(retryableDespiteBody));

        RuntimeException nonRetryableDespiteBody = new RuntimeException(
                new LlmHttpException(400, "temporarily unavailable"));
        assertFalse(LlmErrorClassifier.isRetryable(nonRetryableDespiteBody));
    }

    @Test
    void malformedResponseIsRetryableEvenWhenItsMessageLooksLikeABadRequest() {
        RuntimeException wrapped = new RuntimeException(new org.evosuite.llm.provider.LlmProtocolException(
                "OpenAI response reported an error: invalid request", "{\"error\":{}}"));
        assertTrue(LlmErrorClassifier.isRetryable(wrapped));
    }

    private static int providerCallsAfterFailure(java.io.IOException failure) {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            calls.incrementAndGet();
            throw failure;
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 3, 1,
                false, Paths.get("target/llm-test-traces"), "run-protocol");
        LlmService service = new LlmService(model,
                LlmBudgetCoordinator.of(10),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
        try {
            assertThrows(LlmCallFailedException.class,
                    () -> LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));
        } finally {
            service.close();
        }
        return calls.get();
    }

    @Test
    void transientProtocolFailureIsRetried() {
        assertTrue(providerCallsAfterFailure(
                new org.evosuite.llm.provider.LlmProtocolException("OpenAI response has no choices", "{}")) > 1);
    }

    @Test
    void deterministicProtocolFailureIsNotRetried() {
        assertEquals(1, providerCallsAfterFailure(
                org.evosuite.llm.provider.LlmProtocolException.permanent("response exceeded 8388608 bytes")));
    }

    @Test
    void deterministicProtocolFailureIsNotRetryableWhenWrapped() {
        RuntimeException wrapped = new RuntimeException(
                org.evosuite.llm.provider.LlmProtocolException.permanent("Anthropic response reported an error: boom", "{}"));
        assertFalse(LlmErrorClassifier.isRetryable(wrapped));
    }

    @Test
    void providerErrorInvalidRequestTypeIsNotRetriedEvenWrappedInCauseOnlyException() {
        RuntimeException wrapped = new RuntimeException(
                new LlmProviderErrorException("invalid_request_error", null, "missing field", "{}"));
        assertFalse(LlmErrorClassifier.isRetryable(wrapped));
    }

    @Test
    void providerErrorRateLimitTypeIsRetried() {
        RuntimeException wrapped = new RuntimeException(
                new LlmProviderErrorException("rate_limit_error", null, "slow down", "{}"));
        assertTrue(LlmErrorClassifier.isRetryable(wrapped));
    }

    @Test
    void expiredDeadlineDoesNotConsumeProviderCallOrBudget() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            calls.incrementAndGet();
            return new LlmResponse("late", 0, 0);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 30, 2, 100,
                false, Paths.get("target/llm-test-traces"), "deadline");
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.of(2);
        LlmService service = new LlmService(model, budget, configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            LlmCallFailedException failure = assertThrows(
                    LlmCallFailedException.class,
                    () -> LlmServiceTestQueries.query(service, 
                            Collections.singletonList(LlmMessage.user("generate")),
                            LlmFeature.TEST_REPAIR, System.nanoTime() - 1L));

            assertTrue(failure.getMessage().contains("deadline"));
            assertEquals(0, calls.get());
            assertEquals(2, budget.getRemaining(LlmFeature.POST_PROCESSING));
        } finally {
            service.close();
        }
    }

    @Test
    void budgetExhaustedAfterRetryableFailureRecordsTheProviderFailure() throws Exception {
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            throw new RuntimeException(new LlmHttpException(503, "unavailable"));
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 2, 1,
                true, tempDir, "budget-tail");
        LlmStatistics statistics = new LlmStatistics();
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(1),
                configuration, statistics, recorder);

        try {
            assertThrows(LlmBudgetExceededException.class, () -> LlmServiceTestQueries.query(service, 
                    Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));

            assertEquals(1, statistics.getTotalCalls());
            assertEquals(1, statistics.getFailedCalls(),
                    "the provider failure that ended the retries is the call's final outcome");
            assertEquals(0, statistics.getTimedOutCalls());
            java.util.List<String> lines = Files.readAllLines(recorder.getTraceFile(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(lines.get(lines.size() - 1).contains("\"parse_status\":\"FAILED\""),
                    "the trace must not end on RETRYING");
        } finally {
            service.close();
        }
    }

    @Test
    void deadlineExhaustedAfterRetryableFailureRecordsTheProviderFailure() throws Exception {
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            throw new RuntimeException(new LlmHttpException(503, "unavailable"));
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 30, 3, 5_000,
                true, tempDir, "deadline-tail");
        LlmStatistics statistics = new LlmStatistics();
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(10),
                configuration, statistics, recorder);

        try {
            assertThrows(LlmCallFailedException.class, () -> LlmServiceTestQueries.query(service, 
                    Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));

            assertEquals(1, statistics.getTotalCalls());
            assertEquals(1, statistics.getFailedCalls());
            java.util.List<String> lines = Files.readAllLines(recorder.getTraceFile(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(lines.get(lines.size() - 1).contains("\"parse_status\":\"FAILED\""));
        } finally {
            service.close();
        }
    }

    @Test
    void interruptedCallIsNotCountedAsAProviderFailure() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            providerStarted.countDown();
            new CountDownLatch(1).await();
            return LlmResponse.fromText("unexpected");
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 5, 0, 1,
                false, Paths.get("target/llm-test-traces"), "interrupt-stats");
        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(1), configuration,
                statistics, new LlmTraceRecorder(configuration));
        Thread caller = new Thread(() -> {
            try {
                LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);
            } catch (LlmCallFailedException expected) {
                // The interruption is the expected outcome.
            }
        });

        try {
            caller.start();
            assertTrue(providerStarted.await(1L, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3_000L);
            assertFalse(caller.isAlive());
            assertEquals(1, statistics.getTotalCalls(), "the provider attempt was made");
            assertEquals(0, statistics.getFailedCalls(),
                    "a shutdown interrupt is not a provider failure");
            assertEquals(0, statistics.getTimedOutCalls());
        } finally {
            caller.interrupt();
            service.close();
        }
    }

    @Test
    void interruptedBackoffEndsTheCallWithAnInterruptedOutcome() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            providerCalls.incrementAndGet();
            throw new RuntimeException(new LlmHttpException(503, "unavailable"));
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 30, 3, 30_000,
                true, tempDir, "interrupt-backoff");
        LlmStatistics statistics = new LlmStatistics();
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(10),
                configuration, statistics, recorder);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")),
                        LlmFeature.TEST_REPAIR);
            } catch (LlmCallFailedException expected) {
                thrown.set(expected);
            }
        });

        try {
            caller.start();
            long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (providerCalls.get() == 0 && System.nanoTime() < give) {
                Thread.sleep(5);
            }
            assertEquals(1, providerCalls.get());
            Thread.sleep(200);
            caller.interrupt();
            caller.join(3_000L);
            assertFalse(caller.isAlive());
            assertNotNull(thrown.get());
            assertTrue(thrown.get().getMessage().contains("interrupted"));

            assertEquals(1, statistics.getTotalCalls(), "the one provider attempt is counted once");
            assertEquals(0, statistics.getFailedCalls(), "a shutdown interrupt is not a provider failure");
            assertEquals(0, statistics.getTimedOutCalls());
            java.util.List<String> lines = Files.readAllLines(recorder.getTraceFile(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("\"parse_status\":\"RETRYING\""));
            assertTrue(lines.get(1).contains("\"parse_status\":\"INTERRUPTED\""),
                    "the trace must not end on RETRYING: " + lines.get(1));
        } finally {
            caller.interrupt();
            service.close();
        }
    }

    @Test
    void timeoutReplacesExecutorWhenProviderIgnoresInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            started.countDown();
            while (release.getCount() > 0L) {
                try {
                    release.await(25L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately emulate a provider/transport that swallows
                    // interruption while its request remains live.
                }
            }
            return LlmResponse.fromText("late");
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 1, 0, 1,
                false, Paths.get("target/llm-test-traces"), "stuck-worker");
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            assertThrows(LlmCallFailedException.class, () -> LlmServiceTestQueries.query(service, 
                    Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR));
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertEquals(2L, service.getExecutorGenerationForTesting(),
                    "a timed-out live worker must cause a fresh executor to be installed");
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void callerInterruptionCancelsProviderAndPreservesInterruptStatus() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch providerInterrupted = new CountDownLatch(1);
        AtomicReference<Boolean> callerInterrupted = new AtomicReference<>(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            providerStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return LlmResponse.fromText("unexpected");
            } catch (InterruptedException e) {
                providerInterrupted.countDown();
                throw e;
            }
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 5, 0, 1,
                false, Paths.get("target/llm-test-traces"), "caller-interrupt");
        LlmService service = new LlmService(model, LlmBudgetCoordinator.of(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));
        Thread caller = new Thread(() -> {
            try {
                LlmServiceTestQueries.query(service, Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);
            } catch (Throwable throwable) {
                failure.set(throwable);
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            caller.start();
            assertTrue(providerStarted.await(1L, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3_000L);
            assertFalse(caller.isAlive());
            assertInstanceOf(LlmCallFailedException.class, failure.get());
            assertTrue(callerInterrupted.get());
            assertTrue(providerInterrupted.await(1L, TimeUnit.SECONDS));
        } finally {
            caller.interrupt();
            service.close();
        }
    }
}
