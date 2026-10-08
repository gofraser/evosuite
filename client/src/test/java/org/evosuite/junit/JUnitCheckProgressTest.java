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

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.notification.RunListener;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link JUnitCheckProgress} from a runner thread and a waiting thread into the
 * interleavings that matter, with latches rather than timing.
 */
public class JUnitCheckProgressTest {

    private static final long WAIT_S = 10L;

    static volatile CountDownLatch inTest;
    static volatile CountDownLatch release;
    static volatile Boolean interruptedAfterLastTest;
    static volatile Boolean interruptedInNextBeforeClass;
    static volatile int nextBeforeClassRuns;

    @BeforeEach
    public void reset() {
        inTest = new CountDownLatch(1);
        release = new CountDownLatch(1);
        interruptedAfterLastTest = null;
        interruptedInNextBeforeClass = null;
        nextBeforeClassRuns = 0;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // not ours to act on
            }
        }
    }

    /** Waits, without consuming the interrupt status, for the latch to open. */
    private static void spinUntil(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            Thread.onSpinWait();
        }
    }

    private static Thread start(Runnable body) {
        Thread thread = new Thread(body, "progress-test-runner");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Waits for the runner to park in the progress for good. */
    private static void awaitParked(Thread runner, JUnitCheckProgress progress) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (System.nanoTime() < end) {
            if (LockSupport.getBlocker(runner) == progress) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("the runner never parked; it is " + runner.getState());
    }

    /** Waits for a thread to block on a monitor or to end, whichever it does. */
    private static void awaitBlockedOrDone(Thread thread) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (System.nanoTime() < end) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("the thread neither blocked nor ended; it is " + thread.getState());
    }

    /** Runs the classes with JUnit 5 on a runner thread. */
    private static Thread launch(JUnitCheckProgress progress, List<Pair<TestIdentifier, TestExecutionResult>> finished,
                                 Class<?>... classes) {
        return start(() -> {
            LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
            for (Class<?> clazz : classes) {
                builder.selectors(DiscoverySelectors.selectClass(clazz));
            }
            LauncherDiscoveryRequest request = builder.build();
            Launcher launcher = LauncherFactory.create();
            launcher.registerTestExecutionListeners(progress.junit5Listener(finished));
            launcher.execute(request);
        });
    }

    // ---------------------------------------------------------------------------------------
    // A hang interrupt ends with its test
    // ---------------------------------------------------------------------------------------

    /*
     * JUnit 4.13 clears the interrupt status itself when a test statement ends, so an interrupt the
     * test merely ignores does not leak. What leaks is an interrupt for the hang that arrives after
     * that clearing and before the test is reported finished, while the waiting thread still sees
     * the test running. A listener added before the one of the progress holds the runner in exactly
     * that window. (Jupiter clears the status again after reporting the test finished, so the leak
     * is JUnit 4 only.)
     */

    /** The test that is interrupted as it ends. */
    public static class InterruptedAsItEnds4 {
        @org.junit.Test
        public void test0() {
        }

        @org.junit.AfterClass
        public static void afterLastTest() {
            interruptedAfterLastTest = Thread.currentThread().isInterrupted();
        }
    }

    /** The class run after {@link InterruptedAsItEnds4}. */
    public static class RunsNext4 {
        @org.junit.BeforeClass
        public static void beforeClass() {
            nextBeforeClassRuns++;
            interruptedInNextBeforeClass = Thread.currentThread().isInterrupted();
        }

        @org.junit.Test
        public void test0() {
        }
    }

    @Test
    public void junit4HangInterruptIsNotSeenAfterTheTestNorInTheNextClass() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        AtomicReference<Boolean> interruptedAfterRun = new AtomicReference<>();
        Thread runner = start(() -> {
            JUnitCore core = new JUnitCore();
            core.addListener(new RunListener() {
                @Override
                public void testFinished(Description description) {
                    inTest.countDown();
                    spinUntil(release);
                }
            });
            core.addListener(progress.junit4Listener());
            core.run(InterruptedAsItEnds4.class, RunsNext4.class);
            interruptedAfterRun.set(Thread.currentThread().isInterrupted());
        });
        // the runner is between the end of the test and its report, the test still shown as running
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        JUnitCheckProgress.Snapshot seen = progress.snapshot();
        assertEquals("test0", seen.getRunningTest());
        assertTrue(progress.interruptHungTest(seen.getEventCount(), runner, 1_000L));
        release.countDown();
        runner.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertFalse(runner.isAlive());

        assertEquals(Boolean.FALSE, interruptedAfterLastTest,
                "class level cleanup ran with the interrupt for the hang");
        assertEquals(Boolean.FALSE, interruptedInNextBeforeClass,
                "class level setup of the next class ran with the interrupt for the hang");
        assertEquals(Boolean.FALSE, interruptedAfterRun.get(), "the runner left the run interrupted");
    }

    @Test
    public void budgetInterruptSentAfterAHangInterruptSurvivesIntoTheNextTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch budgetSent = new CountDownLatch(1);
        AtomicReference<Boolean> interruptedInB = new AtomicReference<>();
        Thread runner = start(() -> {
            progress.testStarted("p.C", "a");
            inTest.countDown();
            spinUntil(budgetSent);
            progress.testFinished(() -> { });
            progress.testStarted("p.C", "b");
            interruptedInB.set(Thread.currentThread().isInterrupted());
            progress.testFinished(() -> { });
        });
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        assertTrue(progress.interruptHungTest(progress.snapshot().getEventCount(), runner, 1_000L));
        progress.interruptForBudget(runner);
        budgetSent.countDown();
        runner.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertFalse(runner.isAlive());

        assertEquals(Boolean.TRUE, interruptedInB.get(), "the interrupt of the watchdog was swallowed");
    }

    // ---------------------------------------------------------------------------------------
    // Abandoning a runner in a test
    // ---------------------------------------------------------------------------------------

    @Test
    public void abandonIfStillLeavesARunnerThatFinishedTheTestAtTheSameMomentAlone() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch inRecord = new CountDownLatch(1);
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch startedB = new CountDownLatch(1);
        Thread runner = start(() -> {
            progress.testStarted("p.C", "a");
            inTest.countDown();
            awaitUninterruptibly(release);
            progress.testFinished(() -> {
                inRecord.countDown();
                awaitUninterruptibly(recorded);
            });
            progress.testStarted("p.C", "b");
            startedB.countDown();
            progress.testFinished(() -> { });
        });
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        long judged = progress.snapshot().getEventCount();
        // the runner finishes the test while the waiting thread decides to abandon it
        release.countDown();
        assertTrue(inRecord.await(WAIT_S, TimeUnit.SECONDS));
        AtomicReference<JUnitResult> abandoned = new AtomicReference<>();
        Thread waiting = start(() -> abandoned.set(progress.abandonIfStill(judged, 1_000L, 1)));
        awaitBlockedOrDone(waiting);
        recorded.countDown();
        waiting.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertFalse(waiting.isAlive());

        assertNull(abandoned.get(), "abandoned and blamed a runner that had finished the test");
        assertFalse(progress.hasHungTests());
        assertTrue(startedB.await(WAIT_S, TimeUnit.SECONDS), "the runner was abandoned in the next test");
    }

    @Test
    public void runnerAbandonedInItsTestRunsNoFurtherTest() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        AtomicBoolean startedB = new AtomicBoolean();
        AtomicBoolean recordedA = new AtomicBoolean();
        Thread runner = start(() -> {
            progress.testStarted("p.C", "a");
            inTest.countDown();
            awaitUninterruptibly(release);
            progress.testFinished(() -> recordedA.set(true));
            progress.testStarted("p.C", "b");
            startedB.set(true);
        });
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        JUnitResult result = progress.abandonIfStill(progress.snapshot().getEventCount(), 1_000L, 1);
        release.countDown();
        awaitParked(runner, progress);

        assertNotNull(result);
        assertEquals(1, result.getFailures().size());
        assertEquals("a", result.getFailures().get(0).getDescriptionMethodName());
        assertEquals("p.C", result.getFailures().get(0).getDescriptionClassName());
        assertFalse(startedB.get(), "the abandoned runner started another test");
        assertFalse(recordedA.get(), "the abandoned runner recorded its test after the result was taken");
    }

    // ---------------------------------------------------------------------------------------
    // Abandoning a runner between classes
    // ---------------------------------------------------------------------------------------

    /** Its class level cleanup is where the runner is abandoned. */
    public static class AbandonedInAfterClass4 {
        @org.junit.Test
        public void test0() {
        }

        @org.junit.AfterClass
        public static void afterClass() {
            inTest.countDown();
            awaitUninterruptibly(release);
        }
    }

    @Test
    public void junit4RunnerAbandonedBetweenClassesParksBeforeTheNextBeforeClass() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        Thread runner = start(() -> {
            JUnitCore core = new JUnitCore();
            core.addListener(progress.junit4Listener());
            core.run(AbandonedInAfterClass4.class, RunsNext4.class);
        });
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
        JUnitResult result = progress.abandonAndCollect(2).getResult();
        release.countDown();
        awaitParked(runner, progress);

        assertEquals(0, nextBeforeClassRuns, "the abandoned runner ran @BeforeClass of the next class");
        assertEquals(1, result.getRunCount());
    }

    /** JUnit 5: its class level cleanup is where the runner is abandoned. */
    public static class AbandonedInAfterAll5 {
        @org.junit.jupiter.api.Test
        void test0() {
        }

        @org.junit.jupiter.api.AfterAll
        static void afterAll() {
            inTest.countDown();
            awaitUninterruptibly(release);
        }
    }

    /** JUnit 5: the class run after {@link AbandonedInAfterAll5}. */
    public static class RunsNext5 {
        @org.junit.jupiter.api.BeforeAll
        static void beforeAll() {
            nextBeforeClassRuns++;
        }

        @org.junit.jupiter.api.Test
        void test0() {
        }
    }

    @Test
    public void junit5RunnerAbandonedBetweenClassesParksBeforeTheNextBeforeAll() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        Thread runner = launch(progress, new CopyOnWriteArrayList<>(), AbandonedInAfterAll5.class, RunsNext5.class);
        assertTrue(inTest.await(WAIT_S, TimeUnit.SECONDS));
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
        progress.abandonAndCollect(2);
        release.countDown();
        awaitParked(runner, progress);

        assertEquals(0, nextBeforeClassRuns, "the abandoned runner ran @BeforeAll of the next class");
    }

    // ---------------------------------------------------------------------------------------
    // A test that finishes as the runner is abandoned
    // ---------------------------------------------------------------------------------------

    /** JUnit 5: a test that fails. */
    public static class Fails5 {
        @org.junit.jupiter.api.Test
        void test0() {
            org.junit.jupiter.api.Assertions.fail("fails");
        }
    }

    @Test
    public void junit5TestThatFailsAsTheRunnerIsAbandonedIsInTheResultOrStillRunning() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        CountDownLatch inRecord = new CountDownLatch(1);
        CountDownLatch recorded = new CountDownLatch(1);
        // recording the failed test takes until the waiting thread has tried to abandon the runner
        List<Pair<TestIdentifier, TestExecutionResult>> finished = new CopyOnWriteArrayList<Pair<TestIdentifier,
                TestExecutionResult>>() {
            @Override
            public boolean add(Pair<TestIdentifier, TestExecutionResult> entry) {
                if (entry.getLeft().isTest()) {
                    inRecord.countDown();
                    awaitUninterruptibly(recorded);
                }
                return super.add(entry);
            }
        };
        launch(progress, finished, Fails5.class);
        assertTrue(inRecord.await(WAIT_S, TimeUnit.SECONDS));
        AtomicReference<JUnitResult> collected = new AtomicReference<>();
        Thread waiting = start(() -> collected.set(progress.abandonAndCollect(1).getResult()));
        awaitBlockedOrDone(waiting);
        recorded.countDown();
        waiting.join(TimeUnit.SECONDS.toMillis(WAIT_S));
        assertFalse(waiting.isAlive());

        boolean inResult = false;
        for (JUnitFailure failure : collected.get().getFailures()) {
            inResult |= "test0".equals(failure.getDescriptionMethodName());
        }
        // an abandoned progress does not move any more, so this is what it showed when abandoned
        boolean stillRunning = "test0".equals(progress.snapshot().getRunningTest());
        assertTrue(inResult || stillRunning, "test0 finished but is missing from the result");
    }

    // ---------------------------------------------------------------------------------------
    // A runner that returned is not abandoned
    // ---------------------------------------------------------------------------------------

    @Test
    public void runnerThatReturnedFromTheRunIsNotAbandoned() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        progress.testFinished(() -> { });
        progress.runEnded();

        assertFalse(progress.abandonAndCollect(1).abandonedTheRunner(),
                "a runner that had returned was reported abandoned");
        assertFalse(progress.abandon());
        assertEquals(JUnitCheckProgress.Phase.CLASS_LEVEL, progress.snapshot().getPhase());
    }

    @Test
    public void runnerIsAbandonedOnlyByTheFirstCall() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");

        assertTrue(progress.abandonAndCollect(1).abandonedTheRunner());
        assertFalse(progress.abandonAndCollect(1).abandonedTheRunner());
        assertFalse(progress.abandon());
        // the runner returning after it was abandoned changes nothing
        progress.runEnded();
        assertEquals(JUnitCheckProgress.Phase.ABANDONED, progress.snapshot().getPhase());
    }

    @Test
    public void runnerAbandonedAfterAHangIsNotAbandonedAgain() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        assertNotNull(progress.abandonIfStill(progress.snapshot().getEventCount(), 1_000L, 1));

        assertFalse(progress.abandon());
    }

    // ---------------------------------------------------------------------------------------
    // Code under test runs only after the runner was let go
    // ---------------------------------------------------------------------------------------

    /** An exception of the code under test whose message waits for the runner to go on. */
    private static final class WaitsForTheRunner extends RuntimeException {
        private final JUnitCheckProgress progress;
        private final AtomicReference<Thread> runner;

        WaitsForTheRunner(JUnitCheckProgress progress, AtomicReference<Thread> runner) {
            this.progress = progress;
            this.runner = runner;
        }

        @Override
        public String getMessage() {
            // bounded, so that the deadlock fails the test instead of hanging it
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < end) {
                if (LockSupport.getBlocker(runner.get()) == progress) {
                    return "the runner made its next callback";
                }
                Thread.onSpinWait();
            }
            return "the runner could not make its next callback";
        }
    }

    @Test
    public void failureOfTheCodeUnderTestIsReadAfterTheRunnerCanMakeItsNextCallback() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        AtomicReference<Thread> runnerRef = new AtomicReference<>();
        RunListener listener = progress.junit4Listener();
        Description test0 = Description.createTestDescription("p.C", "test0");
        CountDownLatch recorded = new CountDownLatch(1);
        Thread runner = new Thread(() -> {
            try {
                listener.testSuiteStarted(Description.createSuiteDescription("p.C"));
                listener.testStarted(test0);
                listener.testFailure(new org.junit.runner.notification.Failure(test0,
                        new WaitsForTheRunner(progress, runnerRef)));
                listener.testFinished(test0);
                recorded.countDown();
                while (progress.snapshot().getPhase() != JUnitCheckProgress.Phase.ABANDONED) {
                    Thread.onSpinWait();
                }
                // the next callback: an abandoned runner parks in it
                listener.testStarted(Description.createTestDescription("p.C", "test1"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "progress-test-runner");
        runner.setDaemon(true);
        runnerRef.set(runner);
        runner.start();
        assertTrue(recorded.await(WAIT_S, TimeUnit.SECONDS));

        JUnitResult result = progress.abandonAndCollect(1).getResult();

        assertEquals(1, result.getFailures().size());
        assertEquals("the runner made its next callback", result.getFailures().get(0).getMessage(),
                "the message of the code under test was read while the runner could not go on");
    }

    // ---------------------------------------------------------------------------------------
    // The failure count follows the blame
    // ---------------------------------------------------------------------------------------

    @Test
    public void blameThatReplacesTwoFailuresOfATestCountsAsOneFailure() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung(null, "test0", 30_000L);
        JUnitResult result = new JUnitResult(false, 3, 2);
        // the test timed out and its @After failed too; test1 failed on its own
        result.addFailure(new JUnitFailure("timed out", "java.util.concurrent.TimeoutException", "test0", false, "t"));
        result.addFailure(new JUnitFailure("after", "java.lang.IllegalStateException", "test0", false, "t"));
        result.addFailure(new JUnitFailure("other", "java.lang.IllegalStateException", "test1", false, "t"));

        progress.blameHungTests(result);
        progress.blameHungTests(result);

        assertEquals(2, result.getFailures().size());
        assertEquals(2, result.getFailureCount(), "the failure count does not match the failures listed");
    }

    @Test
    public void hungTestOfUnknownClassLeavesTheSameNamedFailuresOfKnownClassesAlone() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        // JUnit 5 without a method source: the class of the hung test is not known
        progress.markHung(null, "test00", 30_000L);
        JUnitResult result = new JUnitResult(false, 3, 3);
        result.addFailure(new JUnitFailure("a", "java.lang.IllegalStateException", "p.A", "test00", false, "t"));
        result.addFailure(new JUnitFailure("b", "java.lang.IllegalStateException", "p.B", "test00", false, "t"));
        result.addFailure(new JUnitFailure("timed out", "java.util.concurrent.TimeoutException", null, "test00",
                false, "t"));

        progress.blameHungTests(result);

        int blamed = 0;
        int ofKnownClasses = 0;
        for (JUnitFailure failure : result.getFailures()) {
            if (JUnitCheckProgress.HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName())) {
                blamed++;
            } else {
                assertNotNull(failure.getDescriptionClassName(), "the failure of the hung test itself was kept");
                ofKnownClasses++;
            }
        }
        assertEquals(2, ofKnownClasses, "a failure of another class was taken for the hung test");
        assertEquals(1, blamed);
        assertEquals(3, result.getFailureCount());
    }

    @Test
    public void hungTestKnownByClassLeavesAFailureOfUnknownClassAlone() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("p.A", "test00", 30_000L);
        JUnitResult result = new JUnitResult(false, 1, 2);
        result.addFailure(new JUnitFailure("x", "java.lang.IllegalStateException", null, "test00", false, "t"));

        progress.blameHungTests(result);

        assertEquals(2, result.getFailures().size());
        assertEquals(2, result.getFailureCount());
    }

    @Test
    public void hungTestIsBlamedWithTheLongestTimeItWasSeenRunning() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.markHung("p.A", "test0", 30_000L);
        // abandoned later, after it ignored the interrupt
        progress.markHung("p.A", "test0", 50_000L);
        progress.markHung("p.A", "test0", 40_000L);
        JUnitResult result = new JUnitResult(true, 0, 1);

        progress.blameHungTests(result);

        assertEquals(1, result.getFailures().size());
        String message = result.getFailures().get(0).getMessage();
        assertTrue(message.contains("50000 ms"), message);
    }

    // ---------------------------------------------------------------------------------------
    // A runner that returned in the middle of a test is left alone
    // ---------------------------------------------------------------------------------------

    @Test
    public void runnerThatReturnedInTheMiddleOfAHungTestIsNotAbandonedForIt() {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        long event = progress.snapshot().getEventCount();
        // the stop of the hung test ended the run before the test was reported finished
        progress.runEnded();

        assertNull(progress.abandonIfStill(event, 1_000L, 1), "a runner that had returned was abandoned");
        assertEquals(JUnitCheckProgress.Phase.IN_TEST, progress.snapshot().getPhase());
        assertFalse(progress.hasHungTests());
    }

    @Test
    public void runnerThatReturnedInTheMiddleOfAHungTestIsNeitherInterruptedNorStopped() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        progress.testStarted("p.C", "test0");
        long event = progress.snapshot().getEventCount();
        progress.runEnded();
        // the thread the runner ran on, now idle in its executor; it keeps any interrupt it gets
        CountDownLatch done = new CountDownLatch(1);
        Thread idle = start(() -> spinUntil(done));
        try {
            assertFalse(progress.interruptHungTest(event, idle, 1_000L),
                    "a runner that had returned was interrupted");
            assertFalse(idle.isInterrupted());
            assertFalse(progress.stopIfStill(event, idle), "a runner that had returned was stopped");
            idle.join(200L);
            assertTrue(idle.isAlive());
            assertFalse(progress.hasHungTests());
            progress.interruptForBudget(idle);
            assertFalse(idle.isInterrupted(), "a runner that had returned was interrupted for the budget");
        } finally {
            done.countDown();
        }
    }

    // ---------------------------------------------------------------------------------------
    // A failure whose message never comes back does not hold up the result
    // ---------------------------------------------------------------------------------------

    /**
     * An exception of the code under test whose message and stack trace never come back once it
     * is armed. Unarmed, it answers, so that the engine can report it.
     */
    private static final class NeverAnswers extends RuntimeException {
        private final AtomicBoolean armed;
        private final CountDownLatch released;

        NeverAnswers(AtomicBoolean armed, CountDownLatch released) {
            this.armed = armed;
            this.released = released;
        }

        @Override
        public String getMessage() {
            if (armed.get()) {
                awaitUninterruptibly(released);
            }
            return "released";
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            if (armed.get()) {
                awaitUninterruptibly(released);
            }
            return super.getStackTrace();
        }
    }

    @Test
    public void junit4FailureWhoseMessageNeverComesBackIsReportedByItsClass() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        RunListener listener = progress.junit4Listener();
        Description test0 = Description.createTestDescription("p.C", "test0");
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch released = new CountDownLatch(1);
        try {
            listener.testSuiteStarted(Description.createSuiteDescription("p.C"));
            listener.testStarted(test0);
            listener.testFailure(new org.junit.runner.notification.Failure(test0,
                    new NeverAnswers(armed, released)));
            listener.testFinished(test0);
            armed.set(true);

            JUnitResult result = assertTimeoutPreemptively(Duration.ofSeconds(15L),
                    () -> progress.abandonAndCollect(1).getResult());

            assertEquals(1, result.getFailures().size());
            JUnitFailure failure = result.getFailures().get(0);
            assertEquals("test0", failure.getDescriptionMethodName());
            assertEquals("p.C", failure.getDescriptionClassName());
            assertTrue(failure.getExceptionClassName().endsWith(NeverAnswers.class.getName()),
                    failure.getExceptionClassName());
            assertTrue(failure.getMessage().contains(NeverAnswers.class.getName()), failure.getMessage());
        } finally {
            released.countDown();
        }
    }

    /** A Jupiter test that fails with an exception whose message never comes back. */
    public static class NeverAnswers5 {
        static volatile AtomicBoolean armed;
        static volatile CountDownLatch released;

        @Test
        public void test0() {
            throw new NeverAnswers(armed, released);
        }
    }

    @Test
    public void junit5FailureWhoseMessageNeverComesBackIsReportedByItsClass() throws Exception {
        JUnitCheckProgress progress = new JUnitCheckProgress();
        List<Pair<TestIdentifier, TestExecutionResult>> finished = new CopyOnWriteArrayList<>();
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch released = new CountDownLatch(1);
        NeverAnswers5.armed = armed;
        NeverAnswers5.released = released;
        try {
            Thread runner = launch(progress, finished, NeverAnswers5.class);
            runner.join(TimeUnit.SECONDS.toMillis(WAIT_S));
            assertFalse(runner.isAlive());
            armed.set(true);

            JUnitResult result = assertTimeoutPreemptively(Duration.ofSeconds(15L),
                    () -> progress.abandonAndCollect(1).getResult());

            assertEquals(1, result.getFailures().size());
            JUnitFailure failure = result.getFailures().get(0);
            assertEquals("test0", failure.getDescriptionMethodName());
            assertEquals(NeverAnswers.class.getName(), failure.getExceptionClassName());
            assertTrue(failure.getMessage().contains(NeverAnswers.class.getName()), failure.getMessage());
        } finally {
            released.countDown();
        }
    }
}
