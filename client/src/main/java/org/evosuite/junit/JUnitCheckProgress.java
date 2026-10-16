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
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * What a JUnit check run is doing right now, and the only place that changes the state of its
 * runner thread. The runner reports into it through the listener of the JUnit flavour in use
 * ({@link #junit4Listener()}, {@link #junit5Listener(List)}); the thread waiting for the runner
 * reads it to tell a test that hung from a run that is merely slow, and acts on the runner only
 * through the methods here.
 *
 * <h2>Invariant</h2>
 * Every change to the state of the runner happens inside {@code synchronized (transition)}: the
 * phase ({@link Phase}), the event count, the running class and test, whether this class sent the
 * runner an interrupt for a hang or for the budget of the check, whether the runner returned from
 * the run, and the recording of finished tests into the partial result. The waiting thread acts on
 * a test of the runner (interrupt, stop, abandon) only through methods that take the event count
 * it judged and do nothing if it has changed or the runner returned from the run; it gives up on
 * the runner as a whole only through methods that say whether they abandoned it, and walks away
 * from the runner (sandbox, class loader) only when they did.
 * A runner that has been abandoned parks for good, and it parks outside the lock, so the waiting
 * thread never waits for it. No critical section calls into the objects the code under test
 * reported: the runner's sections only record what JUnit reported, and the waiting thread's only
 * copy those records. Turning them into a result calls into them (an exception's
 * {@code getMessage()}, {@code printStackTrace()}), and happens after the lock is released, so
 * code under test that waits for the runner to go on never waits for a runner that waits for the
 * lock. It also happens on a thread of its own, holding no lock, for a limited time: if the code
 * under test does not answer in time, the failures are reported by the class of their exceptions
 * alone, and the waiting thread goes on. (Interrupting the runner, done under the lock, can run
 * the {@code implCloseChannel()} of a channel it is blocked in; that channel would have to be one
 * the code under test implements.)
 *
 * <h2>Interleavings</h2>
 * Runner side ({@link #classStarted}, {@link #classFinished}, {@link #testStarted},
 * {@link #testFinished}): each is one critical section that first checks for
 * {@link Phase#ABANDONED} and then, only if the runner is not abandoned, records and moves on.
 * An abandonment is therefore ordered either before the section, and the runner parks without
 * recording or moving on, or after it, and the runner parks at its next callback. Nothing the
 * runner does after an abandonment can reach the copy the waiting thread took.
 * <ul>
 * <li>{@link #interruptHungTest}: blames and interrupts only while the runner is in the test that
 * was judged; the runner cannot leave that test meanwhile, because {@link #testFinished} needs
 * the lock. If the test then ends without consuming the interrupt, {@link #testFinished} clears it
 * in the same section that moves the runner out of the test, so no {@code @After} of a later test,
 * {@code @AfterClass}, {@code @BeforeClass} of the next class or the runner's own cleanup sees it;
 * and no hang interrupt is sent after that section, because the event count has changed.</li>
 * <li>{@link #interruptForBudget}: marks the budget interrupt and sends it in one section. The
 * clearing in {@link #testFinished} checks the mark in its own section, so it either runs before
 * the budget interrupt is sent (and clears only the interrupt for the hang) or sees the mark and
 * clears nothing. The runner's interrupt status is never cleared once the budget ran out.</li>
 * <li>{@link #stopIfStill}: sends {@code Thread.stop()} only while the runner is in the judged test.
 * The stop is delivered asynchronously, so it can still land after the runner left the test,
 * including inside one of the runner-side sections; that aborts the callback and, usually, the
 * run, which the waiting thread then sees as a runner that died after a test hung.</li>
 * <li>{@link #abandonIfStill}: checks the judged event, abandons and copies the partial result in
 * one section. A runner that finished the test first has moved the event on, and the call returns
 * null without abandoning anything; otherwise the runner parks at the end of the test it hung in
 * and the result blames that test.</li>
 * <li>A runner that returned from the run has said so in {@link #runEnded}, its last section, and
 * is left alone from then on: {@link #interruptHungTest}, {@link #stopIfStill},
 * {@link #abandonIfStill} and {@link #interruptForBudget} check for it in their own section. A
 * stop that lands outside the test can end the run while the test is still shown as running, and
 * the thread is then back in its executor, not in the test.</li>
 * <li>{@link #abandonAndCollect} (and {@link #abandon}, which copies nothing): abandons and copies
 * the partial result in one section, whatever the runner is doing, and says whether it abandoned
 * it. A test that finishes later parks in {@link #testFinished} before it is recorded, so every
 * test is either in the result or still shown as running; none is finished but missing. A runner
 * that returned from the run first has said so in {@link #runEnded}, its last section, and is not
 * abandoned; one abandoned first returns, if ever, with nothing recorded. The waiting thread
 * therefore walks away from a runner only when this call abandoned it, never from one that was
 * done.</li>
 * </ul>
 * The listener callbacks never throw: a JUnit 4 listener that throws is removed by JUnit, and the
 * progress would then stop following the run. Where a callback would have to refuse, it parks.
 *
 * <p>The engines clear the interrupt status themselves as a test ends (JUnit 4.13 before reporting
 * it finished, Jupiter after), so what {@link #testFinished} clears is an interrupt for the hang
 * that arrived after that. The same clearing by the engine can swallow a budget interrupt that
 * lands inside a test; this class cannot prevent that, only never add to it.
 *
 * <p>Known limit: there is no callback between the last {@code testFinished} of a class and its
 * {@code @AfterClass}, so a runner abandoned in that window runs that cleanup before it parks in
 * {@link #classFinished}. It parks before the {@code @BeforeClass} of any further class.
 */
final class JUnitCheckProgress {

    private static final Logger logger = LoggerFactory.getLogger(JUnitCheckProgress.class);

    /** Exception class name reported for a test that hung; not a class that exists. */
    static final String HUNG_TEST_EXCEPTION = "org.evosuite.junit.HungTest";

    /** Where the runner is. */
    enum Phase {
        /**
         * Class rules, {@code @BeforeClass}/{@code @AfterClass}, or between tests and classes.
         */
        CLASS_LEVEL,
        /** Inside a test, from the moment it started until it finished, its fixture included. */
        IN_TEST,
        /** Given up on by the waiting thread: the runner parks at its next callback. */
        ABANDONED
    }

    /**
     * What the runner is doing at one moment. Immutable and replaced as a whole, so a reader gets
     * the test, the time it started at and the event count from the same moment: reading them one
     * by one lets the runner move to the next test in between, and the waiting thread would blame
     * one test and interrupt another.
     */
    static final class Snapshot {
        private final long event;
        private final Phase phase;
        private final String className;
        private final String test;
        private final long sinceNanos;

        Snapshot(long event, Phase phase, String className, String test, long sinceNanos) {
            this.event = event;
            this.phase = phase;
            this.className = className;
            this.test = test;
            this.sinceNanos = sinceNanos;
        }

        /**
         * The class being executed.
         *
         * @return the class, or null if not known
         */
        String getRunningClass() {
            return className;
        }

        /**
         * The event count.
         *
         * @return a counter that changes whenever a test or class starts or finishes
         */
        long getEventCount() {
            return event;
        }

        /**
         * The phase.
         *
         * @return where the runner is
         */
        Phase getPhase() {
            return phase;
        }

        /**
         * The test being executed.
         *
         * @return the test, or the one an abandoned runner was in; null outside tests
         */
        String getRunningTest() {
            return test;
        }

        /**
         * How long the test has been executing.
         *
         * @return the time in milliseconds, or 0 outside tests
         */
        long getRunningMillis() {
            if (phase != Phase.IN_TEST) {
                return 0L;
            }
            return Math.max(0L, (System.nanoTime() - sinceNanos) / 1_000_000L);
        }

        private Snapshot abandoned() {
            return new Snapshot(event, Phase.ABANDONED, className, test, sinceNanos);
        }
    }

    /** Guards every change to the runner's state; see the class comment. */
    private final Object transition = new Object();
    /**
     * Written only under {@link #transition}; read by anyone.
     */
    private volatile Snapshot state = new Snapshot(0L, Phase.CLASS_LEVEL, null, null, 0L);
    /**
     * The thread this progress interrupted for a hang, until the test ends. Under {@link #transition}.
     */
    private Thread hangInterruptSentTo;
    /**
     * Set once the runner was interrupted because the budget of the check ran out. Under
     * {@link #transition}.
     */
    private boolean budgetInterruptSent;
    /**
     * Set once the runner returned from the run without having been abandoned. Under
     * {@link #transition}.
     */
    private boolean runEnded;
    /**
     * Where the tests recorded so far are copied from. Set by the listener before the run; the
     * copy is taken only under {@link #transition}.
     */
    private volatile RecordedSoFar partialResult;

    /**
     * Copies what the runner recorded so far, as JUnit reported it, and turns the copy into a
     * result later. The copy is taken under {@link #transition} and must not call into the
     * reported exceptions; the result is built after the lock is released, since building it
     * runs code under test ({@code getMessage()}, {@code printStackTrace()} of its exceptions).
     */
    @FunctionalInterface
    interface RecordedSoFar {
        /**
         * Copies the tests recorded so far.
         *
         * @return the copy
         */
        Recorded copy();
    }

    /** A copy of the tests the runner recorded, as JUnit reported them. */
    interface Recorded {
        /**
         * Builds the result, with the message and stack trace of every failure. Calls into the
         * exceptions of the code under test, which can take any time.
         *
         * @return the result
         */
        JUnitResult build();

        /**
         * Builds the result without calling into the code under test: each failure is reported
         * by the class of its exception alone.
         *
         * @return the result
         */
        JUnitResult buildWithoutDetail();
    }

    /**
     * How long reading the failures of a partial result may take in all, before they are reported
     * by the class of their exceptions alone.
     */
    static final long FAILURE_DETAIL_LIMIT_MS = 5_000L;
    /** The tests that ran past the limit, with how long they had run when they were interrupted. */
    private final Map<String, HungTest> hungTests = new ConcurrentHashMap<>();

    /** A test that ran past the limit. The class is null if it is not known. */
    private static final class HungTest {
        private final String className;
        private final String method;
        private final long ranMs;

        HungTest(String className, String method, long ranMs) {
            this.className = className;
            this.method = method;
            this.ranMs = ranMs;
        }

        /**
         * Whether a failure is one of this test. The class has to be the same, unknown on both
         * sides included: the failures of a test and its progress take the class from the same
         * description, and a test whose class is unknown (JUnit 5 without a method source) shares
         * its name with the tests of every other generated class ({@code test00}).
         */
        boolean matches(JUnitFailure failure) {
            return method.equals(failure.getDescriptionMethodName())
                    && Objects.equals(className, failure.getDescriptionClassName());
        }

        HungTest longer(HungTest other) {
            return other.ranMs > ranMs ? other : this;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Runner side
    // ---------------------------------------------------------------------------------------

    /**
     * Records that a class (or another container of tests) starts, before its class rules and
     * {@code @BeforeClass} run. An abandoned runner parks here instead.
     *
     * @param className the class, or null if the container is not a class
     */
    void classStarted(String className) {
        synchronized (transition) {
            if (state.phase != Phase.ABANDONED) {
                state = new Snapshot(state.event + 1, Phase.CLASS_LEVEL, className, null, 0L);
                return;
            }
        }
        parkForGood();
    }

    /**
     * Records that a class (or another container of tests) finished, after its {@code @AfterClass}.
     * An abandoned runner parks here instead.
     *
     * @param className    the class, or null if the container is not a class
     * @param recordResult records what the class reported into the partial result
     */
    void classFinished(String className, Runnable recordResult) {
        synchronized (transition) {
            if (state.phase != Phase.ABANDONED) {
                record(recordResult);
                state = new Snapshot(state.event + 1, Phase.CLASS_LEVEL, null, null, 0L);
                return;
            }
        }
        parkForGood();
    }

    /**
     * Records that a test starts, before its fixture runs. An abandoned runner parks here instead.
     *
     * @param className the class of the test, or null if not known
     * @param name      the name of the test method, without parentheses
     */
    void testStarted(String className, String name) {
        long now = System.nanoTime();
        String test = name == null ? "<unknown>" : name;
        synchronized (transition) {
            if (state.phase != Phase.ABANDONED) {
                state = new Snapshot(state.event + 1, Phase.IN_TEST, className, test, now);
                return;
            }
        }
        parkForGood();
    }

    /**
     * Records that the running test finished, however it ended, its fixture included. The test is
     * recorded in the same section that moves the runner out of it, so it is either in the partial
     * result or still shown as running. An interrupt this progress sent for a hang is cleared here,
     * the last moment it can still be pending for this test, unless the budget of the whole check
     * ran out meanwhile. An abandoned runner parks here instead, without recording the test.
     *
     * @param recordResult records the test into the partial result
     */
    void testFinished(Runnable recordResult) {
        synchronized (transition) {
            if (state.phase != Phase.ABANDONED) {
                record(recordResult);
                state = new Snapshot(state.event + 1, Phase.CLASS_LEVEL, null, null, 0L);
                if (hangInterruptSentTo != null) {
                    if (!budgetInterruptSent && hangInterruptSentTo == Thread.currentThread()) {
                        Thread.interrupted();
                    }
                    hangInterruptSentTo = null;
                }
                return;
            }
        }
        parkForGood();
    }

    /**
     * Records that the runner returned from the run, however it ended. A runner that is not
     * abandoned by then can no longer be: it runs nothing more of the check.
     */
    void runEnded() {
        synchronized (transition) {
            if (state.phase != Phase.ABANDONED) {
                runEnded = true;
            }
        }
    }

    private static void record(Runnable recordResult) {
        try {
            recordResult.run();
        } catch (RuntimeException e) {
            // Thrown out of a listener, it would make JUnit 4 drop the listener for the rest of the
            // run; the test then counts as not recorded, which only loses detail.
            logger.warn("Could not record a finished test of the JUnit check: {}", e.toString());
        }
    }

    private void parkForGood() {
        // An orphan that wakes up must not start another test, nor run the class level code of
        // the suite (which resets global state the work that went on without it depends on).
        // Nothing wakes it: a parked daemon thread costs nothing, and it is given up on already.
        while (true) {
            LockSupport.park(this);
            Thread.interrupted();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Waiting side
    // ---------------------------------------------------------------------------------------

    /**
     * Whether the runner is still in the test of the event the caller judged. A runner that
     * returned from the run is in no test, even if the test it was in never finished (a stop that
     * landed outside the test ends the run that way): its thread is back in its executor, and
     * abandoning it would keep the class loader of the check for a runner that is gone. Under
     * {@link #transition}.
     */
    private boolean stillIn(long event) {
        Snapshot current = state;
        return !runEnded && current.event == event && current.phase == Phase.IN_TEST;
    }

    /**
     * Interrupts the runner and blames the test it is in, provided that is still the test the
     * caller saw.
     *
     * @param event  the event count of the snapshot the caller judged
     * @param runner the runner thread
     * @param ranMs  how long the test had run
     * @return false if the runner had moved on and was left alone
     */
    boolean interruptHungTest(long event, Thread runner, long ranMs) {
        synchronized (transition) {
            if (!stillIn(event)) {
                return false;
            }
            markHung(state.className, state.test, ranMs);
            if (runner != null) {
                hangInterruptSentTo = runner;
                runner.interrupt();
            }
            return true;
        }
    }

    /**
     * Last resort for a test that spins in code an interrupt does not reach, such as a loop in a
     * library class that is not instrumented: stops the runner thread, as the test executor does
     * during the search, provided it is still in the test the caller saw. The test then fails with
     * a ThreadDeath, which the JUnit engines record like any other failure before going on to the
     * next test. Not available from Java 20 on, where {@code Thread.stop()} only throws.
     *
     * @param event  the event count of the snapshot the caller judged
     * @param runner the runner thread
     * @return true if the thread was sent the stop
     */
    boolean stopIfStill(long event, Thread runner) {
        synchronized (transition) {
            if (!stillIn(event)) {
                return false;
            }
            return stop(runner);
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static boolean stop(Thread runner) {
        if (runner == null || !runner.isAlive()) {
            return false;
        }
        try {
            logger.warn("Stopping thread {} with Thread.stop()", runner.getName());
            runner.stop();
            return true;
        } catch (Throwable t) {
            logger.info("Cannot stop thread {}: {}", runner.getName(), t.toString());
            return false;
        }
    }

    /**
     * Interrupts the runner because the budget of the whole check ran out, unless it was
     * abandoned or returned from the run. From then on its interrupt status is never cleared by
     * this progress.
     *
     * @param runner the runner thread, or null if it never started
     */
    void interruptForBudget(Thread runner) {
        synchronized (transition) {
            budgetInterruptSent = true;
            if (runner != null && state.phase != Phase.ABANDONED && !runEnded) {
                runner.interrupt();
            }
        }
    }

    /**
     * Gives up on the runner if it is still in the test the caller saw: the runner parks as soon
     * as that test ends, and the result is whatever finished before, plus a failure for that test
     * and every test that hung before.
     *
     * @param event     the event count of the snapshot the caller judged
     * @param hungMs    how long the test had been running
     * @param testCount the number of test classes the check covered
     * @return the result of the abandoned run, or null if the runner had moved on and was left alone
     */
    JUnitResult abandonIfStill(long event, long hungMs, int testCount) {
        Recorded recorded;
        synchronized (transition) {
            if (!stillIn(event)) {
                return null;
            }
            state = state.abandoned();
            markHung(state.className, state.test, hungMs);
            recorded = copyRecorded();
        }
        return build(recorded, testCount);
    }

    /**
     * Gives up on the runner wherever it is, unless it has returned from the run, and takes what it
     * recorded so far. Only a call that abandoned the runner warrants walking away from it: the
     * runner of a call that did not either returned, and released what it held, or was abandoned
     * before.
     *
     * @param testCount the number of test classes the check covered
     * @return whatever finished before, plus a failure for every test that hung, and whether this
     *         call abandoned the runner
     */
    Abandonment abandonAndCollect(int testCount) {
        Recorded recorded;
        boolean abandoned;
        synchronized (transition) {
            abandoned = abandonUnlessDone();
            recorded = copyRecorded();
        }
        return new Abandonment(abandoned, build(recorded, testCount));
    }

    /**
     * Gives up on the runner wherever it is, unless it has returned from the run, as
     * {@link #abandonAndCollect} does, without taking what it recorded.
     *
     * @return whether this call abandoned the runner; false if the runner had returned from the
     *         run or was abandoned before
     */
    boolean abandon() {
        synchronized (transition) {
            return abandonUnlessDone();
        }
    }

    private boolean abandonUnlessDone() {
        if (state.phase == Phase.ABANDONED || runEnded) {
            return false;
        }
        state = state.abandoned();
        return true;
    }

    /**
     * What {@link #abandonAndCollect} did.
     */
    static final class Abandonment {
        private final boolean abandoned;
        private final JUnitResult result;

        Abandonment(boolean abandoned, JUnitResult result) {
            this.abandoned = abandoned;
            this.result = result;
        }

        /**
         * Whether the call abandoned the runner.
         *
         * @return false if the runner had returned from the run or was abandoned before
         */
        boolean abandonedTheRunner() {
            return abandoned;
        }

        /**
         * The result.
         *
         * @return whatever finished before, plus a failure for every test that hung
         */
        JUnitResult getResult() {
            return result;
        }
    }

    /**
     * The result of a run that ended because the runner thread died, which is what stopping the
     * thread of a hung test can do when the stop lands outside the test: whatever finished
     * before, plus a failure for each test that hung.
     *
     * @param testCount the number of test classes the check covered
     * @return the result of the run
     */
    JUnitResult deadRunnerResult(int testCount) {
        Recorded recorded;
        synchronized (transition) {
            recorded = copyRecorded();
        }
        return build(recorded, testCount);
    }

    /**
     * Copies what the runner recorded so far. Under {@link #transition}, so it runs no code under
     * test.
     */
    private Recorded copyRecorded() {
        RecordedSoFar recorded = partialResult;
        if (recorded == null) {
            return null;
        }
        try {
            return recorded.copy();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Builds the result out of a copy of what the runner recorded. Runs code under test, so never
     * under {@link #transition}: a {@code getMessage()} that waits for the runner to go on would
     * otherwise wait for a runner that waits for the lock.
     */
    private JUnitResult build(Recorded recorded, int testCount) {
        JUnitResult result = null;
        if (recorded != null) {
            result = buildInTime(recorded);
        }
        if (result == null) {
            result = new JUnitResult(false, 0, testCount);
        }
        return blameHungTests(result);
    }

    /**
     * Builds the result on a thread of its own, and without the detail of the failures if that
     * takes longer than {@link #FAILURE_DETAIL_LIMIT_MS}. The code under test can make a message
     * or stack trace wait forever (for the abandoned runner, say), and the thread that waits for
     * the check must not wait with it: nothing else would end the check. The reader holds no lock,
     * and is left behind if it never returns; it is a daemon and costs a thread.
     */
    private static JUnitResult buildInTime(Recorded recorded) {
        FutureTask<JUnitResult> detailed = new FutureTask<>(recorded::build);
        Thread reader = new Thread(detailed, "evosuite-junit-check-failure-reader");
        reader.setDaemon(true);
        try {
            reader.start();
            return detailed.get(FAILURE_DETAIL_LIMIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Reading the failures of the JUnit check took longer than {} ms; "
                    + "reporting them by the class of their exceptions", FAILURE_DETAIL_LIMIT_MS);
        } catch (ExecutionException e) {
            // not toString(): that would call into the exception of the code under test again
            logger.warn("Could not read the failures of the JUnit check: {}", e.getCause().getClass().getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | OutOfMemoryError e) {
            // no thread to read them on
            logger.warn("Could not start reading the failures of the JUnit check: {}", e.getClass().getName());
        }
        try {
            return recorded.buildWithoutDetail();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Readers
    // ---------------------------------------------------------------------------------------

    /**
     * What the runner is doing.
     *
     * @return the event count, phase, test and running time as of one moment
     */
    Snapshot snapshot() {
        return state;
    }

    /**
     * Whether the runner moved on from the moment the caller saw.
     *
     * @param event an event count the caller saw
     * @return whether the runner started or finished a test or class since
     */
    boolean movedOnFrom(long event) {
        return state.event != event;
    }

    /**
     * Sets where the failures found so far can be read from. Set by the listeners before the run.
     *
     * @param partialResult copies the tests recorded so far
     */
    void setPartialResult(RecordedSoFar partialResult) {
        this.partialResult = partialResult;
    }

    /**
     * Remembers a test that ran past the limit. Interrupting or stopping it can let the test end
     * without a failure, for example when the code under test runs it inside a task that records
     * the exception, so the test has to be blamed whatever its outcome. A test marked again keeps
     * the longest time it was seen running, which is the one at its abandonment.
     *
     * @param className the class of the test, or null if not known
     * @param name      the test
     * @param ranMs     how long it had run
     */
    void markHung(String className, String name, long ranMs) {
        hungTests.merge(className + "#" + name, new HungTest(className, name, ranMs), HungTest::longer);
    }

    /**
     * Makes the result blame every test that hung. A test that was interrupted usually has a
     * failure of its own by then, the timeout of the engine, which the check treats as the limit
     * of the search being reproduced and ignores; that failure is replaced by the one that
     * blames the hang, so the test is dropped from the suite like any other test that fails the
     * check. The failure count follows: each failure removed is one less, the blame one more.
     *
     * @param result the result of the run
     * @return the result
     */
    JUnitResult blameHungTests(JUnitResult result) {
        for (HungTest hung : hungTests.values()) {
            boolean alreadyBlamed = false;
            int removed = 0;
            Iterator<JUnitFailure> failures = result.getFailures().iterator();
            while (failures.hasNext()) {
                JUnitFailure failure = failures.next();
                if (hung.matches(failure)) {
                    if (HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName())) {
                        alreadyBlamed = true;
                    } else {
                        failures.remove();
                        removed++;
                    }
                }
            }
            int added = 0;
            if (!alreadyBlamed) {
                String message = "test did not finish within " + hung.ranMs
                        + " ms and had to be interrupted";
                result.addFailure(new JUnitFailure(message, HUNG_TEST_EXCEPTION, hung.className, hung.method,
                        false, message));
                result.setSuccessful(false);
                added = 1;
            }
            result.setFailureCount(Math.max(0, result.getFailureCount() + added - removed));
        }
        return result;
    }

    /**
     * Whether a test hung.
     *
     * @return whether a test had to be interrupted during this run
     */
    boolean hasHungTests() {
        return !hungTests.isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------------------------------

    /**
     * A JUnit 4 listener that reports the run into this progress and keeps the partial result.
     * {@code testSuiteStarted}/{@code testSuiteFinished} are fired by every {@code ParentRunner}
     * (JUnit 4.13), around class rules and {@code @BeforeClass}/{@code @AfterClass}; the outer
     * suite of {@code JUnitCore.run(Class...)} fires them too and counts as class level.
     *
     * @return the listener, to be added to the {@code JUnitCore} of the run
     */
    RunListener junit4Listener() {
        return new JUnit4Listener();
    }

    /**
     * A JUnit 5 listener that reports the run into this progress and keeps the partial result.
     *
     * @param finished where the results of finished tests and containers are recorded, also read
     *                 for the result of a run that completes
     * @return the listener, to be registered with the launcher of the run
     */
    TestExecutionListener junit5Listener(List<Pair<TestIdentifier, TestExecutionResult>> finished) {
        setPartialResult(() -> {
            List<Pair<TestIdentifier, TestExecutionResult>> copy = new ArrayList<>(finished);
            return new Recorded() {
                @Override
                public JUnitResult build() {
                    return new JUnitResultBuilder().build(copy);
                }

                @Override
                public JUnitResult buildWithoutDetail() {
                    return new JUnitResultBuilder().buildWithoutDetail(copy);
                }
            };
        });
        return new TestExecutionListener() {
            @Override
            public void executionStarted(TestIdentifier identifier) {
                if (identifier.isTest()) {
                    testStarted(JUnitResultBuilder.classNameOf(identifier),
                            JUnitResultBuilder.methodNameOf(identifier));
                } else {
                    classStarted(JUnitResultBuilder.classNameOfContainer(identifier));
                }
            }

            @Override
            public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                Runnable recordResult = () -> finished.add(Pair.of(identifier, result));
                if (identifier.isTest()) {
                    testFinished(recordResult);
                } else {
                    classFinished(JUnitResultBuilder.classNameOfContainer(identifier), recordResult);
                }
            }
        };
    }

    /**
     * Thread safe without JUnit's wrapper, which would hold the monitor of the notifier while
     * an abandoned runner parks in a callback.
     */
    @RunListener.ThreadSafe
    private final class JUnit4Listener extends RunListener {
        /** Failures of the test or class in progress, recorded when it finishes. */
        private final List<Failure> pending = new CopyOnWriteArrayList<>();
        private final List<Failure> failuresSoFar = new CopyOnWriteArrayList<>();
        private final AtomicInteger finishedSoFar = new AtomicInteger();

        JUnit4Listener() {
            setPartialResult(() -> {
                List<Failure> failures = new ArrayList<>(failuresSoFar);
                int finished = finishedSoFar.get();
                return new Recorded() {
                    @Override
                    public JUnitResult build() {
                        return partial(failures, finished, JUnitResultBuilder::toFailure);
                    }

                    @Override
                    public JUnitResult buildWithoutDetail() {
                        return partial(failures, finished, JUnitResultBuilder::toFailureWithoutDetail);
                    }
                };
            });
        }

        private JUnitResult partial(List<Failure> failures, int finished,
                                    Function<Failure, JUnitFailure> convert) {
            JUnitResult partial = new JUnitResult(false, failures.size(), finished);
            for (Failure failure : failures) {
                partial.addFailure(convert.apply(failure));
            }
            return partial;
        }

        private void takePending() {
            for (Failure failure : pending) {
                failuresSoFar.add(failure);
                pending.remove(failure);
            }
        }

        @Override
        public void testSuiteStarted(Description description) {
            classStarted(description.getClassName());
        }

        @Override
        public void testSuiteFinished(Description description) {
            classFinished(description.getClassName(), this::takePending);
        }

        @Override
        public void testStarted(Description description) {
            JUnitCheckProgress.this.testStarted(description.getClassName(), description.getMethodName());
        }

        @Override
        public void testFailure(Failure failure) {
            pending.add(failure);
        }

        @Override
        public void testFinished(Description description) {
            JUnitCheckProgress.this.testFinished(() -> {
                takePending();
                finishedSoFar.incrementAndGet();
            });
        }
    }
}
