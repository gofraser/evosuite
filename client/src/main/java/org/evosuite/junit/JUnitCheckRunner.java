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
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.runner.JUnitCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs the compiled tests of a JUnit check on a worker thread under a watchdog. The runner can be
 * abandoned if it hangs or becomes unrecoverable, and the check then goes on without it.
 */
final class JUnitCheckRunner {

    private static final Logger logger = LoggerFactory.getLogger(JUnitCheckRunner.class);

    private JUnitCheckRunner() {
    }

    /**
     * Set when a JUnit check runner had to be abandoned while it may still be executing code
     * through the current check loader; that loader is then left alone rather than released
     * under the orphan's feet.
     */
    private static volatile boolean checkRunnerAbandoned = false;

    private static final VersionDependentAnalyzing JUNIT4_ANALYZER = new JUnit4Analyzing();
    private static final VersionDependentAnalyzing JUNIT5_ANALYZER = new JUnit5Analyzing();

    /**
     * Test-only override for {@link #getVersionDependentAnalyzer()}. {@code null} in production
     * (and never set outside tests). The call it replaces happens inside the submitted
     * {@link #runnerTask} of {@link #run(Class[])}, on a dedicated worker
     * thread -- Mockito's static/construction mocking only intercepts calls made from the thread
     * that registered the mock, so it cannot reach that call. This field is the seam that lets a
     * test make the runner invocation itself throw (e.g. simulating an {@link OutOfMemoryError}
     * that already escaped JUnit's own per-test handling), the same way
     * {@code TestSuiteGenerator.freeMemoryBytesForTesting} overrides a production supplier for
     * tests.
     */
    static VersionDependentAnalyzing VERSION_DEPENDENT_ANALYZER_FOR_TESTING = null;

