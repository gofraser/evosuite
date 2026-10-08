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
package org.evosuite.junit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test that hangs where no interrupt can reach it (two threads of the code under test
 * deadlocked on class initialization, with the test thread waiting for one of them) used to cost
 * the whole budget of the JUnit check. The check must instead blame that test and move on.
 */
public class JUnitAnalyzerHungTestTest {

    private static AtomicReference<Thread> submit(java.util.concurrent.Callable<JUnitResult> body,
                                                  Future<JUnitResult>[] futureOut) {
        AtomicReference<Thread> runner = new AtomicReference<>();
        // the thread is created by the executor, as in the real check
        ExecutorService own = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hung-test-runner");
            t.setDaemon(true);
            runner.set(t);
            return t;
        });
        futureOut[0] = own.submit(body);
        return runner;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testThatIgnoresTheInterruptIsBlamedAndTheRunAbandoned() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("test0");
            progress.testFinished();
            progress.testStarted("test1");
            // wedged like a thread waiting for a class initialization lock: the interrupt is ignored
            while (release.getCount() > 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                    // keep waiting, whatever is thrown at this thread
                }
            }
            return JUnitResult.allPassed(1);
        }, future);

        try {
            long start = System.nanoTime();
            JUnitResult result = JUnitAnalyzer.awaitRunner(future[0], runner, progress,
                    60_000L, 300L, 500L, 1);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(tookMs < 20_000L, "the check waited " + tookMs + " ms for a test it could not free");
            assertFalse(result.wasSuccessful());
            assertEquals(1, result.getFailures().size());
            JUnitFailure failure = result.getFailures().get(0);
            assertEquals("test1", failure.getDescriptionMethodName());
            assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, failure.getExceptionClassName());
            assertFalse(failure.isAssertionError(), "a hung test is dropped, not marked unstable");
        } finally {
            release.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testThatEndsQuietlyAfterTheInterruptIsStillBlamed() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("test0");
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                // the test fails by its own means and the run goes on
            }
            progress.testFinished();
            return JUnitResult.allPassed(1);
        }, future);

        JUnitResult result = JUnitAnalyzer.awaitRunner(future[0], runner, progress,
                60_000L, 300L, 5_000L, 1);

        // the code under test can run a test in a task that records the interrupt, so the test
        // passes after having run far too long: it must be dropped all the same
        assertFalse(result.wasSuccessful());
        assertEquals(1, result.getFailures().size());
        assertEquals("test0", result.getFailures().get(0).getDescriptionMethodName());
        assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, result.getFailures().get(0).getExceptionClassName());
    }

    @Test
    public void timeoutTheEngineReportedForAHungTestIsReplacedByTheBlame() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("test2", 30_000L);
        JUnitResult result = new JUnitResult(false, 2, 3);
        result.addFailure(new JUnitFailure("test2() timed out after 3000 milliseconds",
                "java.util.concurrent.TimeoutException", "test2", false, "trace"));
        result.addFailure(new JUnitFailure("other", "java.lang.IllegalStateException", "test1", false, "trace"));

        progress.blameHungTests(result);
        progress.blameHungTests(result); // blaming twice must not duplicate

        assertEquals(2, result.getFailures().size());
        for (JUnitFailure failure : result.getFailures()) {
            if ("test2".equals(failure.getDescriptionMethodName())) {
                assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, failure.getExceptionClassName(),
                        "the engine's timeout is ignored by the check, so it must not stand for the hang");
            } else {
                assertEquals("java.lang.IllegalStateException", failure.getExceptionClassName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void runnerThatDiesFromTheStopStillBlamesTheTest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.version().feature() < 20,
                "Thread.stop() is not available from Java 20");
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("test4");
            // spins in code that checks nothing; the stop kills the runner itself
            long x = 0;
            while (release.getCount() > 0) {
                x++;
            }
            return JUnitResult.allPassed((int) Math.min(x, 1));
        }, future);

        try {
            JUnitResult result = JUnitAnalyzer.awaitRunner(future[0], runner, progress,
                    60_000L, 300L, 500L, 1);

            assertFalse(result.wasSuccessful());
            assertEquals(1, result.getFailures().size());
            assertEquals("test4", result.getFailures().get(0).getDescriptionMethodName());
        } finally {
            release.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void slowRunThatNeverHangsOneTestStillHitsTheBudget() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            // stuck between tests, e.g. in a scaffolding method: no test to blame
            release.await();
            return JUnitResult.allPassed(1);
        }, future);

        try {
            assertThrows(TimeoutException.class, () -> JUnitAnalyzer.awaitRunner(future[0], runner,
                    progress, 800L, 100L, 100L, 1));
        } finally {
            release.countDown();
        }
    }

    /**
     * The runner moves to the next test between the reads of the waiting thread: the test that
     * was running is blamed, and the interrupt must not land on the one that follows.
     */
    static class MovesOnWhenEventCountIsRead extends JUnitCheckProgress {
        final CountDownLatch movedToB = new CountDownLatch(1);
        private boolean moved = false;

        @Override
        long getEventCount() {
            if (!moved) {
                moved = true;
                // the runner finishes A and starts B just as the count is read
                testFinished();
                testStarted("B");
                movedToB.countDown();
            }
            return super.getEventCount();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void interruptLandsOnTheTestThatWasBlamedNotTheNextOne() throws Exception {
        MovesOnWhenEventCountIsRead progress = new MovesOnWhenEventCountIsRead();
        java.util.concurrent.atomic.AtomicBoolean interruptedInB = new java.util.concurrent.atomic.AtomicBoolean();
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("A");
            progress.movedToB.await();
            try {
                Thread.sleep(600L);
            } catch (InterruptedException e) {
                interruptedInB.set(true);
            }
            return JUnitResult.allPassed(1);
        }, future);

        JUnitResult result = JUnitAnalyzer.awaitRunner(future[0], runner, progress,
                60_000L, 1_000L, 500L, 1);

        assertFalse(interruptedInB.get(), "the interrupt meant for A landed on B");
        assertTrue(result.wasSuccessful(), "A moved on by itself and must not be blamed: "
                + result.getFailures().size() + " failures");
    }

    @Test
    public void snapshotNeverMixesTheTestOfOneMomentWithTheEventCountOfAnother() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        int rounds = 200_000;
        Thread writer = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                progress.testStarted("t" + i);
                progress.testFinished();
            }
        });
        writer.start();
        int seen = 0;
        while (writer.isAlive()) {
            JUnitCheckProgress.Snapshot snapshot = progress.snapshot();
            if (snapshot.getRunningTest() != null) {
                long round = Long.parseLong(snapshot.getRunningTest().substring(1));
                assertEquals(2 * round + 1, snapshot.getEventCount());
                seen++;
            }
        }
        writer.join();
        assertTrue(seen > 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void abandonedRunnerIsStoppedAtItsNextProbeOnceTheHangClears() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("test1");
            while (release.getCount() > 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                    // wedged: ignores the interrupt
                }
            }
            // the hang cleared after the check had walked away: the orphan goes on with its test
            try {
                org.evosuite.testcase.execution.ExecutionTracer.checkTimeout();
            } catch (org.evosuite.testcase.execution.TestCaseExecutor.TimeoutExceeded e) {
                stopped.set(true);
            }
            probed.countDown();
            return JUnitResult.allPassed(1);
        }, future);

        try {
            JUnitAnalyzer.awaitRunner(future[0], runner, progress, 60_000L, 300L, 300L, 1);
        } finally {
            release.countDown();
        }
        assertTrue(probed.await(10, TimeUnit.SECONDS));
        assertTrue(stopped.get(), "the abandoned runner went on executing instrumented code");
        // and the probe of the thread that continues the work is untouched
        org.evosuite.testcase.execution.ExecutionTracer.checkTimeout();
    }

    @Test
    public void resultWithinGraceBlamesTestsCaughtHanging() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("test2", 30_000L);
        JUnitResult run = new JUnitResult(false, 1, 3);
        run.addFailure(new JUnitFailure("timed out", "java.util.concurrent.TimeoutException", "test2", false, "t"));

        JUnitResult result = JUnitAnalyzer.resultWithinGrace(
                java.util.concurrent.CompletableFuture.completedFuture(run), progress, 100L, 3);

        assertTrue(result.wasWatchdogFired());
        assertEquals(1, result.getFailures().size());
        assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, result.getFailures().get(0).getExceptionClassName());
    }

    @Test
    public void runnerThatDiesInTheGraceWindowReportsWhatItFoundNotAFailureOfEveryTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("test1", 30_000L);
        progress.setPartialResult(() -> new JUnitResult(false, 0, 1));
        java.util.concurrent.CompletableFuture<JUnitResult> died = new java.util.concurrent.CompletableFuture<>();
        died.completeExceptionally(new ThreadDeath());

        JUnitResult result = JUnitAnalyzer.resultWithinGrace(died, progress, 100L, 7);

        assertEquals(1, result.getFailures().size(), "only the hung test is blamed");
        assertEquals("test1", result.getFailures().get(0).getDescriptionMethodName());
        assertEquals(1, result.getRunCount());
        assertTrue(result.wasWatchdogFired());
    }

    @Test
    public void runnerThatDiesInTheGraceWindowWithoutAHungTestStillPropagates() {
        java.util.concurrent.CompletableFuture<JUnitResult> died = new java.util.concurrent.CompletableFuture<>();
        died.completeExceptionally(new IllegalStateException("boom"));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JUnitAnalyzer.resultWithinGrace(died, new JUnitCheckProgress(), 100L, 1));
    }
}
