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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What a JUnit check run is doing right now: the test it is executing and for how long. The
 * runner thread reports into it through the listener of the JUnit flavour in use, and the thread
 * waiting for the runner reads it to tell a test that hung from a run that is merely slow.
 */
class JUnitCheckProgress {

    /** Exception class name reported for a test that hung; not a class that exists. */
    static final String HUNG_TEST_EXCEPTION = "org.evosuite.junit.HungTest";

    /**
     * What the runner is doing at one moment. Immutable and replaced as a whole, so a reader gets
     * the test, the time it started at and the event count from the same moment: reading them one
     * by one lets the runner move to the next test in between, and the waiting thread would blame
     * one test and interrupt another.
     */
    static final class Snapshot {
        private final long event;
        private final String test;
        private final long sinceNanos;

        Snapshot(long event, String test, long sinceNanos) {
            this.event = event;
            this.test = test;
            this.sinceNanos = sinceNanos;
        }

        /** @return a counter that changes whenever a test starts or finishes */
        long getEventCount() {
            return event;
        }

        /** @return the test being executed, or null between tests */
        String getRunningTest() {
            return test;
        }

        /** @return how long the test has been executing, or 0 between tests */
        long getRunningMillis() {
            if (test == null) {
                return 0L;
            }
            return Math.max(0L, (System.nanoTime() - sinceNanos) / 1_000_000L);
        }
    }

    /** Changes whenever a test starts or finishes, so a test can be recognised as the same one. */
    private final java.util.concurrent.atomic.AtomicReference<Snapshot> state =
            new java.util.concurrent.atomic.AtomicReference<>(new Snapshot(0L, null, 0L));
    private volatile Supplier<JUnitResult> partialResult;
    /** The tests that ran past the limit, with how long they had run when they were interrupted. */
    private final Map<String, Long> hungTests = new ConcurrentHashMap<>();

    /**
     * Records that a test started.
     *
     * @param name the name of the test method, without parentheses
     */
    void testStarted(String name) {
        long now = System.nanoTime();
        String test = name == null ? "<unknown>" : name;
        state.updateAndGet(previous -> new Snapshot(previous.event + 1, test, now));
    }

    /** Records that the running test finished, however it ended. */
    void testFinished() {
        state.updateAndGet(previous -> new Snapshot(previous.event + 1, null, 0L));
    }

    /**
     * Sets where the failures found so far can be read from.
     *
     * @param partialResult builds a result out of the tests that have finished
     */
    void setPartialResult(Supplier<JUnitResult> partialResult) {
        this.partialResult = partialResult;
    }

    /**
     * @return the test being executed, or null between tests
     */
    String getRunningTest() {
        return state.get().getRunningTest();
    }

    /**
     * @return the event count, test and running time as of one moment
     */
    Snapshot snapshot() {
        return state.get();
    }

    /**
     * @return how long the running test has been executing, or 0 between tests
     */
    long getRunningMillis() {
        return state.get().getRunningMillis();
    }

    /**
     * @return a counter that changes whenever a test starts or finishes
     */
    long getEventCount() {
        return state.get().getEventCount();
    }

    /**
     * Remembers a test that ran past the limit. Interrupting or stopping it can let the test end
     * without a failure, for example when the code under test runs it inside a task that records
     * the exception, so the test has to be blamed whatever its outcome.
     *
     * @param name  the test
     * @param ranMs how long it had run
     */
    void markHung(String name, long ranMs) {
        hungTests.putIfAbsent(name, ranMs);
    }

    /**
     * Makes the result blame every test that hung. A test that was interrupted usually has a
     * failure of its own by then, the timeout of the engine, which the check treats as the limit
     * of the search being reproduced and ignores; that failure is replaced by the one that
     * blames the hang, so the test is dropped from the suite like any other test that fails the
     * check.
     *
     * @param result the result of the run
     * @return the result
     */
    JUnitResult blameHungTests(JUnitResult result) {
        for (Map.Entry<String, Long> hung : hungTests.entrySet()) {
            boolean alreadyBlamed = false;
            java.util.Iterator<JUnitFailure> failures = result.getFailures().iterator();
            while (failures.hasNext()) {
                JUnitFailure failure = failures.next();
                if (hung.getKey().equals(failure.getDescriptionMethodName())) {
                    if (HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName())) {
                        alreadyBlamed = true;
                    } else {
                        failures.remove();
                    }
                }
            }
            if (!alreadyBlamed) {
                String message = "test did not finish within " + hung.getValue()
                        + " ms and had to be interrupted";
                result.addFailure(new JUnitFailure(message, HUNG_TEST_EXCEPTION, hung.getKey(), false, message));
                result.setSuccessful(false);
            }
        }
        return result;
    }

    /**
     * @return whether a test had to be interrupted during this run
     */
    boolean hasHungTests() {
        return !hungTests.isEmpty();
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
        return abandonedResult(null, 0L, testCount);
    }

    /**
     * Builds the result of a run that was given up on while one test hung: whatever finished
     * before it, plus a failure for the test that hung and for every test that hung before.
     *
     * @param culprit   the test that hung
     * @param hungMs    how long it had been running
     * @param testCount the number of test classes the check covered
     * @return the result of the abandoned run
     */
    JUnitResult abandonedResult(String culprit, long hungMs, int testCount) {
        Supplier<JUnitResult> supplier = partialResult;
        JUnitResult result = null;
        if (supplier != null) {
            try {
                result = supplier.get();
            } catch (RuntimeException e) {
                result = null;
            }
        }
        if (result == null) {
            result = new JUnitResult(false, 0, testCount);
        }
        if (culprit != null) {
            markHung(culprit, hungMs);
        }
        return blameHungTests(result);
    }
}
