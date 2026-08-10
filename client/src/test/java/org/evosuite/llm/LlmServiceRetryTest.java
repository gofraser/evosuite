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
            return new LlmService.LlmResponse("ok", 0, 0);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 2, 0, 1,
                false, Paths.get("target/llm-test-traces"), "sandbox-worker");
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));
        try {
            assertEquals("ok", service.query(
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
                true,
                tempDir,
                "run-1");

        LlmStatistics statistics = new LlmStatistics();
        LlmBudgetCoordinator.Local budget = new LlmBudgetCoordinator.Local(2);
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmService service = new LlmService(model,
                budget,
                configuration,
                statistics,
                recorder);

        try {
            String output = service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);

            assertEquals("ok", output);
            assertEquals(2, calls.get());
            assertEquals(2, statistics.getTotalCalls());
            assertEquals(1, statistics.getSuccessfulCalls());
            assertEquals(0, statistics.getFailedCalls());
            assertEquals(0, budget.getRemaining());
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

    @Test
    void retryClassificationTreatsTextCannotBeNullAsRetryable() {
        IllegalArgumentException error = new IllegalArgumentException("text cannot be null");
        assertTrue(LlmService.isRetryable(error));
    }

    @Test
    void retriesTextCannotBeNullThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalArgumentException("text cannot be null");
            }
            return new LlmService.LlmResponse("ok", 9, 4);
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
                "run-4");

        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model,
                new LlmBudgetCoordinator.Local(2),
                configuration,
                statistics,
                new LlmTraceRecorder(configuration));

        try {
            String output = service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);

            assertEquals("ok", output);
            assertEquals(2, calls.get());
            assertEquals(1, statistics.getSuccessfulCalls());
            assertEquals(0, statistics.getFailedCalls());
        } finally {
            service.close();
        }
    }

    @Test
    void expiredDeadlineDoesNotConsumeProviderCallOrBudget() {
        AtomicInteger calls = new AtomicInteger();
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            calls.incrementAndGet();
            return new LlmService.LlmResponse("late", 0, 0);
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 30, 2, 100,
                false, Paths.get("target/llm-test-traces"), "deadline");
        LlmBudgetCoordinator.Local budget = new LlmBudgetCoordinator.Local(2);
        LlmService service = new LlmService(model, budget, configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            LlmCallFailedException failure = assertThrows(
                    LlmCallFailedException.class,
                    () -> service.query(
                            Collections.singletonList(LlmMessage.user("generate")),
                            LlmFeature.TEST_REPAIR, System.nanoTime() - 1L));

            assertTrue(failure.getMessage().contains("deadline"));
            assertEquals(0, calls.get());
            assertEquals(2, budget.getRemaining());
        } finally {
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
            return LlmService.LlmResponse.fromText("late");
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 1, 0, 1,
                false, Paths.get("target/llm-test-traces"), "stuck-worker");
        LlmService service = new LlmService(model, new LlmBudgetCoordinator.Local(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            assertThrows(LlmCallFailedException.class, () -> service.query(
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
                return LlmService.LlmResponse.fromText("unexpected");
            } catch (InterruptedException e) {
                providerInterrupted.countDown();
                throw e;
            }
        };
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 1024, 5, 0, 1,
                false, Paths.get("target/llm-test-traces"), "caller-interrupt");
        LlmService service = new LlmService(model, new LlmBudgetCoordinator.Local(1), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));
        Thread caller = new Thread(() -> {
            try {
                service.query(Collections.singletonList(LlmMessage.user("generate")), LlmFeature.TEST_REPAIR);
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