    /**
     * Runs the test classes of a JUnit check and returns what they did, or a result synthesized
     * for a run that had to be given up.
     *
     * @param testClasses the test classes to run
     * @return the result of the run
     */
    static JUnitResult run(Class<?>[] testClasses) {
        // Generation leaves the tracer enabled (TestCaseExecutor re-enables it in
        // its timeout finally block). During JUnit check we don't consume the
        // trace, so leaving it on only causes instrumented testability/mutation
        // hooks in the SUT to feed ConstantPoolManager and tracer state on every
        // comparison — measurable hang on hot loops.
        boolean wasTracerEnabled = ExecutionTracer.isEnabled();
        if (wasTracerEnabled) {
            ExecutionTracer.disable();
        }

        // Force LoopCounter on for the JUnit check. The instrumented SUT classes
        // call LoopCounter.checkLoop() at every loop back-edge, but the check
        // is a no-op unless the counter is "activated". Several paths
        // (ClassStateSupport.initializeClasses, TestCaseExecutor's <clinit>
        // grace handling, Properties.getTargetClassAndDontInitialise) save the
        // current state, set it false, and restore it — so if the counter was
        // ever left off when JUnit check began, runaway SUT loops won't terminate
        // here even though they did during search.
        boolean wasLoopCheckOn = LoopCounter.getInstance().isActivated();
        if (!wasLoopCheckOn) {
            LoopCounter.getInstance().setActive(true);
        }

        // Run JUnit on a dedicated worker thread so we can abandon it if it
        // becomes unrecoverable. A common failure mode is OOM inside the SUT:
        // JUnit catches it and tries to record it via Throwable.addSuppressed,
        // which is synchronized and itself allocates — under memory pressure
        // this cascades into an unrecoverable RUNNABLE state where neither
        // interrupt() nor the kill switch can free the thread (interrupt is
        // only checked on exit from the synchronized block, which never
        // happens). We give the runner the configured budget, then a grace
        // window after interrupt, and finally synthesize an all-failed result
        // and walk away from the orphan thread.
        long budgetMs = computeWatchdogBudgetMs(1000L * Math.max(1, Properties.JUNIT_CHECK_TIMEOUT),
                remainingTimeInPhaseMs());
        long graceMs = 30_000L;
        // Register the runner thread as privileged at thread creation, not
        // post-hoc. The body calls Sandbox.resetDefaultSecurityManager() (and
        // may load classes through InstrumentingClassLoader), both of which
        // require setSecurityManager / privileged class-load permissions; an
        // unprivileged thread would have those rejected by MSecurityManager.
        // Doing this in the ThreadFactory means (a) the registration runs on
        // the caller (master) thread, which is already privileged, so
        // addPrivilegedThread passes its caller-must-be-privileged check; and
        // (b) any thread the executor creates is privileged from the start,
        // not just the first one — the previous submit(Thread::currentThread)
        // dance was fragile if the executor ever replaced the worker.
        boolean sandboxOn = Sandbox.isSecurityManagerInitialized();
        // Remembered so that, if we end up abandoning this thread below, we can hand back the
        // sandbox lock it may still be holding. The generated scaffolding enters the sandbox, and
        // an abandoned runner never leaves it, which used to strand the lock for the rest of the
        // process and fail every later test execution on the acquisition timeout.
        AtomicReference<Thread> runnerThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "evosuite-junit-check-runner");
            t.setDaemon(true);
            if (sandboxOn) {
                Sandbox.addPrivilegedThread(t);
            }
            runnerThread.set(t);
            return t;
        });
        JUnitCheckProgress progress = new JUnitCheckProgress();
        // SUT threads left over from the tests (fixsuite's LibrarySuite starts one from the event
        // dispatch thread) outlive the generated test that turns the mock framework off, so their
        // System.exit is the real one. The runner takes the sandbox down for the run, and the JVM
        // exited whenever such a call came while no manager was installed, losing the suite.
        Sandbox.setJvmExitRefused(true);
        Future<JUnitResult> future = executor.submit(runnerTask(testClasses, progress));
        boolean firedTimeout = false;
        boolean gaveUp = false;
        try {
            try {
                return awaitRunner(future, runnerThread, progress, budgetMs, hangLimitMs(), hangGraceMs(),
                        testClasses.length);
            } catch (TimeoutException te) {
                firedTimeout = true;
                logger.warn("JUnit check exceeded watchdog budget of {} ms; "
                        + "setting kill switch and interrupting runner", budgetMs);
                ExecutionTracer.setKillSwitch(true);
                // Interrupt the runner directly rather than cancelling the Future. cancel(true)
                // interrupts too, but it also moves the Future to CANCELLED, and every later
                // get() then throws CancellationException instead of returning the runner's
                // result or timing out - which made the grace window below unreachable and sent
                // us down a path that discarded the whole suite. Interrupting the thread leaves
                // the Future live, so a runner that unwinds in time still reports its real
                // result, and one that does not falls through to the keep-the-suite branch.
                // The progress sends it, so that it is never cleared as a leftover of a hang.
                progress.interruptForBudget(runnerThread.get());
            }
            return resultAfterBudgetRanOut(future, runnerThread.get(), progress, graceMs, testClasses.length);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Synthesized result, not an observed one: every test is reported failed without
            // ever having been checked, so this is a give-up like the others above.
            gaveUp = true;
            JUnitCheckState.recordIncomplete("JUnit check runner interrupted while awaiting its result: " + ie);
            return JUnitResult.allFailed(testClasses.length, ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            // Same reasoning as the InterruptedException branch above: the result is synthesized,
            // not observed.
            gaveUp = true;
            JUnitCheckState.recordIncomplete("JUnit check runner failed unexpectedly while awaiting its result: " + cause);
            return JUnitResult.allFailed(testClasses.length, cause);
        } finally {
            abandonRunnerLeftRunning(future, progress, runnerThread.get(), gaveUp);
            // Interrupts a runner that has either returned or been abandoned; an abandoned one
            // parks at its next callback whatever the interrupt does to the code it is in.
            executor.shutdownNow();
            // Whichever way we leave, the runner is done with the sandbox: it either returned a
            // result (and released it itself, making this a no-op) or we are walking away from it.
            // That covers exits the paths above do not, such as an Error escaping this method while
            // it waits (OutOfMemoryError under heap pressure), an interrupt of this thread, or a
            // runner that died inside the sandbox without reaching its own release. Without it the
            // dead runner's lock stalls the next test execution for the whole acquisition timeout.
            Sandbox.reclaimSandboxLockOwnedBy(runnerThread.get());
            // Not even past a runner that was abandoned: the client has to be able to exit itself.
            Sandbox.setJvmExitRefused(false);
            if (firedTimeout) {
                // Clear any signals we raised so subsequent work isn't poisoned.
                // Note: cannot clear the orphan runner's interrupt — it isn't us.
                ExecutionTracer.setKillSwitch(false);
            }
            if (wasTracerEnabled) {
                ExecutionTracer.enable();
            }
            if (!wasLoopCheckOn) {
                LoopCounter.getInstance().setActive(false);
            }
        }
    }

    /**
     * What the runner thread of a JUnit check executes: the run, and then, however it ended, the
     * report to the progress that the runner returned from it.
     *
     * @param testClasses the test classes to run
     * @param progress    what the runner does
     * @return the task
     */
    static Callable<JUnitResult> runnerTask(Class<?>[] testClasses, JUnitCheckProgress progress) {
        return () -> {
            try {
                return getVersionDependentAnalyzer().runJUnitOnCurrentProcess(testClasses, progress);
            } catch (OutOfMemoryError e) {
                // Test triggered a huge allocation (e.g. new DefaultTableModel(351774, 351774)).
                // Force GC to reclaim before reporting back.
                System.gc();
                logger.warn("OutOfMemoryError during JUnit execution — forced GC and treating all tests as failed");
                // This OOM escaped the whole JUnit runner (run), rather than
                // being caught and attributed to one test the way JUnit itself catches per-test
                // throwables; the result below carries no per-test failure info, so callers that
                // read result.getFailures() (e.g. handleTestsThatAreUnstable) see an empty list
                // and treat this exactly like a run that observed nothing wrong. Record it the
                // same way the other synthesized-result give-ups in this method already do.
                JUnitCheckState.recordIncomplete("OutOfMemoryError escaped the JUnit runner: " + e);
                return JUnitResult.allFailed(testClasses.length, e);
            } finally {
                // The runner runs nothing more of the check: from here on it is not abandoned, so
                // its class loader is released and the sandbox not taken from it.
                progress.runEnded();
            }
        };
    }

    /**
     * The result of a check whose budget ran out and whose runner was interrupted for it.
     *
     * <p>Grace window: the runner gets a chance to unwind after the interrupt. If it does not
     * (typical for OOM-suppression cascades stuck in synchronized addSuppressed), the check is
     * abandoned and the tests are treated as passing: the suite already survived search, and
     * there is no observed failure for any specific test, so dropping the whole suite would be
     * more punitive than skipping the phase. The orphan thread parks at its next callback; the
     * shutdown of its executor cannot kill it. A runner that returns from the run just as the
     * grace runs out is not abandoned, and reports what it found.
     *
     * @param future    the runner's result
     * @param runner    the runner thread, or null if it never started
     * @param progress  what the runner did
     * @param graceMs   how long the runner gets to unwind
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired, unless the check was
     *         cancelled
     */
    static JUnitResult resultAfterBudgetRanOut(Future<JUnitResult> future, Thread runner,
                                               JUnitCheckProgress progress, long graceMs, int testCount)
            throws InterruptedException, ExecutionException {
        try {
            return resultWithinGrace(future, progress, graceMs, testCount);
        } catch (TimeoutException te) {
            JUnitCheckProgress.Abandonment abandonment = progress.abandonAndCollect(testCount);
            String reason = "runner did not respond " + graceMs + " ms after interrupt";
            if (abandonment.abandonedTheRunner()) {
                logger.error("JUnit check did not respond {} ms after interrupt; "
                        + "abandoning JUnit check and keeping the suite as-is. "
                        + "The orphan runner thread parks at its next test or class boundary.",
                        graceMs);
                abandonRunner(runner, reason);
                return afterWatchdogAbandonedRunner(abandonment.getResult(), progress, testCount);
            }
            // It returned from the run just as the grace ran out, so there is nothing to walk away
            // from, and its result is handed to the future as it returns: take what it found.
            JUnitResult returned = resultOfRunnerThatReturned(future, progress, graceMs, testCount);
            if (returned != null) {
                return returned;
            }
            JUnitCheckState.recordIncomplete(reason);
            return afterWatchdogAbandonedRunner(abandonment.getResult(), progress, testCount);
        } catch (CancellationException ce) {
            // The check was cancelled rather than completed, so it observed no failure for
            // any specific test. Treating that as "every test failed" would throw away a
            // suite the search already produced because the *check* did not finish, which
            // is the wrong trade: report the tests as passing and skip the phase.
            logger.error("JUnit check was cancelled before completing; "
                    + "keeping the suite as-is without checking it.");
            String reason = "runner was cancelled before completing";
            if (progress.abandon()) {
                abandonRunner(runner, reason);
            } else {
                JUnitCheckState.recordIncomplete(reason);
            }
            return JUnitResult.allPassed(testCount);
        }
    }

    /**
     * The result of a runner that returned from the run, read as soon as it is handed over. The
     * runner reports that it returned before its result reaches the future, so the wait is short;
     * it is bounded all the same.
     *
     * @param future    the runner's result
     * @param progress  what the runner did
     * @param waitMs    how long to wait for the result at most
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired, or null if the runner
     *         did not complete the run with a result after all
     */
    private static JUnitResult resultOfRunnerThatReturned(Future<JUnitResult> future, JUnitCheckProgress progress,
                                                          long waitMs, int testCount)
            throws InterruptedException {
        try {
            return resultWithinGrace(future, progress, waitMs, testCount);
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            logger.warn("The JUnit check runner returned from the run without a result: {}", e.toString());
            return null;
        }
    }

    /**
     * Abandons the runner if it is still going when the wait for it ends. That is left by an exit
     * the paths of the wait do not handle, such as an interrupt of the waiting thread or an Error
     * escaping the wait: the runner must not go on with the suite alongside the work that follows.
     * Whether it is still going is decided by the progress, at the moment it abandons it, so a
     * runner that returns in the meantime is neither abandoned nor walked away from (which would
     * keep the class loader of the check from being released).
     *
     * @param future         the runner's result
     * @param progress       what the runner did
     * @param runner         the runner thread, or null if it never started
     * @param giveUpRecorded whether the exit already recorded the check as incomplete
     * @return whether the runner was abandoned
     */
    static boolean abandonRunnerLeftRunning(Future<JUnitResult> future, JUnitCheckProgress progress,
                                            Thread runner, boolean giveUpRecorded) {
        if (future.isDone() || !progress.abandon()) {
            return false;
        }
        Sandbox.abandonThread(runner);
        checkRunnerAbandoned = true;
        if (!giveUpRecorded) {
            JUnitCheckState.recordIncomplete("runner was still going when the wait for it ended");
        }
        return true;
    }

    /**
     * Whether a runner was abandoned since the class loader of the check was last released.
     *
     * @return whether the class loader of the current check is to be kept for an abandoned runner
     */
    static boolean isCheckRunnerAbandoned() {
        return checkRunnerAbandoned;
    }

    /**
     * Reads and clears the abandonment of the runner, for the release of the class loader of the
     * check it belonged to.
     *
     * @return whether a runner was abandoned since the last time this was called
     */
    static boolean consumeCheckRunnerAbandoned() {
        boolean abandoned = checkRunnerAbandoned;
        checkRunnerAbandoned = false;
        return abandoned;
    }

    /**
     * Collects the result of a runner that was interrupted because the budget of the check ran
     * out, within the grace period. The result is judged like the one of a run that finished in
     * time: tests that were caught hanging are blamed, and a runner that died after a test had to
     * be stopped reports what it had found, not a failure of every test.
     *
     * @param future    the runner's result
     * @param progress  what the runner did
     * @param graceMs   how long the runner gets to unwind
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired
     * @throws TimeoutException if the runner did not unwind in time
     */
    static JUnitResult resultWithinGrace(Future<JUnitResult> future, JUnitCheckProgress progress,
                                         long graceMs, int testCount)
            throws InterruptedException, ExecutionException, TimeoutException {
        JUnitResult result;
        try {
            result = progress.blameHungTests(future.get(graceMs, TimeUnit.MILLISECONDS));
        } catch (ExecutionException died) {
            result = resultOfDeadRunner(died, progress, testCount);
        }
        result.setWatchdogFired(true);
        return result;
    }

    /**
     * Makes the result of a check whose runner ignored the interrupt of the watchdog. The suite is
     * kept as it is, except that tests caught hanging before the budget ran out are still to be
     * dropped. As for any result produced after the watchdog fired, the timeouts in it say nothing
     * about the tests: the kill switch was raised for the run.
     *
     * @param collected what the runner recorded until it was abandoned
     * @param progress  what the runner did
     * @param testCount the number of test classes the check covers
     * @return the result, flagged as produced after the watchdog fired
     */
    static JUnitResult afterWatchdogAbandonedRunner(JUnitResult collected, JUnitCheckProgress progress,
                                                    int testCount) {
        JUnitResult result = progress.hasHungTests() ? collected : JUnitResult.allPassed(testCount);
        result.setWatchdogFired(true);
        return result;
    }

    /**
     * The result of a run whose runner thread died. Stopping the thread of a hung test can raise
     * the stop outside the test, in the engine, and end the run: the tests that hung are known,
     * the ones after them were not run. A runner that died without any test having hung is a
     * failure of the check itself.
     *
     * @param died      how the runner ended
     * @param progress  what the runner did
     * @param testCount the number of test classes the check covers
     * @return what the run found
     * @throws ExecutionException if no test hung, so there is nothing to report but the failure
     */
    private static JUnitResult resultOfDeadRunner(ExecutionException died, JUnitCheckProgress progress,
                                                  int testCount) throws ExecutionException {
        if (!progress.hasHungTests()) {
            throw died;
        }
        logger.warn("The JUnit check runner died after a test had to be stopped: {}", died.toString());
        JUnitCheckState.recordIncomplete("runner died after a hung test was stopped");
        return progress.deadRunnerResult(testCount);
    }

    /**
     * How long the runner gets to unwind after a test that ran too long was interrupted, before
     * the test counts as hung.
     */
    static final long HANG_GRACE_MS = 10_000L;

    /** Longest a single test may run before it counts as hung, however short its own timeout. */
    static final long MIN_HANG_LIMIT_MS = 30_000L;

    /**
     * Test-only override for {@link #hangGraceMs()}; 0 in production.
     */
    static volatile long HANG_GRACE_FOR_TESTING_MS = 0L;

    static long hangGraceMs() {
        return HANG_GRACE_FOR_TESTING_MS > 0 ? HANG_GRACE_FOR_TESTING_MS : HANG_GRACE_MS;
    }

    /**
     * Test-only override for {@link #hangLimitMs()}; 0 in production.
     */
    static volatile long HANG_LIMIT_FOR_TESTING_MS = 0L;

    /**
     * How long one test may execute before the runner is interrupted. The generated tests carry a
     * timeout of their own, a few seconds, so a test that runs ten times as long has not merely
     * been slow: it either ignores the interrupt that timeout sends or it is blocked where an
     * interrupt cannot reach it.
     *
     * @return the limit in milliseconds
     */
    static long hangLimitMs() {
        if (HANG_LIMIT_FOR_TESTING_MS > 0) {
            return HANG_LIMIT_FOR_TESTING_MS;
        }
        return Math.max(MIN_HANG_LIMIT_MS, 10L * Math.max(1, Properties.TIMEOUT));
    }

    /**
     * Waits for the runner of a JUnit check, and gives up on a single test that hangs instead of
     * waiting out the budget of the whole check.
     *
     * <p>A test can hang where nothing can free it: two threads of the code under test deadlocked
     * on class initialization, with the test thread waiting for one of them, cannot be
     * interrupted. The runner then never reaches the next test, and the watchdog used to wait for
     * the whole budget (20 minutes by default), plus its grace period, only to learn nothing about
     * which test was to blame. Here a test that runs past the limit gets one interrupt; if it is
     * still the same test after the grace period, the runner is abandoned and the result blames
     * that test, which is dropped from the suite like any other test that fails the check.
     *
     * @param future       the runner's result
     * @param runnerThread holds the runner thread
     * @param progress     what the runner is doing
     * @param budgetMs     how long the whole run may take
     * @param hangLimitMs  how long one test may run before it is interrupted
     * @param hangGraceMs  how long the runner gets to move on after that interrupt
     * @param testCount    the number of test classes the check covers
     * @return the runner's result, or the result blaming the test that hung
     * @throws TimeoutException if the whole run exceeded its budget
     */
    static JUnitResult awaitRunner(Future<JUnitResult> future, AtomicReference<Thread> runnerThread,
                                   JUnitCheckProgress progress, long budgetMs, long hangLimitMs,
                                   long hangGraceMs, int testCount)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (true) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                throw new TimeoutException();
            }
            try {
                return progress.blameHungTests(future.get(Math.min(remainingMs, 200L), TimeUnit.MILLISECONDS));
            } catch (TimeoutException stillRunning) {
                // fall through to the hang check
            } catch (ExecutionException died) {
                return resultOfDeadRunner(died, progress, testCount);
            }
            // One snapshot: the runner can move to the next test at any moment, and reading the
            // test and the event count separately could blame one test and interrupt the next.
            JUnitCheckProgress.Snapshot seen = progress.snapshot();
            String running = seen.getRunningTest();
            if (running == null || seen.getRunningMillis() < hangLimitMs) {
                continue;
            }
            long event = seen.getEventCount();
            long ranMs = seen.getRunningMillis();
            Thread runner = runnerThread.get();
            // Blaming and interrupting happen while the runner cannot move to another test, so the
            // interrupt lands on the test that hung or not at all.
            if (!progress.interruptHungTest(event, runner, ranMs)) {
                continue; // it finished or moved on while we looked
            }
            logger.warn("Test {} has been running for {} ms in the JUnit check; interrupted the runner",
                    running, ranMs);
            if (waitForRunnerToMoveOn(future, progress, event, hangGraceMs)) {
                continue;
            }
            if (progress.stopIfStill(event, runner)
                    && waitForRunnerToMoveOn(future, progress, event, hangGraceMs)) {
                logger.warn("Test {} of the JUnit check was stopped after it ignored the interrupt", running);
                continue;
            }
            // Checking that the runner is still in the test and abandoning it happen at once, so a
            // runner that finishes the test meanwhile is not abandoned in the next one.
            JUnitResult result = progress.abandonIfStill(event, ranMs + 2 * hangGraceMs, testCount);
            if (result == null) {
                continue; // it moved on before it could be abandoned
            }
            logger.error("Test {} of the JUnit check ignored the interrupt for {} ms; abandoned the "
                    + "runner and dropped the test. The orphan runner thread parks once the test ends.",
                    running, ranMs + 2 * hangGraceMs);
            abandonRunner(runner, "test " + running + " hung and ignored the interrupt");
            return result;
        }
    }

    /**
     * Waits for the runner to start or finish a test, so it is no longer in the one it was in.
     *
     * @return true if the runner moved on, or finished the run
     */
    private static boolean waitForRunnerToMoveOn(Future<JUnitResult> future, JUnitCheckProgress progress,
                                                 long event, long graceMs) throws InterruptedException {
        long graceEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
        while (System.nanoTime() < graceEnd) {
            if (progress.movedOnFrom(event) || future.isDone()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return progress.movedOnFrom(event) || future.isDone();
    }

    /** Shortest watchdog budget a check gets, even when its phase is (nearly) over. */
    static final long MIN_WATCHDOG_BUDGET_MS = 1_000L;

    /**
     * Returns how long the watchdog lets a single JUnit run take: the configured JUnit check
     * timeout, but never more than what is left of the current phase. Each call used to get
     * the full configured timeout (20 minutes by default) however little of the phase was
     * left, so a slow check could overrun the phase, and the whole run, by that much.
     *
     * @param configuredMs     the configured JUnit check timeout
     * @param remainingPhaseMs time left in the current phase, or {@link Long#MAX_VALUE} if unknown
     * @return the watchdog budget in milliseconds
     */
    static long computeWatchdogBudgetMs(long configuredMs, long remainingPhaseMs) {
        long budget = Math.min(configuredMs, Math.max(0L, remainingPhaseMs));
        return Math.max(MIN_WATCHDOG_BUDGET_MS, budget);
    }

    private static long remainingTimeInPhaseMs() {
        try {
            return TimeController.getInstance().getRemainingTimeInPhaseMs();
        } catch (RuntimeException e) {
            // No usable phase information: fall back to the configured timeout.
            logger.debug("Cannot determine the time left in the current phase", e);
            return Long.MAX_VALUE;
        }
    }

    static boolean isJUnit5AnalyzerSelectedForCurrentFormat() {
        return getVersionDependentAnalyzer() == JUNIT5_ANALYZER;
    }

    private static VersionDependentAnalyzing getVersionDependentAnalyzer() {
        if (VERSION_DEPENDENT_ANALYZER_FOR_TESTING != null) {
            return VERSION_DEPENDENT_ANALYZER_FOR_TESTING;
        }
        return Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5
                ? JUNIT5_ANALYZER
                : JUNIT4_ANALYZER;
    }


    /**
     * The bookkeeping for a JUnit check whose runner was abandoned through its
     * {@link JUnitCheckProgress}, which is what stops the runner: the orphan parks at its next
     * callback, after the rest of the code it is in (a test, its {@code @After}) has run. The kill
     * switch could not do that: it is global and cleared when the check ends.
     *
     * <p>The orphan never calls goingToEndTestCase(). The sandbox lock is taken back on its behalf
     * now rather than making the next test execution discover the leak by burning its whole
     * acquisition timeout.
     *
     * @param runner the runner thread, or null if it never started
     * @param reason why the check was abandoned
     */
    private static void abandonRunner(Thread runner, String reason) {
        Sandbox.abandonThread(runner);
        checkRunnerAbandoned = true;
        JUnitCheckState.recordIncomplete(reason);
    }

    /**
     * Class defining what functionality must be defined for different JUNIT versions.
     */
    private abstract static class VersionDependentAnalyzing {
        abstract JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress);
    }

    /**
     * Define functionality for JUnit 4 Tests.
     */
    private static class JUnit4Analyzing extends VersionDependentAnalyzing {
        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress) {
            JUnitCore runner = new JUnitCore();
            runner.addListener(progress.junit4Listener());
            return runInCheckEnvironment(testClasses, () -> runner.run(testClasses),
                    result -> new JUnitResultBuilder().build(result));
        }
    }


    /**
     * Define functionality for JUnit 5 tests.
     */
    private static class JUnit5Analyzing extends VersionDependentAnalyzing {

        @Override
        JUnitResult runJUnitOnCurrentProcess(Class<?>[] testClasses, JUnitCheckProgress progress) {
            List<Pair<TestIdentifier, TestExecutionResult>> result = new CopyOnWriteArrayList<>();
            return runInCheckEnvironment(testClasses, () -> {
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(Arrays.stream(testClasses).map(DiscoverySelectors::selectClass)
                                .collect(Collectors.toList()))
                        .build();
                Launcher launcher = LauncherFactory.create();
                launcher.registerTestExecutionListeners(progress.junit5Listener(result));
                launcher.execute(request);
                return result;
            }, executed -> new JUnitResultBuilder().build(executed));
        }
    }

    /**
     * Runs the given test classes on the current thread in the environment the JUnit check
     * needs, and restores the previous environment afterwards.
     *
     * @param testClasses the test classes to run
     * @param run         runs the classes and returns the runner's raw result
     * @param toResult    converts the raw result once the mock framework, headless mode and
     *                    context class loader have been restored
     */
    private static <T> JUnitResult runInCheckEnvironment(Class<?>[] testClasses, Supplier<T> run,
                                                         Function<T, JUnitResult> toResult) {
        /*
         * Why deactivating the sandbox? This is pretty tricky.
         * The JUnit runner will execute the test cases on a new
         * thread, which might not be privileged. If the test cases need
         * the JavaAgent, then they will fail due to the sandbox :(
         * Note: if the test cases need a sandbox, they will have code
         * to do that by their self. When they do it, the initialization
         * will be after the agent is already loaded.
         */
        boolean wasSandboxOn = Sandbox.isSecurityManagerInitialized();

        Set<Thread> privileged = null;
        if (wasSandboxOn) {
            privileged = Sandbox.resetDefaultSecurityManager();
        }
        try {
            T result;
            ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
            boolean wasMockFrameworkEnabled = MockFramework.isEnabled();
            boolean shouldEnableMockFramework = RuntimeSettings.mockJVMNonDeterminism
                    || Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
            boolean disabledHeadless = false;

            // Skip agent self-attachment during the in-process JUnit check.
            // Classes are already instrumented by InstrumentingClassLoader, and
            // ByteBuddyAgent.attach() can hang on some JDK/OS combinations.
            org.evosuite.runtime.agent.AgentLoader.setSkipAttach(true);
            try {
                if (shouldEnableMockFramework) {
                    MockFramework.enable();
                }
                if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                    GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
                    // Align JUnit recheck with search-time constructor execution:
                    // ConstructorStatement temporarily disables headless mode for GUI
                    // constructors, but JUnit executes plain Java source directly.
                    // Without this, GUI-heavy tests can fail only in recheck.
                    GuiSupport.disableHeadlessForMockConstruction();
                    disabledHeadless = true;
                }
                TestGenerationContext.getInstance().goingToExecuteSUTCode();
                Thread.currentThread().setContextClassLoader(testClasses[0].getClassLoader());
                // be sure we reset it here, otherwise "init" in the test case
                // would take current changed state
                JDKClassResetter.reset();
                result = run.get();
            } finally {
                Thread.currentThread().setContextClassLoader(currentLoader);
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
                org.evosuite.runtime.agent.AgentLoader.setSkipAttach(false);
                if (disabledHeadless) {
                    GuiSupport.restoreHeadlessAfterMockConstruction();
                }
                if (Properties.REPLACE_GUI || RuntimeSettings.mockGUI) {
                    GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
                }
                if (!wasMockFrameworkEnabled) {
                    MockFramework.disable();
                }
            }
            return toResult.apply(result);
        } finally {
            restoreSandboxStateAfterJUnitRun(wasSandboxOn, privileged);
        }
    }

    private static void restoreSandboxStateAfterJUnitRun(boolean wasSandboxOn, Set<Thread> privileged) {
        try {
            if (Sandbox.isSecurityManagerInitialized()) {
                // A test whose @After throws before reaching doneWithExecutingSUTCode() (for
                // example a class reset that fails) leaves this runner thread inside the sandbox:
                // still downgraded, still owning the sandbox lock. The suite's @AfterClass
                // cannot then restore the security manager, so the manager is retained, and the
                // dead runner's lock would stall every later test execution for the whole lock
                // timeout. This is a no-op when the suite left the sandbox cleanly.
                Sandbox.doneWithExecutingSUTCode();
            }
            if (wasSandboxOn) {
                // only activate Sandbox if it was already active before
                if (!Sandbox.isSecurityManagerInitialized()) {
                    org.evosuite.testcase.execution.ClientSandbox.activate(privileged);
                }
            } else if (Sandbox.isSecurityManagerInitialized()) {
                logger.warn("EvoSuite problem: tests set up a security manager, "
                        + "but they do not remove it after execution");
                Sandbox.resetDefaultSecurityManager();
            }
        } catch (RuntimeException e) {
            logger.error("Failed to restore sandbox state after JUnit check", e);
        }
    }
}
