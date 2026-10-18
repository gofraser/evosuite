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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static AtomicReference<Thread> submit(Callable<JUnitResult> body,
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
            progress.testStarted("p.C", "test0");
            progress.testFinished(() -> { });
            progress.testStarted("p.C", "test1");
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
            progress.testStarted("p.C", "test0");
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                // the test fails by its own means and the run goes on
            }
            progress.testFinished(() -> { });
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
        progress.markHung(null, "test2", 30_000L);
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
        Assumptions.assumeTrue(Runtime.version().feature() < 20,
                "Thread.stop() is not available from Java 20");
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("p.C", "test4");
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

    @SuppressWarnings("unchecked")
    @Test
    public void interruptMeantForATestIsNotSentOnceTheRunnerMovedOn() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        AtomicBoolean interruptedInB = new AtomicBoolean();
        CountDownLatch inB = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("p.C", "A");
            progress.testFinished(() -> { });
            progress.testStarted("p.C", "B");
            inB.countDown();
            checked.await();
            interruptedInB.set(Thread.currentThread().isInterrupted());
            return JUnitResult.allPassed(1);
        }, future);
        inB.await();
        // the waiting thread judged A (event 1) and then the runner went on to B
        assertFalse(progress.interruptHungTest(1L, runner.get(), 30_000L));
        assertFalse(progress.hasHungTests(), "B must not be blamed for A");
        checked.countDown();
        future[0].get(10, TimeUnit.SECONDS);
        assertFalse(interruptedInB.get(), "the interrupt meant for A landed on B");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void interruptThatTheHungTestLeftBehindDoesNotFailTheNextTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        AtomicBoolean flagInB = new AtomicBoolean(true);
        CountDownLatch interrupted = new CountDownLatch(1);
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("p.C", "A");
            while (interrupted.getCount() > 0) {
                Thread.yield(); // busy, so the interrupt stays set
            }
            // A ends without ever looking at the interrupt, which is still set
            progress.testFinished(() -> { });
            progress.testStarted("p.C", "B");
            flagInB.set(Thread.currentThread().isInterrupted());
            return JUnitResult.allPassed(1);
        }, future);
        while (progress.snapshot().getRunningTest() == null) {
            Thread.sleep(10);
        }
        assertTrue(progress.interruptHungTest(progress.snapshot().getEventCount(), runner.get(), 30_000L));
        interrupted.countDown();
        future[0].get(10, TimeUnit.SECONDS);
        assertFalse(flagInB.get(), "the interrupt of A is still set in B");
    }

    @Test
    public void hungTestIsMatchedByClassAndMethodNotByMethodAlone() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("p.A", "test0", 30_000L);
        JUnitResult result = new JUnitResult(false, 2, 4);
        JUnitFailure inA = new JUnitFailure("a", "java.lang.IllegalStateException", "p.A", "test0", false, "t");
        JUnitFailure inB = new JUnitFailure("b", "java.lang.IllegalStateException", "p.B", "test0", false, "t");
        result.addFailure(inA);
        result.addFailure(inB);

        progress.blameHungTests(result);

        assertEquals(2, result.getFailures().size());
        for (JUnitFailure failure : result.getFailures()) {
            if ("p.A".equals(failure.getDescriptionClassName())) {
                assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, failure.getExceptionClassName());
            } else {
                assertEquals("java.lang.IllegalStateException", failure.getExceptionClassName(),
                        "the failure of B.test0 was blamed on the hang of A.test0");
            }
        }
    }

    @Test
    public void blameKeepsTheFailureCountOfTheResult() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung(null, "test0", 30_000L);
        progress.markHung(null, "test1", 30_000L);
        JUnitResult result = new JUnitResult(false, 1, 3);
        result.addFailure(new JUnitFailure("t", "java.util.concurrent.TimeoutException", "test0", false, "t"));

        progress.blameHungTests(result);
        progress.blameHungTests(result);

        // test0's failure was replaced, test1 had none: two failures, as many as are listed
        assertEquals(2, result.getFailures().size());
        assertEquals(2, result.getFailureCount());
    }

    @Test
    public void snapshotNeverMixesTheTestOfOneMomentWithTheEventCountOfAnother() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        int rounds = 200_000;
        Thread writer = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                progress.testStarted("p.C", "t" + i);
                progress.testFinished(() -> { });
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
    public void abandonedRunnerWhoseHangClearsStartsNoFurtherTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finishedHungTest = new CountDownLatch(1);
        AtomicBoolean ranMore = new AtomicBoolean();
        Future<JUnitResult>[] future = new Future[1];
        AtomicReference<Thread> runner = submit(() -> {
            progress.testStarted("p.C", "test1");
            while (release.getCount() > 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                    // wedged: ignores the interrupt
                }
            }
            // the hang cleared after the check had walked away
            finishedHungTest.countDown();
            progress.testFinished(() -> { });
            ranMore.set(true);
            progress.testStarted("p.C", "test2");
            return JUnitResult.allPassed(1);
        }, future);

        try {
            JUnitAnalyzer.awaitRunner(future[0], runner, progress, 60_000L, 300L, 300L, 1);
        } finally {
            release.countDown();
        }
        assertTrue(finishedHungTest.await(10, TimeUnit.SECONDS));
        Thread.sleep(500L);
        assertFalse(ranMore.get(), "the abandoned runner went on past the end of its test");
        assertFalse(future[0].isDone());
        // and the work that went on without it is not touched
        assertEquals(Thread.State.WAITING, runner.get().getState());
    }

    @Test
    public void resultAfterTheWatchdogAbandonedTheRunnerIsFlaggedAlsoWhenTestsHung() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung(null, "test1", 30_000L);
        JUnitResult result = JUnitAnalyzer.afterWatchdogAbandonedRunner(
                progress.abandonAndCollect(2).getResult(), progress, 2);
        assertTrue(result.wasWatchdogFired());
        assertEquals(1, result.getFailures().size());
        assertEquals(JUnitCheckProgress.Phase.ABANDONED, progress.snapshot().getPhase());

        JUnitCheckProgress noHang = new JUnitCheckProgress();
        JUnitResult kept = JUnitAnalyzer.afterWatchdogAbandonedRunner(
                noHang.abandonAndCollect(2).getResult(), noHang, 2);
        assertTrue(kept.wasWatchdogFired());
        assertTrue(kept.wasSuccessful(), "a check without a hung test keeps the suite");
    }

    /** Other tests abandon runners and leave the class loader of the check marked as kept. */
    private static void forgetAbandonedRunners() throws Exception {
        Field flag = JUnitAnalyzer.class.getDeclaredField("checkRunnerAbandoned");
        flag.setAccessible(true);
        flag.setBoolean(null, false);
        JUnitCheckState.resetForPhase();
    }

    @Test
    public void runnerThatReturnedBeforeTheWaitEndedIsNotWalkedAwayFrom() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        progress.testFinished(() -> { });
        // returned from the run, its result not yet handed to the future
        progress.runEnded();
        CompletableFuture<JUnitResult> notYetDone = new CompletableFuture<>();
        int roundsBefore = JUnitCheckState.getIncompleteRounds();

        assertFalse(JUnitAnalyzer.abandonRunnerLeftRunning(notYetDone, progress, Thread.currentThread(), false),
                "a runner that had returned was abandoned");
        assertFalse(JUnitAnalyzer.isCheckRunnerAbandoned(), "the class loader of the check would be kept");
        assertEquals(roundsBefore, JUnitCheckState.getIncompleteRounds());
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
    }

    @Test
    public void runnerStillGoingWhenTheWaitEndsIsAbandonedAndTheCheckRecordedIncomplete() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        CompletableFuture<JUnitResult> notDone = new CompletableFuture<>();
        int roundsBefore = JUnitCheckState.getIncompleteRounds();
        try {
            assertTrue(JUnitAnalyzer.abandonRunnerLeftRunning(notDone, progress, null, false));
            assertTrue(JUnitAnalyzer.isCheckRunnerAbandoned());
            assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
            assertEquals(JUnitCheckProgress.Phase.ABANDONED, progress.snapshot().getPhase());
            // once abandoned, a second wait that ends does nothing more
            assertFalse(JUnitAnalyzer.abandonRunnerLeftRunning(notDone, progress, null, false));
            assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
        } finally {
            forgetAbandonedRunners();
        }
    }

    @Test
    public void resultWithinGraceBlamesTestsCaughtHanging() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung(null, "test2", 30_000L);
        JUnitResult run = new JUnitResult(false, 1, 3);
        run.addFailure(new JUnitFailure("timed out", "java.util.concurrent.TimeoutException", "test2", false, "t"));

        JUnitResult result = JUnitAnalyzer.resultWithinGrace(
                CompletableFuture.completedFuture(run), progress, 100L, 3);

        assertTrue(result.wasWatchdogFired());
        assertEquals(1, result.getFailures().size());
        assertEquals(JUnitCheckProgress.HUNG_TEST_EXCEPTION, result.getFailures().get(0).getExceptionClassName());
    }

    @Test
    public void runnerThatDiesInTheGraceWindowReportsWhatItFoundNotAFailureOfEveryTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung(null, "test1", 30_000L);
        progress.setPartialResult(() -> new JUnitCheckProgress.Recorded() {
            @Override
            public JUnitResult build() {
                return new JUnitResult(false, 0, 1);
            }

            @Override
            public JUnitResult buildWithoutDetail() {
                return build();
            }
        });
        CompletableFuture<JUnitResult> died = new CompletableFuture<>();
        died.completeExceptionally(new ThreadDeath());

        JUnitResult result = JUnitAnalyzer.resultWithinGrace(died, progress, 100L, 7);

        assertEquals(1, result.getFailures().size(), "only the hung test is blamed");
        assertEquals("test1", result.getFailures().get(0).getDescriptionMethodName());
        assertEquals(1, result.getRunCount());
        assertTrue(result.wasWatchdogFired());
    }

    @Test
    public void runnerThatDiesInTheGraceWindowWithoutAHungTestStillPropagates() {
        CompletableFuture<JUnitResult> died = new CompletableFuture<>();
        died.completeExceptionally(new IllegalStateException("boom"));
        assertThrows(ExecutionException.class,
                () -> JUnitAnalyzer.resultWithinGrace(died, new JUnitCheckProgress(), 100L, 1));
    }

    // ---------------------------------------------------------------------------------------
    // The runner reports that it returned, and a runner that did is not walked away from
    // ---------------------------------------------------------------------------------------

    /** Makes the version dependent analyzer of the runner answer every call with the given one. */
    private static void withAnalyzer(org.mockito.stubbing.Answer<Object> answer, Callable<Void> body)
            throws Exception {
        Field override = JUnitAnalyzer.class.getDeclaredField("VERSION_DEPENDENT_ANALYZER_FOR_TESTING");
        override.setAccessible(true);
        Class<?> type = Class.forName("org.evosuite.junit.JUnitAnalyzer$VersionDependentAnalyzing");
        override.set(null, org.mockito.Mockito.mock(type, answer));
        try {
            body.call();
        } finally {
            override.set(null, null);
        }
    }

    @Test
    public void runnerThatReturnsReportsThatItReturned() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        withAnalyzer(invocation -> JUnitResult.allPassed(1), () -> {
            JUnitAnalyzer.runnerTask(new Class<?>[]{Object.class}, progress).call();
            return null;
        });

        assertFalse(progress.abandon(), "a runner that had returned could still be abandoned");
    }

    @Test
    public void runnerThatThrowsReportsThatItReturned() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        withAnalyzer(invocation -> {
            throw new IllegalStateException("the run failed");
        }, () -> {
            assertThrows(IllegalStateException.class,
                    () -> JUnitAnalyzer.runnerTask(new Class<?>[]{Object.class}, progress).call());
            return null;
        });

        assertFalse(progress.abandon(), "a runner that had returned could still be abandoned");
    }

    /**
     * A runner that returns from the run the moment its grace runs out: the wait for its result
     * times out, and the result is handed over right after.
     */
    private static final class ReturnsAsTheGraceRunsOut extends CompletableFuture<JUnitResult> {
        private final JUnitCheckProgress progress;
        private final JUnitResult result;
        private final boolean handsOverItsResult;
        private boolean timedOut;

        ReturnsAsTheGraceRunsOut(JUnitCheckProgress progress, JUnitResult result, boolean handsOverItsResult) {
            this.progress = progress;
            this.result = result;
            this.handsOverItsResult = handsOverItsResult;
        }

        @Override
        public synchronized JUnitResult get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (!timedOut) {
                timedOut = true;
                progress.runEnded();
                if (handsOverItsResult) {
                    complete(result);
                }
                throw new TimeoutException();
            }
            return super.get(timeout, unit);
        }
    }

    @Test
    public void runnerThatReturnsAsTheGraceRunsOutReportsWhatItFound() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        JUnitResult run = new JUnitResult(false, 1, 2);
        run.addFailure(new JUnitFailure("broken", "java.lang.IllegalStateException", "p.C", "test1", false, "t"));
        int roundsBefore = JUnitCheckState.getIncompleteRounds();

        JUnitResult result = JUnitAnalyzer.resultAfterBudgetRanOut(
                new ReturnsAsTheGraceRunsOut(progress, run, true), null, progress, 100L, 2);

        assertEquals(1, result.getFailures().size(), "the failure the runner found was thrown away");
        assertEquals("test1", result.getFailures().get(0).getDescriptionMethodName());
        assertTrue(result.wasWatchdogFired());
        assertEquals(roundsBefore, JUnitCheckState.getIncompleteRounds(),
                "a check that completed was recorded incomplete");
        assertFalse(JUnitAnalyzer.isCheckRunnerAbandoned(), "the class loader of the check would be kept");
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
    }

    @Test
    public void runnerThatReturnsWithoutHandingOverItsResultIsGivenUpOnButNotWalkedAwayFrom() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        int roundsBefore = JUnitCheckState.getIncompleteRounds();

        JUnitResult result = JUnitAnalyzer.resultAfterBudgetRanOut(
                new ReturnsAsTheGraceRunsOut(progress, null, false), null, progress, 100L, 2);

        assertTrue(result.wasSuccessful(), "a check without a result keeps the suite");
        assertTrue(result.wasWatchdogFired());
        assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
        assertFalse(JUnitAnalyzer.isCheckRunnerAbandoned(), "the class loader of the check would be kept");
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
    }

    @Test
    public void runnerStillGoingAfterTheGraceIsAbandoned() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        int roundsBefore = JUnitCheckState.getIncompleteRounds();
        try {
            JUnitResult result = JUnitAnalyzer.resultAfterBudgetRanOut(
                    new CompletableFuture<>(), null, progress, 100L, 2);

            assertTrue(result.wasSuccessful(), "a check without a hung test keeps the suite");
            assertTrue(result.wasWatchdogFired());
            assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
            assertTrue(JUnitAnalyzer.isCheckRunnerAbandoned());
            assertEquals(JUnitCheckProgress.Phase.ABANDONED, progress.snapshot().getPhase());
        } finally {
            forgetAbandonedRunners();
        }
    }

    @Test
    public void cancelledCheckWhoseRunnerReturnedIsGivenUpOnButNotWalkedAwayFrom() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.runEnded();
        CompletableFuture<JUnitResult> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        int roundsBefore = JUnitCheckState.getIncompleteRounds();

        JUnitResult result = JUnitAnalyzer.resultAfterBudgetRanOut(cancelled, null,
                progress, 100L, 2);

        assertTrue(result.wasSuccessful());
        assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
        assertFalse(JUnitAnalyzer.isCheckRunnerAbandoned(), "the class loader of the check would be kept");
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
    }

    @Test
    public void cancelledCheckWhoseRunnerIsStillGoingIsAbandoned() throws Exception {
        forgetAbandonedRunners();
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        CompletableFuture<JUnitResult> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        int roundsBefore = JUnitCheckState.getIncompleteRounds();
        try {
            JUnitResult result = JUnitAnalyzer.resultAfterBudgetRanOut(cancelled, null, progress, 100L, 2);

            assertTrue(result.wasSuccessful());
            assertEquals(roundsBefore + 1, JUnitCheckState.getIncompleteRounds());
            assertTrue(JUnitAnalyzer.isCheckRunnerAbandoned());
            assertEquals(JUnitCheckProgress.Phase.ABANDONED, progress.snapshot().getPhase());
        } finally {
            forgetAbandonedRunners();
        }
    }
}
