/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.testcase.execution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.Properties;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.lang.MockObjectWait;
import org.evosuite.runtime.mock.java.lang.MockThread;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the timeout handling of {@link TestCaseExecutor} does to a test, its worker thread,
 * the standard streams and the log, for each way a test can fail to come back in time: the
 * escalation (interrupt, kill switch, grace waits, cancel, force-stop, abandon, rotate) is only
 * observable through these outcomes.
 *
 * <p>Every scenario uses short timeouts and the SUT methods are bounded by a wall-clock
 * deadline, so a worker that survives the executor still ends on its own.
 */
public class TestCaseExecutorTimeoutEscalationTest {

    private static final String ROTATING = "Rotating TestCaseExecutor worker after timeout";
    private static final String STALLED_TRACE = "Stalled thread stack trace:";
    private static final String FORCE_STOPPING = "Force-stopping stuck thread";
    private static final String SWALLOWS = "skipping the graceful shutdown chain";
    private static final String CLINIT_ABANDON = "abandoning the thread instead of forcing it";

    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static volatile Thread sutThread;
    private static volatile int interrupts;

    private int timeout;
    private int shutdownTimeout;
    private int clinitGraceTimeout;
    private long rotationCooldown;
    private int maxRotations;
    private int maxStalled;
    private boolean cpuTimeout;
    private boolean printToSystem;
    private PrintStream out;
    private PrintStream err;
    private PrintStream installedOut;
    private PrintStream installedErr;

    private Logger executorLogger;
    private Level executorLevel;
    private ListAppender<ILoggingEvent> appender;

    private TestCaseExecutor executor;

    @BeforeEach
    public void setUp() {
        timeout = Properties.TIMEOUT;
        shutdownTimeout = Properties.SHUTDOWN_TIMEOUT;
        clinitGraceTimeout = Properties.CLINIT_GRACE_TIMEOUT;
        rotationCooldown = Properties.ROTATION_COOLDOWN_MS;
        maxRotations = Properties.MAX_TOTAL_ROTATIONS;
        maxStalled = Properties.MAX_STALLED_THREADS;
        cpuTimeout = Properties.CPU_TIMEOUT;
        printToSystem = Properties.PRINT_TO_SYSTEM;
        Properties.TIMEOUT = 300;
        Properties.SHUTDOWN_TIMEOUT = 150;
        Properties.CLINIT_GRACE_TIMEOUT = 300;
        Properties.ROTATION_COOLDOWN_MS = 0;
        Properties.MAX_TOTAL_ROTATIONS = 50;
        Properties.MAX_STALLED_THREADS = 50;
        Properties.CPU_TIMEOUT = false;
        Properties.PRINT_TO_SYSTEM = false;

        out = System.out;
        err = System.err;
        installedOut = new PrintStream(new ByteArrayOutputStream(), true);
        installedErr = new PrintStream(new ByteArrayOutputStream(), true);
        System.setOut(installedOut);
        System.setErr(installedErr);

        executorLogger = (Logger) LoggerFactory.getLogger(TestCaseExecutor.class);
        executorLevel = executorLogger.getLevel();
        executorLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);

        release = new CountDownLatch(1);
        sutThread = null;
        interrupts = 0;
        executor = TestCaseExecutor.getInstance();
    }

    @AfterEach
    public void tearDown() {
        release.countDown();
        Thread leftover = sutThread;
        if (leftover != null) {
            try {
                leftover.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executorLogger.detachAppender(appender);
        executorLogger.setLevel(executorLevel);
        System.setOut(out);
        System.setErr(err);
        Properties.TIMEOUT = timeout;
        Properties.SHUTDOWN_TIMEOUT = shutdownTimeout;
        Properties.CLINIT_GRACE_TIMEOUT = clinitGraceTimeout;
        Properties.ROTATION_COOLDOWN_MS = rotationCooldown;
        Properties.MAX_TOTAL_ROTATIONS = maxRotations;
        Properties.MAX_STALLED_THREADS = maxStalled;
        Properties.CPU_TIMEOUT = cpuTimeout;
        Properties.PRINT_TO_SYSTEM = printToSystem;
        MockFramework.disable();
        MockThread.reset();
        MockObjectWait.reset();
        ExecutionTracer.setKillSwitch(false);
        ExecutionTracer.enable();
        TestCaseExecutor.pullDown();
        TestCaseExecutor.initExecutor();
        TestCaseExecutor.resetAdaptiveTimeoutStateIfPresent();
    }

    // ----- the scenarios' SUT code -----

    public static class Sut {
        public static int quick() {
            return 7;
        }

        /** Blocked in a real sleep; an interrupt ends it. */
        public static void sleepLong() throws InterruptedException {
            sutThread = Thread.currentThread();
            Thread.sleep(30_000L);
        }

        /** A busy loop in uninstrumented code: the interrupt flag is never looked at. */
        public static void busyIgnoringInterrupt() {
            sutThread = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long sink = 0;
            while (System.nanoTime() < deadline) {
                sink += deadline;
            }
            if (sink == 42) {
                System.out.print("");
            }
        }

        /** Survives interrupts and Thread.stop() until released, like a catch-Throwable retry loop. */
        public static void resistEverything() {
            sutThread = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean finished = false;
            while (!finished) {
                try {
                    while (release.getCount() > 0 && System.nanoTime() < deadline) {
                        try {
                            release.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            interrupts++;
                        }
                    }
                    finished = true;
                } catch (ThreadDeath ignored) {
                    // keeps going
                }
            }
        }

        /** Loops like a server accept loop: every kill-switch exception is swallowed. */
        public static void spinSwallowingTimeout() {
            sutThread = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try {
                    ExecutionTracer.checkTimeout();
                } catch (Throwable ignored) {
                    // exactly what accept_loop() does
                }
            }
        }

        /** Polls with the mocked sleep until the call budget of the mock ends it. */
        public static void mockedSleepLoop() throws InterruptedException {
            sutThread = Thread.currentThread();
            while (true) {
                MockThread.sleep(10);
            }
        }

        /** Waits in a loop that nothing ever notifies, until the budget of the mock ends it. */
        public static void mockedWaitLoop() throws InterruptedException {
            sutThread = Thread.currentThread();
            Object lock = new Object();
            synchronized (lock) {
                while (true) {
                    MockObjectWait.wait(lock, 1000);
                }
            }
        }

        /** Sleeps through the mocked sleep() from a loop that swallows the budget exception. */
        public static void mockedSleepLoopSwallowing() {
            sutThread = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try {
                    MockThread.sleep(10);
                } catch (Throwable ignored) {
                    // keeps polling
                }
            }
        }
    }

    /** Its static initializer blocks until released; nothing else touches it. */
    public static class StuckInitializer {
        static {
            sutThread = Thread.currentThread();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException("initializer was interrupted", e);
            }
        }

        public static int answer() {
            return 42;
        }
    }

    private static DefaultTestCase testOf(Class<?> owner, String method) throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new MethodStatement(test,
                new GenericMethod(owner.getMethod(method), owner), null, Collections.emptyList()));
        return test;
    }

    // ----- what the executor looks like from the outside -----

    private Object field(String name) throws Exception {
        Field f = TestCaseExecutor.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(executor);
    }

    private Thread workerThread() throws Exception {
        return (Thread) field("currentThread");
    }

    @SuppressWarnings("unchecked")
    private Set<Thread> stalled() throws Exception {
        return (Set<Thread>) field("stalledThreads");
    }

    @SuppressWarnings("unchecked")
    private Set<Thread> clinitStuck() throws Exception {
        return (Set<Thread>) field("clinitStuckThreads");
    }

    @SuppressWarnings("unchecked")
    private Set<String> abandonedClinits() throws Exception {
        return (Set<String>) field("abandonedClinitClasses");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private boolean logged(String fragment) {
        return messages().stream().anyMatch(m -> m.contains(fragment));
    }

    private int indexOfLog(String fragment) {
        List<String> all = messages();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    private void assertStreamsHandedBack() {
        assertSame(installedOut, System.out, "System.out was not left as the run handed it back");
        assertSame(installedErr, System.err, "System.err was not left as the run handed it back");
    }

    private void assertTimeoutRecorded(DefaultTestCase test, ExecutionResult result) {
        assertTrue(result.hasTimeout());
        Throwable recorded = result.getExceptionThrownAtPosition(test.size());
        assertTrue(recorded instanceof TestCaseExecutor.TimeoutExceeded, String.valueOf(recorded));
        assertTrue(recorded.getMessage().startsWith("Test execution timed out in worker thread "
                + TestCaseExecutor.TEST_EXECUTION_THREAD + "_"), recorded.getMessage());
    }

    // ----- scenarios -----

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void finishingTestNeitherTimesOutNorRotates() throws Exception {
        DefaultTestCase test = testOf(Sut.class, "quick");
        int rotations = executor.getTotalRotationCount();
        int stalledBefore = executor.getNumStalledThreads();

        ExecutionResult first = executor.execute(test, 5000);
        Thread worker = workerThread();
        ExecutionResult second = executor.execute(test, 5000);

        assertFalse(first.hasTimeout());
        assertTrue(first.noThrownExceptions());
        assertFalse(second.hasTimeout());
        assertSame(worker, workerThread(), "the worker is reused");
        assertTrue(worker.isAlive());
        assertEquals(rotations, executor.getTotalRotationCount());
        assertEquals(stalledBefore, stalled().size());
        assertStreamsHandedBack();
        assertFalse(logged("TimeoutException"));
        assertFalse(logged(ROTATING));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void interruptibleTestTimesOutAndKeepsItsWorker() throws Exception {
        DefaultTestCase test = testOf(Sut.class, "sleepLong");
        int rotations = executor.getTotalRotationCount();
        int stalledBefore = executor.getNumStalledThreads();

        ExecutionResult result = executor.execute(test, 300);
        Thread worker = workerThread();

        assertTimeoutRecorded(test, result);
        assertSame(sutThread, worker);
        assertTrue(worker.isAlive(), "the interrupt ended the sleep, the pool thread went back to idle");
        assertEquals(Thread.NORM_PRIORITY, worker.getPriority());
        assertEquals(rotations, executor.getTotalRotationCount());
        assertEquals(stalledBefore, stalled().size());
        assertStreamsHandedBack();
        assertTrue(logged("TimeoutException, need to stop runner"));
        assertFalse(logged(ROTATING));
        assertFalse(logged(FORCE_STOPPING));
        assertFalse(logged(SWALLOWS));

        // The same worker runs the next test.
        assertTrue(executor.execute(testOf(Sut.class, "quick"), 5000).noThrownExceptions());
        assertSame(worker, workerThread());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testIgnoringTheInterruptIsForceStopped() throws Exception {
        DefaultTestCase test = testOf(Sut.class, "busyIgnoringInterrupt");
        int rotations = executor.getTotalRotationCount();
        int stalledBefore = executor.getNumStalledThreads();

        ExecutionResult result = executor.execute(test, 300);
        Thread abandoned = sutThread;

        assertTimeoutRecorded(test, result);
        // Whether the stop has unwound the worker by the time the executor looks again depends on
        // how fast the cleanup of the stopped run is: either the pool thread is back to idle and
        // nothing happens to it, or it still counts as stuck and is retired and the executor rotated.
        if (logged(ROTATING)) {
            assertTrue(abandoned.isAlive());
            assertEquals(Thread.MIN_PRIORITY, abandoned.getPriority());
            assertEquals(rotations + 1, executor.getTotalRotationCount());
            assertEquals(stalledBefore + 1, stalled().size());
            assertTrue(stalled().contains(abandoned));
            assertNull(workerThread());
        } else {
            assertEquals(Thread.NORM_PRIORITY, abandoned.getPriority());
            assertEquals(rotations, executor.getTotalRotationCount());
            assertEquals(stalledBefore, stalled().size());
            assertSame(abandoned, workerThread());
            assertFalse(abandoned.isAlive() && abandoned.getState() == Thread.State.RUNNABLE);
        }
        assertStreamsHandedBack();
        assertTrue(logged("Cancelling thread:"));
        assertTrue(logged("Run still not finished, thread " + abandoned.getName() + " is stuck at:"));
        assertTrue(logged(FORCE_STOPPING + " " + abandoned.getName() + " via Thread.stop()"));
        assertTrue(logged(ROTATING));
        assertFalse(logged(SWALLOWS));
        assertFalse(logged(CLINIT_ABANDON));
        assertTrue(indexOfLog("Cancelling thread:") < indexOfLog("Run still not finished"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void workerSurvivingEveryStepIsRetiredAndTheExecutorRotated() throws Exception {
        DefaultTestCase test = testOf(Sut.class, "resistEverything");
        int rotations = executor.getTotalRotationCount();
        int stalledBefore = executor.getNumStalledThreads();
        int stuckBefore = clinitStuck().size();
        int threadsBefore = executor.threadCounter;

        ExecutionResult result = executor.execute(test, 300);
        Thread abandoned = sutThread;

        assertTimeoutRecorded(test, result);
        assertTrue(abandoned.isAlive());
        assertTrue(interrupts > 0, "the worker was interrupted while it was being waited for");
        assertEquals(Thread.MIN_PRIORITY, abandoned.getPriority());
        assertEquals(rotations + 1, executor.getTotalRotationCount());
        assertEquals(stalledBefore + 1, stalled().size());
        assertTrue(stalled().contains(abandoned));
        assertEquals(stuckBefore, clinitStuck().size(), "not an initializer: re-interrupted when counted");
        assertNull(workerThread(), "the replacement worker has not been created yet");
        assertStreamsHandedBack();

        assertTrue(logged(FORCE_STOPPING + " " + abandoned.getName() + " via Thread.stop()"));
        assertTrue(logged(ROTATING));
        assertTrue(logged(STALLED_TRACE));
        assertTrue(logged("Executor rotation #" + executor.getTotalRotationCount() + " complete."));
        assertTrue(indexOfLog("Run still not finished") < indexOfLog(FORCE_STOPPING));
        assertTrue(indexOfLog(FORCE_STOPPING) < indexOfLog(ROTATING));
        assertTrue(indexOfLog(ROTATING) < indexOfLog(STALLED_TRACE));
        assertFalse(logged(SWALLOWS));

        // The next test runs on a new, differently named thread; the abandoned one is left alone.
        assertTrue(executor.execute(testOf(Sut.class, "quick"), 5000).noThrownExceptions());
        Thread replacement = workerThread();
        assertNotSame(abandoned, replacement);
        assertEquals(threadsBefore + 2, executor.threadCounter);
        assertEquals(TestCaseExecutor.TEST_EXECUTION_THREAD + "_" + (threadsBefore + 1), replacement.getName());
        assertTrue(replacement.isDaemon());
        assertTrue(abandoned.isAlive());

        release.countDown();
        abandoned.join(3000);
        assertFalse(abandoned.isAlive());
        assertEquals(stalledBefore + 1, stalled().size(), "pruned only when the stalled threads are counted");
        assertEquals(stalledBefore, executor.getNumStalledThreads());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testSwallowingTheKillSwitchGoesStraightToForceStop() throws Exception {
        DefaultTestCase test = testOf(Sut.class, "spinSwallowingTimeout");
        int rotations = executor.getTotalRotationCount();

        long start = System.currentTimeMillis();
        ExecutionResult result = executor.execute(test, 300);
        long elapsed = System.currentTimeMillis() - start;
        Thread abandoned = sutThread;

        assertTimeoutRecorded(test, result);
        assertTrue(logged("it is looping through code that swallows TimeoutExceeded"));
        assertTrue(logged(SWALLOWS));
        assertTrue(logged(FORCE_STOPPING + " " + abandoned.getName() + " via Thread.stop()"));
        // The graceful chain, which the fast path skips, is where this message is logged.
        assertFalse(logged("Cancelling thread:"));
        assertTrue(indexOfLog(SWALLOWS) < indexOfLog(FORCE_STOPPING));
        assertStreamsHandedBack();
        assertTrue(elapsed < 2500, "took " + elapsed + "ms");
        // The loop catches the ThreadDeath of the stop too, so the worker is retired.
        assertTrue(abandoned.isAlive());
        assertEquals(rotations + 1, executor.getTotalRotationCount());
        assertTrue(stalled().contains(abandoned));
        assertTrue(logged(ROTATING));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void workerStuckInStaticInitializerIsAbandonedNotInterrupted() throws Exception {
        DefaultTestCase test = testOf(StuckInitializer.class, "answer");
        int rotations = executor.getTotalRotationCount();
        int stalledBefore = executor.getNumStalledThreads();
        int stuckBefore = clinitStuck().size();
        Set<String> abandonedBefore = new java.util.HashSet<>(abandonedClinits());

        ExecutionResult result = executor.execute(test, 300);
        Thread abandoned = sutThread;

        assertTimeoutRecorded(test, result);
        assertNotNull(abandoned);
        assertTrue(abandoned.isAlive());
        assertFalse(abandoned.isInterrupted(), "an initializer that is left alone is not interrupted");
        assertEquals(Thread.MIN_PRIORITY, abandoned.getPriority());
        assertEquals(rotations + 1, executor.getTotalRotationCount());
        assertEquals(stalledBefore + 1, stalled().size());
        assertEquals(stuckBefore + 1, clinitStuck().size());
        assertTrue(clinitStuck().contains(abandoned));
        assertEquals(abandonedBefore.size() + 1, abandonedClinits().size());
        assertTrue(abandonedClinits().contains(StuckInitializer.class.getName()));
        assertStreamsHandedBack();

        assertTrue(logged("Timeout happened inside <clinit> of "));
        assertTrue(logged("Deferring thread interrupt while <clinit> grace handling is active."));
        assertTrue(logged("Static initializer of "));
        assertTrue(logged(CLINIT_ABANDON));
        assertTrue(logged(ROTATING));
        assertFalse(logged(FORCE_STOPPING));
        assertFalse(logged("Cancelling thread:"));
        assertTrue(indexOfLog("Timeout happened inside <clinit>") < indexOfLog(CLINIT_ABANDON));
        assertTrue(indexOfLog(CLINIT_ABANDON) < indexOfLog(ROTATING));

        // Counting the stalled threads leaves the abandoned initializer alone ...
        assertEquals(stalledBefore + 1, executor.getNumStalledThreads());
        assertFalse(abandoned.isInterrupted());

        // ... and it finishes normally once released: the class is not poisoned.
        release.countDown();
        abandoned.join(3000);
        assertFalse(abandoned.isAlive());
        assertEquals(42, StuckInitializer.answer());
        assertEquals(stalledBefore, executor.getNumStalledThreads());
        assertEquals(stuckBefore, clinitStuck().size(), "forgotten with the dead thread");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void mockedSleepPollingEndsAtTheCallBudgetWithoutATimeout() throws Exception {
        MockFramework.enable();
        MockThread.reset();
        DefaultTestCase test = testOf(Sut.class, "mockedSleepLoop");
        int rotations = executor.getTotalRotationCount();

        ExecutionResult result = executor.execute(test, 20_000);

        assertFalse(result.hasTimeout());
        Throwable thrown = result.getExceptionThrownAtPosition(0);
        assertTrue(thrown instanceof TooManyResourcesException, String.valueOf(thrown));
        assertTrue(thrown.getMessage().contains("exceeded 10000 sleep() calls"), thrown.getMessage());
        assertEquals(rotations, executor.getTotalRotationCount());
        assertStreamsHandedBack();
        assertFalse(logged("TimeoutException"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void mockedWaitLoopEndsAtTheWaitBudgetWithoutATimeout() throws Exception {
        MockFramework.enable();
        MockObjectWait.reset();
        DefaultTestCase test = testOf(Sut.class, "mockedWaitLoop");
        int rotations = executor.getTotalRotationCount();

        ExecutionResult result = executor.execute(test, 20_000);

        assertFalse(result.hasTimeout());
        Throwable thrown = result.getExceptionThrownAtPosition(0);
        assertTrue(thrown instanceof TooManyResourcesException, String.valueOf(thrown));
        assertTrue(thrown.getMessage().contains("cumulative wait request exceeded 10000ms"),
                thrown.getMessage());
        assertEquals(rotations, executor.getTotalRotationCount());
        assertStreamsHandedBack();
        assertFalse(logged("TimeoutException"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void mockedSleepLoopThatSwallowsItsBudgetIsStoppedByTheTimeout() throws Exception {
        MockFramework.enable();
        MockThread.reset();
        DefaultTestCase test = testOf(Sut.class, "mockedSleepLoopSwallowing");
        int rotations = executor.getTotalRotationCount();

        ExecutionResult result = executor.execute(test, 300);
        Thread abandoned = sutThread;

        assertTimeoutRecorded(test, result);
        assertStreamsHandedBack();
        assertTrue(logged("TimeoutException, need to stop runner"));
        // The loop swallows the stop as well as the budget exception: retired and rotated.
        assertTrue(abandoned.isAlive());
        assertEquals(rotations + 1, executor.getTotalRotationCount());
        assertTrue(stalled().contains(abandoned));
    }
}
