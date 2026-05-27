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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTestsStoppingCondition;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.sandbox.PermissionStatistics;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.JOptionPaneInputs;
import org.evosuite.runtime.util.SystemInUtil;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.Statement;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * <p>
 * The test case executor manages thread creation/deletion to execute a test
 * case.
 * </p>
 *
 * <p>
 * WARNING: never give "privileged" rights in MSecurityManager to any of the
 * threads generated here.
 * </p>
 *
 * @author Gordon Fraser
 */
public class TestCaseExecutor implements ThreadFactory {

    /**
     * Used to identify the threads spawn by the SUT.
     */
    public static final String TEST_EXECUTION_THREAD_GROUP = "Test_Execution_Group";

    /**
     * Name used to define the threads spawn by this factory.
     */
    public static final String TEST_EXECUTION_THREAD = "TEST_EXECUTION_THREAD";

    private static final Logger logger = LoggerFactory.getLogger(TestCaseExecutor.class);

    private static final PrintStream systemOut = System.out;
    private static final PrintStream systemErr = System.err;

    private static TestCaseExecutor instance = null;

    private volatile ExecutorService executor;

    /**
     * Serializes top-level execute(...) calls across all callers.
     * TestCaseExecutor has shared mutable state (e.g., currentThread) and uses
     * a process-global sandbox flag, so concurrent execute invocations from
     * different threads are unsafe.
     */
    private final Object executionLock = new Object();

    /**
     * Lock that serializes test executions. Callers that need to perform
     * pre/post setup of process-wide state (e.g. {@code ClassReInitializer},
     * {@code MockFramework}, {@code GuiSupport} headless toggles) atomically
     * with the execution itself can {@code synchronized} on this object around
     * the whole prep+execute+cleanup block. The monitor is reentrant, so the
     * nested {@link #execute(TestCase)} call inside the block is safe.
     */
    public Object getExecutionLock() {
        return executionLock;
    }

    private Thread currentThread = null;

    private ThreadGroup threadGroup = null;

    // private static ExecutorService executor =
    // Executors.newCachedThreadPool();

    private Set<ExecutionObserver> observers;

    private final Set<Thread> stalledThreads = new HashSet<>();

    /**
     * Timestamp of the last executor rotation, used to enforce a cooldown
     * between rotations and prevent GC thrashing from rapid timeout cycling.
     */
    private long lastRotationTimestamp = 0;

    /**
     * Set to true inside the TimeoutException handler when the task is still
     * not done after all cleanup attempts. Used by the outer execute() to
     * decide whether rotation is needed — avoids the race condition where
     * a stack trace check sees the pool thread in a transient state.
     */
    private volatile boolean lastTaskStillRunning = false;

    /**
     * Cumulative count of executor rotations. Unlike {@link #stalledThreads},
     * this is never decremented when threads die, so it catches the pattern
     * where threads die quickly but cycling is pathological.
     */
    private int totalRotationCount = 0;

    /**
     * Number of consecutive test executions that ended in timeout.
     * Used to disable mocking when a SUT class consistently hangs.
     */
    private int consecutiveTimeoutCount = 0;

    /**
     * How many consecutive timeouts before we temporarily disable mocking.
     */
    private static final int CONSECUTIVE_TIMEOUT_THRESHOLD = 15;

    /**
     * After disabling mocking, re-enable it every N tests even if timeouts
     * continue.  This prevents a one-way trap for classes that need mocking
     * to reach non-blocking code paths.
     */
    private static final int MOCK_DISABLE_RETRY_INTERVAL = 30;

    /**
     * Saved values of mocking properties before timeout-based disabling.
     * Used to restore them if the timeout pattern breaks (i.e., a test
     * succeeds without timeout after mocking was disabled).
     */
    private double savedPFunctionalMocking = -1;
    private boolean savedMockIfNoGenerator = false;
    private boolean mockingDisabledDueToTimeouts = false;
    private int testsSinceMockingDisabled = 0;

    /**
     * Constant <code>timeExecuted=0</code>.
     */
    private static long timeExecuted = 0;

    /**
     * Constant <code>testsExecuted=0</code>.
     */
    private static int testsExecuted = 0;

    public static long getTimeExecuted() {
        return timeExecuted;
    }

    public static int getTestsExecuted() {
        return testsExecuted;
    }

    /**
     * Used when we spawn a new thread to give a unique name.
     */
    public volatile int threadCounter;

    static {
        PermissionStatistics.getInstance().setThreadGroupToMonitor(TEST_EXECUTION_THREAD_GROUP);
    }

    /**
     * <p>Getter for the field <code>instance</code>.</p>
     *
     * @return a {@link org.evosuite.testcase.execution.TestCaseExecutor} object.
     */
    public static synchronized TestCaseExecutor getInstance() {
        if (instance == null) {
            instance = new TestCaseExecutor();
        }

        return instance;
    }

    /**
     * Execute a test case.
     *
     * @param test The test case to execute
     * @return Result of the execution
     */
    public static ExecutionResult runTest(TestCase test) {

        ExecutionResult result = new ExecutionResult(test, null);

        try {
            TestCaseExecutor executor = getInstance();
            logger.debug("Executing test");
            result = executor.execute(test);

            MaxStatementsStoppingCondition.statementsExecuted(result.getExecutedStatements());

        } catch (Exception e) {
            logger.error("TG: Exception caught: ", e);
            throw new Error(e);
        }

        return result;
    }

    private TestCaseExecutor() {
        executor = Executors.newSingleThreadExecutor(this);
        newObservers();
    }

    public static class TimeoutExceeded extends RuntimeException {
        private static final long serialVersionUID = -5314228165430676893L;

        private final Integer statementPosition;

        private final String statementCode;

        public TimeoutExceeded() {
            this(null, null, null, null);
        }

        /**
         * Build a timeout marker exception whose stack trace reflects the worker
         * thread that was executing the test when the timeout fired.
         */
        public TimeoutExceeded(Thread workerThread) {
            this(workerThread == null
                            ? "Test execution timed out"
                            : "Test execution timed out in worker thread " + workerThread.getName(),
                    workerThread == null ? null : workerThread.getStackTrace(),
                    null,
                    null);
        }

        public TimeoutExceeded(String message, StackTraceElement[] stackTrace) {
            this(message, stackTrace, null, null);
        }

        public TimeoutExceeded(String message,
                               StackTraceElement[] stackTrace,
                               Integer statementPosition,
                               String statementCode) {
            super(message);
            this.statementPosition = statementPosition;
            this.statementCode = statementCode;
            if (stackTrace != null && stackTrace.length > 0) {
                setStackTrace(stackTrace);
            }
        }

        public Integer getStatementPosition() {
            return statementPosition;
        }

        public String getStatementCode() {
            return statementCode;
        }
    }

    /**
     * <p>
     * setup.
     * </p>
     */
    public void setup() {
        // start own thread
    }

    /**
     * <p>
     * pullDown.
     * </p>
     */
    public static void pullDown() {
        if (instance != null) {
            synchronized (instance) {
                if (instance.executor != null) {
                    instance.executor.shutdownNow();
                    instance.executor = null;
                }
            }
        }
    }

    /**
     * Returns true if the singleton executor exists and has a live thread pool,
     * i.e. it is safe to call {@link #runTest(TestCase)}.
     */
    public static boolean isAvailable() {
        if (instance == null) {
            return false;
        }
        ExecutorService snapshot = instance.executor;
        return snapshot != null && !snapshot.isShutdown() && !snapshot.isTerminated();
    }

    /**
     * <p>
     * initExecutor.
     * </p>
     */
    public static void initExecutor() {
        if (instance != null) {
            synchronized (instance) {
                if (instance.executor == null) {
                    logger.info("TestCaseExecutor instance is non-null, but its actual executor is null");
                    instance.executor = Executors.newSingleThreadExecutor(instance);
                } else {
                    instance.executor = Executors.newSingleThreadExecutor(instance);
                }
            }
        }
    }

    private ExecutionResult buildExecutorUnavailableResult(TestCase tc, String reason) {
        ExecutionResult result = new ExecutionResult(tc, null);
        // Use slot 0 instead of the terminal slot to avoid no-op chops in salvage
        // loops that truncate at the first failing statement.
        result.reportNewThrownException(0, new IllegalStateException(reason));
        result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
        ExecutionTracer.getExecutionTracer().clear();
        return result;
    }

    private ExecutorService getUsableExecutor() {
        ExecutorService snapshot;
        synchronized (this) {
            snapshot = executor;
        }
        if (snapshot == null || snapshot.isShutdown() || snapshot.isTerminated()) {
            return null;
        }
        return snapshot;
    }

    private ExecutionResult executeWithUnavailableGuard(TestCase tc,
                                                        TimeoutHandler<ExecutionResult> handler,
                                                        TestRunnable callable,
                                                        int timeout) throws InterruptedException,
            ExecutionException, TimeoutException {
        ExecutorService usableExecutor = getUsableExecutor();
        if (usableExecutor == null) {
            logger.debug("Skipping test execution because TestCaseExecutor has been pulled down.");
            return buildExecutorUnavailableResult(tc,
                    "TestCaseExecutor is unavailable (executor was pulled down)");
        }
        try {
            return handler.execute(callable, usableExecutor, timeout, Properties.CPU_TIMEOUT);
        } catch (RejectedExecutionException e) {
            logger.debug("Skipping test execution because TestCaseExecutor rejected task submission.", e);
            return buildExecutorUnavailableResult(tc,
                    "TestCaseExecutor rejected execution (executor shutting down)");
        }
    }

    /**
     * Clears adaptive timeout state on the existing singleton, if present.
     * Unlike {@link #getInstance()}, this method does not create a new executor.
     */
    public static void resetAdaptiveTimeoutStateIfPresent() {
        if (instance != null) {
            instance.resetAdaptiveTimeoutState();
        }
    }

    /**
     * Clears adaptive timeout/mocking state kept on this singleton between runs.
     * If timeout-adaptive logic previously disabled mocking, restore the
     * original property values before starting the next search.
     */
    public synchronized void resetAdaptiveTimeoutState() {
        consecutiveTimeoutCount = 0;
        testsSinceMockingDisabled = 0;
        if (mockingDisabledDueToTimeouts) {
            logger.info("Restoring functional mocking during context reset.");
            Properties.P_FUNCTIONAL_MOCKING = savedPFunctionalMocking;
            Properties.MOCK_IF_NO_GENERATOR = savedMockIfNoGenerator;
            mockingDisabledDueToTimeouts = false;
        }
    }

    /**
     * <p>
     * addObserver.
     * </p>
     *
     * @param observer a {@link org.evosuite.testcase.execution.ExecutionObserver}
     *                 object.
     */
    public void addObserver(ExecutionObserver observer) {
        if (!observers.contains(observer)) {
            logger.debug("Adding observer");
            observers.add(observer);
        }
    }

    /**
     * <p>
     * removeObserver.
     * </p>
     *
     * @param observer a {@link org.evosuite.testcase.execution.ExecutionObserver}
     *                 object.
     */
    public void removeObserver(ExecutionObserver observer) {
        if (observers.contains(observer)) {
            logger.debug("Removing observer");
            observers.remove(observer);
        }
    }

    /**
     * <p>
     * newObservers.
     * </p>
     */
    public void newObservers() {
        observers = new LinkedHashSet<>();
    }

    /**
     * Returns a copy of the current set of execution observers.
     *
     * @return a {@link java.util.Set} object containing the execution observers.
     */
    public Set<ExecutionObserver> getExecutionObservers() {
        return new LinkedHashSet<>(observers);
    }

    private void resetObservers() {
        for (ExecutionObserver observer : observers) {
            observer.clear();
        }
    }

    /**
     * Execute a test case on a new scope.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    public ExecutionResult execute(TestCase tc) {
        ExecutionResult result = execute(tc, Properties.TIMEOUT);
        return result;
    }

    /**
     * Execute a test case on a new scope.
     *
     * @param tc a {@link org.evosuite.testcase.TestCase} object.
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    public ExecutionResult execute(TestCase tc, int timeout) {
        synchronized (executionLock) {
            Scope scope = new Scope();
            ExecutionResult result;
            boolean timedOutExecution = false;
            boolean workerThreadStillAliveAfterTimeout = false;
            try {
                result = execute(tc, scope, timeout);
                timedOutExecution = result != null && result.hasTimeout();
                // Use the flag set by the inner execute()'s TimeoutException handler
                // which checks FutureTask.isDone() — this is authoritative and avoids
                // the race condition where stack trace inspection catches the pool
                // thread in a transient cleanup state.
                workerThreadStillAliveAfterTimeout = timedOutExecution && lastTaskStillRunning;
            } finally {
                clearMockInvocationsIfNeeded(tc, scope, timedOutExecution,
                        workerThreadStillAliveAfterTimeout);
            }
            if (timedOutExecution && workerThreadStillAliveAfterTimeout) {
                rotateExecutorAfterStalledTimeout();
            }

            // Track consecutive timeouts and temporarily disable mocking if the SUT
            // consistently hangs.  Unlike FunctionalMockStatement's own global disable
            // (which is permanent based on mock failure rates), this is reversible:
            //  - Immediately restored when a test succeeds without timeout.
            //  - Periodically restored after MOCK_DISABLE_RETRY_INTERVAL tests even
            //    if all tests keep timing out, to avoid a one-way trap where mocking
            //    is needed for some code paths but never gets re-enabled.
            if (timedOutExecution) {
                consecutiveTimeoutCount++;
                if (consecutiveTimeoutCount >= CONSECUTIVE_TIMEOUT_THRESHOLD
                        && !mockingDisabledDueToTimeouts
                        && (Properties.P_FUNCTIONAL_MOCKING > 0.0 || Properties.MOCK_IF_NO_GENERATOR)) {
                    logger.warn("Temporarily disabling functional mocking after {} consecutive timeouts "
                            + "to reduce mock class generation overhead.", consecutiveTimeoutCount);
                    savedPFunctionalMocking = Properties.P_FUNCTIONAL_MOCKING;
                    savedMockIfNoGenerator = Properties.MOCK_IF_NO_GENERATOR;
                    Properties.P_FUNCTIONAL_MOCKING = 0.0;
                    Properties.MOCK_IF_NO_GENERATOR = false;
                    mockingDisabledDueToTimeouts = true;
                    testsSinceMockingDisabled = 0;
                } else if (mockingDisabledDueToTimeouts) {
                    testsSinceMockingDisabled++;
                    if (testsSinceMockingDisabled >= MOCK_DISABLE_RETRY_INTERVAL) {
                        logger.info("Restoring functional mocking after {} tests without success "
                                + "— giving mocked tests another chance.", testsSinceMockingDisabled);
                        Properties.P_FUNCTIONAL_MOCKING = savedPFunctionalMocking;
                        Properties.MOCK_IF_NO_GENERATOR = savedMockIfNoGenerator;
                        mockingDisabledDueToTimeouts = false;
                        consecutiveTimeoutCount = 0;
                    }
                }
            } else {
                consecutiveTimeoutCount = 0;
                // Restore mocking if we previously disabled it and the timeout pattern broke
                if (mockingDisabledDueToTimeouts) {
                    logger.info("Restoring functional mocking — consecutive timeout streak broken.");
                    Properties.P_FUNCTIONAL_MOCKING = savedPFunctionalMocking;
                    Properties.MOCK_IF_NO_GENERATOR = savedMockIfNoGenerator;
                    mockingDisabledDueToTimeouts = false;
                }
            }

            if (Properties.RESET_STATIC_FIELDS) {
                logger.debug("Resetting classes after execution");
                ClassReInitializer.getInstance().reInitializeClassesAfterTestExecution(tc, result);
            }
            return result;
        }
    }

    /**
     * Execute a test case on an existing scope.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param scope a {@link org.evosuite.testcase.execution.Scope} object.
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    @SuppressWarnings({"deprecation", "removal"})
    private ExecutionResult execute(TestCase tc, Scope scope, int timeout) {
        lastTaskStillRunning = false;
        ExecutionTracer.getExecutionTracer().clear();

        int sanitizedLiterals = ConstantPoolManager.sanitizeTestCaseNumericLiterals(tc);
        if (sanitizedLiterals > 0) {
            logger.info("Sanitized {} oversized numeric literals before executing test {}", sanitizedLiterals,
                    tc.getID());
        }

        // TODO: Re-insert!
        resetObservers();
        ExecutionObserver.setCurrentTest(tc);
        MaxTestsStoppingCondition.testExecuted();
        Runtime.getInstance().resetRuntime();

        long startTime = System.currentTimeMillis();

        TimeoutHandler<ExecutionResult> handler = new TimeoutHandler<>();
        handler.setTaskThread(currentThread);

        // #TODO steenbuck could be nicer (TestRunnable should be an interface
        TestRunnable callable = new TestRunnable(tc, scope, observers);
        callable.storeCurrentThreads();

        /*
         * FIXME: the sequence of "catch" with calls to "result.set" should be
         * re-factored, as these things should be (already) handled in
         * TestRunnable.call. If not, it should be explained, as it is not
         * necessarily obvious why some checks are done here, and others in
         * TestRunnable
         */

        try {
            // ExecutionResult result = task.get(timeout,
            // TimeUnit.MILLISECONDS);

            ExecutionResult result = null;

            // important to call it before setting up the sandbox
            SystemInUtil.getInstance().initForTestCase();
            JOptionPaneInputs.getInstance().initForTestCase();

            Sandbox.goingToExecuteSUTCode();
            TestGenerationContext.getInstance().goingToExecuteSUTCode();
            try {
                result = executeWithUnavailableGuard(tc, handler, callable, timeout);
            } finally {
                Sandbox.doneWithExecutingSUTCode();
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
            }

            int activeThreadCount = threadGroup != null ? threadGroup.activeCount() : 0;
            PermissionStatistics.getInstance().countThreads(activeThreadCount);
            result.setSecurityException(PermissionStatistics.getInstance().getAndResetExceptionInfo());
            /*
             * TODO: this will need proper care when we ll start to handle
             * threads in the search.
             */
            callable.killAndJoinClientThreads();

            /*
             * TODO: we might want to initialize the ExecutionResult here, once
             * we waited for all SUT threads to finish
             */

            long endTime = System.currentTimeMillis();
            timeExecuted += endTime - startTime;
            testsExecuted++;
            if (logger.isDebugEnabled()) {
                int testSize = tc == null ? -1 : tc.size();
                Map<Integer, Throwable> snapshot = result.getCopyOfExceptionMapping();
                boolean hasTimeoutInSnapshot = testSize >= 0
                        && snapshot.containsKey(testSize)
                        && ExecutionResult.isTimeoutLike(snapshot.get(testSize));
                boolean hasCodeUnderTestExceptionInSnapshot = snapshot.values().stream()
                        .anyMatch(ExecutionResult::isCodeUnderTestLike);
                logger.debug("ExecutionResult handoff: resultIdentity=" + System.identityHashCode(result)
                        + ", testSize=" + testSize
                        + ", exceptionCount=" + snapshot.size()
                        + ", hasTimeoutInSnapshot=" + hasTimeoutInSnapshot
                        + ", hasCodeUnderTestExceptionInSnapshot=" + hasCodeUnderTestExceptionInSnapshot);
            }
            return result;
        } catch (ThreadDeath t) {
            logger.warn("Caught ThreadDeath during test execution");
            ExecutionResult result = new ExecutionResult(tc, null);
            result.setThrownExceptions(callable.getExceptionsThrown());
            result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
            ExecutionTracer.getExecutionTracer().clear();
            return result;

        } catch (InterruptedException e1) {
            logger.info("InterruptedException");
            ExecutionResult result = new ExecutionResult(tc, null);
            result.setThrownExceptions(callable.getExceptionsThrown());
            result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
            ExecutionTracer.getExecutionTracer().clear();
            return result;
        } catch (ExecutionException e1) {
            /*
             * An ExecutionException at this point, is most likely an error in
             * evosuite. As exceptions from the tested code are caught before
             * this.
             */
            System.setOut(systemOut);
            System.setErr(systemErr);

            logger.error("ExecutionException (this is likely a serious error in the framework)", e1);
            ExecutionResult result = new ExecutionResult(tc, null);
            result.setThrownExceptions(callable.getExceptionsThrown());
            result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
            ExecutionTracer.getExecutionTracer().clear();
            if (e1.getCause() instanceof Error) { // an error was thrown
                // somewhere in evosuite
                // code
                throw (Error) e1.getCause();
            } else if (e1.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e1.getCause();
            }
            return result; // FIXME: is this reachable?
        } catch (TimeoutException e1) {
            // Snapshot immediately on timeout catch, before any interrupt/wait/cleanup
            // can move the worker back to the executor idle loop.
            StackTraceElement[] timeoutStackSnapshot = currentThread != null
                    ? currentThread.getStackTrace()
                    : null;
            String timeoutThreadName = currentThread != null ? currentThread.getName() : "<null>";
            Thread.State timeoutThreadState = currentThread != null ? currentThread.getState() : null;

            if (Properties.LOG_TIMEOUT) {
                logger.warn("Timeout occurred for " + Properties.TARGET_CLASS);
            }
            logger.info("TimeoutException, need to stop runner", e1);
            // If timeout happened while executing <clinit>, enter the dedicated
            // grace branch first. Forcing TimeoutExceeded inside <clinit> can
            // poison class initialization and block later static reads.
            boolean initialInStaticInit = isInStaticInit();
            ExecutionTracer.setKillSwitch(!initialInStaticInit);
            if (initialInStaticInit) {
                logger.info("Timeout happened inside <clinit>; entering grace handling before kill switch.");
            }

            // Interrupt immediately only when not in <clinit>. If we interrupt
            // during static initialization, LoopCounter.checkLoop() can convert
            // the interrupt into TooManyResourcesException, which then poisons
            // class initialization (NoClassDefFoundError on subsequent access).
            if (currentThread != null && !initialInStaticInit) {
                currentThread.interrupt();
            } else if (initialInStaticInit) {
                logger.info("Deferring thread interrupt while <clinit> grace handling is active.");
            }
            waitForLastTask(handler, Properties.SHUTDOWN_TIMEOUT);

            if (needsTimeoutCleanup(handler)) {
                // If the thread is in a static initializer or classloading, let it finish:
                // throwing inside <clinit> would leave the class broken.
                boolean loopCounter = LoopCounter.getInstance().isActivated();
                boolean wasInStaticInit = false;
                int clinitWaitAttempts = 0;
                // Allow up to timeout ms for the static initializer to complete normally
                int maxClinitWaitAttempts = (int) Math.max(1, timeout / Properties.SHUTDOWN_TIMEOUT);
                while (needsTimeoutCleanup(handler)
                        && currentThread != null
                        && currentThread.isAlive()
                        && isInStaticInit()
                        && clinitWaitAttempts < maxClinitWaitAttempts) {
                    wasInStaticInit = true;
                    LoopCounter.getInstance().setActive(false);
                    ExecutionTracer.setKillSwitch(false);
                    logger.info("Run still not finished, but awaiting for static initializer to finish ({}/{}).",
                            clinitWaitAttempts + 1, maxClinitWaitAttempts);
                    waitForLastTask(handler, Properties.SHUTDOWN_TIMEOUT);
                    clinitWaitAttempts++;
                }
                LoopCounter.getInstance().setActive(loopCounter);

                if (wasInStaticInit && needsTimeoutCleanup(handler) && isInStaticInit()) {
                    // Static initializer is still running after max wait — it's likely stuck.
                    // Re-enable kill switch and loop counter so the thread can be terminated.
                    logger.warn("Static initializer still running after {}ms — re-enabling kill switch "
                            + "to force termination.", clinitWaitAttempts * Properties.SHUTDOWN_TIMEOUT);
                    ExecutionTracer.setKillSwitch(true);
                    LoopCounter.getInstance().setActive(true);
                    waitForLastTask(handler, Properties.SHUTDOWN_TIMEOUT);
                } else if (wasInStaticInit && needsTimeoutCleanup(handler)) {
                    // The initial timeout was consumed by classloading/static initialization.
                    // Give the actual test code a fresh timeout budget so it gets a fair chance.
                    logger.info("Static initializer finished; granting fresh timeout of {}ms for test execution.",
                            timeout);
                    ExecutionTracer.setKillSwitch(false);
                    waitForLastTask(handler, timeout);
                }

                ExecutionTracer.setKillSwitch(true);
            }

            if (needsTimeoutCleanup(handler)) {
                logger.info("Cancelling thread:");
                if (currentThread != null) {
                    for (StackTraceElement elem : currentThread.getStackTrace()) {
                        logger.info(elem.toString());
                    }
                }
                logger.info(tc.toCode());

                // Re-interrupt in case the first one was swallowed
                if (currentThread != null) {
                    currentThread.interrupt();
                }
                waitForLastTask(handler, Properties.SHUTDOWN_TIMEOUT);
            }

            if (needsTimeoutCleanup(handler)) {
                // Last resort: cancel the task and wait once more
                FutureTask<?> task = handler.getLastTask();
                if (task != null) {
                    task.cancel(true);
                }
                if (currentThread != null) {
                    currentThread.interrupt();
                }
                try {
                    // After cancel, get() throws CancellationException immediately,
                    // so fall back to a timed join on the thread itself.
                    if (currentThread != null) {
                        currentThread.join(Properties.SHUTDOWN_TIMEOUT);
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            waitForTimeoutExitGracePeriod(handler);

            if (needsTimeoutCleanup(handler)) {
                logger.warn("Run still not finished, thread {} is stuck at:",
                        currentThread == null ? "<null>" : currentThread.getName());
                if (currentThread != null) {
                    for (StackTraceElement element : currentThread.getStackTrace()) {
                        logger.warn("  {}", element);
                    }
                }
                // Try interrupt first — works for threads blocked on I/O,
                // sleeping, or waiting on monitors.
                if (currentThread != null && currentThread.isAlive()) {
                    currentThread.interrupt();
                }
                // Last resort: Thread.stop() to kill CPU-bound loops in
                // uninstrumented JDK code (e.g. Logger.getEffectiveLoggerBundle)
                // that don't respond to interrupt(). Deprecated but necessary —
                // a leaked thread burns 100% CPU for the entire process lifetime.
                if (currentThread != null && currentThread.isAlive()) {
                    try {
                        logger.warn("Force-stopping stuck thread {} via Thread.stop()",
                                currentThread.getName());
                        currentThread.stop();
                        currentThread.join(Properties.SHUTDOWN_TIMEOUT);
                    } catch (Throwable t) {
                        logger.info("Exception during Thread.stop() cleanup: {}", t.getMessage());
                    }
                }
                ExecutionTracer.disable();
            }
            ExecutionTracer.disable();

            // Record whether execution is genuinely still running in SUT code
            // after all cleanup attempts.
            //
            // Do not rely on FutureTask.isDone() here: cancel(true) marks the
            // Future as done even if the worker thread keeps running after
            // swallowing interrupts. That would miss stalled workers and skip
            // rotation.
            lastTaskStillRunning = currentThread != null
                    && currentThread.isAlive()
                    && isThreadStuckInSutCode(currentThread);

            // TODO: If this is true, is this problematic?
            if (Sandbox.isOnAndExecutingSUTCode()) {
                Sandbox.doneWithExecutingSUTCode();
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
            }

            ExecutionResult result = new ExecutionResult(tc, null);
            try {
                result.setThrownExceptions(callable.getExceptionsThrown());
                String timeoutMessage = timeoutThreadState == null
                        ? "Test execution timed out in worker thread " + timeoutThreadName
                        : "Test execution timed out in worker thread " + timeoutThreadName
                        + " [state=" + timeoutThreadState + "]";
                Integer timeoutStatementPosition = callable.getCurrentStatementIndex();
                if (timeoutStatementPosition != null && timeoutStatementPosition < 0) {
                    timeoutStatementPosition = null;
                }
                result.reportNewThrownException(tc.size(),
                        new TestCaseExecutor.TimeoutExceeded(
                                timeoutMessage,
                                timeoutStackSnapshot,
                                timeoutStatementPosition,
                                callable.getCurrentStatementCode()));
                result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
                ExecutionTracer.getExecutionTracer().clear();
            } finally {
                // Ensure the tracer is always re-enabled after a timeout, even if
                // result construction throws (e.g., due to a race with the
                // still-running worker thread).  Leaving the tracer disabled would
                // cause ALL subsequent test executions to produce empty traces.
                ExecutionTracer.setKillSwitch(false);
                ExecutionTracer.enable();
            }
            System.setOut(systemOut);
            System.setErr(systemErr);

            return result;
        } finally {
            if (threadGroup != null) {
                PermissionStatistics.getInstance().countThreads(threadGroup.activeCount());
            }
            TestCluster.getInstance().handleRuntimeAccesses(tc);
        }
    }

    /**
     * Release Mockito mock state if the test case used functional mocks.
     *
     * <p>EvoSuite always uses {@code SubclassByteBuddyMockMaker} (not the inline
     * mock maker), so {@code framework().clearInlineMocks()} is intentionally
     * omitted — it is a no-op for subclass mocks and risks interacting with an
     * accidentally-loaded inline mock maker, which can corrupt mock handler state.
     *
     * <p>{@code Mockito.clearInvocations(mock)} drains the per-mock
     * {@code registeredInvocations} LinkedList inside each handler.  Although
     * mocks are now created with {@code stubOnly()} (which disables invocation
     * recording), this call is kept as defense-in-depth for any code path that
     * creates mocks without that flag.
     *
     * <p>Both cleanups are safe because EvoSuite tracks invocations independently
     * via {@code EvoInvocationListener}, which is read at mutation time
     * <em>before</em> re-execution — Mockito's own invocation log is never
     * consulted by the search.
     *
     * <p>We only skip cleanup when the <em>current</em> test's worker thread is
     * still alive after a timeout — it might be inside Mockito code and
     * concurrent cleanup could deadlock. Stalled threads from prior executions
     * do not block cleanup; they are already abandoned.
     */
    private void clearMockInvocationsIfNeeded(TestCase tc, Scope scope,
                                              boolean timedOutExecution,
                                              boolean workerThreadStillAliveAfterTimeout) {
        if (timedOutExecution && workerThreadStillAliveAfterTimeout) {
            logger.warn("Skipping Mockito cleanup because the current execution thread "
                    + "is still alive after timeout. Clearing scope to release objects.");
            scope.clear();
            return;
        }

        for (Statement s : tc) {
            if (s instanceof FunctionalMockStatement) {
                try {
                    Object mockObj = scope.getObject(s.getReturnValue());
                    if (mockObj != null) {
                        Mockito.clearInvocations(mockObj);
                    }
                } catch (Throwable ignored) {
                    // Mock may not have been created (execution failed early)
                }
            }
        }
    }

    private synchronized void rotateExecutorAfterStalledTimeout() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRotationTimestamp;
        if (elapsed < Properties.ROTATION_COOLDOWN_MS) {
            logger.info("Skipping executor rotation: only {}ms since last rotation (cooldown {}ms). "
                    + "Reusing existing executor.", elapsed, Properties.ROTATION_COOLDOWN_MS);
            // Still track the stalled thread even if we skip rotation
            if (currentThread != null && currentThread.isAlive()) {
                currentThread.setPriority(Thread.MIN_PRIORITY);
                stalledThreads.add(currentThread);
            }
            return;
        }

        logger.warn("Rotating TestCaseExecutor worker after timeout with live thread "
                + "to avoid leaking thread-local state.");
        // Track the stalled thread so ResourceController can count it and
        // stop the search before we accumulate too many.
        if (currentThread != null && currentThread.isAlive()) {
            // Log where the thread is stuck to aid diagnosis
            StringBuilder sb = new StringBuilder("Stalled thread stack trace:");
            for (StackTraceElement elem : currentThread.getStackTrace()) {
                sb.append("\n  at ").append(elem);
            }
            logger.warn(sb.toString());
            currentThread.setPriority(Thread.MIN_PRIORITY);
            stalledThreads.add(currentThread);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        executor = Executors.newSingleThreadExecutor(this);
        currentThread = null;
        threadGroup = null;
        totalRotationCount++;
        lastRotationTimestamp = now;
        int numStalled = getNumStalledThreads();
        logger.warn("Executor rotation #{} complete. Stalled threads: {} (limit {}). "
                        + "Total rotations: {} (limit {}).",
                totalRotationCount, numStalled, Properties.MAX_STALLED_THREADS,
                totalRotationCount, Properties.MAX_TOTAL_ROTATIONS);
    }

    private boolean isInStaticInit() {
        if (currentThread == null) {
            return false;
        }
        for (StackTraceElement elem : currentThread.getStackTrace()) {
            if (elem.getMethodName().equals("<clinit>")) {
                return true;
            }
            if (elem.getMethodName().equals("loadClass") && elem.getClassName()
                    .equals(org.evosuite.instrumentation.InstrumentingClassLoader.class.getCanonicalName())) {
                return true;
            }
            // CFontManager is responsible for loading fonts
            // which can take seconds
            if (elem.getClassName().equals("sun.font.CFontManager")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a thread is stuck in SUT code (or EvoSuite test execution code),
     * as opposed to being idle in the thread pool's work queue.
     * A thread that has finished its task but is still alive in the
     * ThreadPoolExecutor's getTask/runWorker loop should NOT trigger rotation.
     */
    private boolean isThreadStuckInSutCode(Thread thread) {
        if (thread == null || !thread.isAlive()) {
            return false;
        }
        for (StackTraceElement elem : thread.getStackTrace()) {
            String className = elem.getClassName();
            // If the thread is still in TestRunnable.call or executeStatements,
            // it's executing SUT code and is genuinely stuck
            if (className.equals(TestRunnable.class.getName())) {
                return true;
            }
            // If it's in Statement.execute or AbstractStatement.exceptionHandler,
            // it's in the middle of SUT invocation
            if (className.endsWith("Statement") && elem.getMethodName().equals("execute")) {
                return true;
            }
        }
        // Thread is alive but not in SUT code — likely idle in the executor pool
        return false;
    }

    private boolean needsTimeoutCleanup(TimeoutHandler<?> handler) {
        FutureTask<?> task = handler.getLastTask();
        if (task == null) {
            return false;
        }
        return !task.isDone();
    }

    private void waitForLastTask(TimeoutHandler<?> handler, long timeoutMillis) {
        FutureTask<?> task = handler.getLastTask();
        if (task == null) {
            return;
        }
        try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException
                 | CancellationException expected) {
        }
    }

    private boolean isLikelyExitingFromTimeout() {
        if (currentThread == null || !currentThread.isAlive()) {
            return false;
        }
        for (StackTraceElement element : currentThread.getStackTrace()) {
            if (element.getClassName().equals(ExecutionTracer.class.getName())
                    && element.getMethodName().equals("checkTimeout")) {
                return true;
            }
        }
        return false;
    }

    private void waitForTimeoutExitGracePeriod(TimeoutHandler<?> handler) {
        long graceMillis = Math.min(Properties.SHUTDOWN_TIMEOUT, 1000L);
        if (graceMillis <= 0) {
            return;
        }

        final long deadline = System.currentTimeMillis() + graceMillis;
        while (needsTimeoutCleanup(handler)
                && isLikelyExitingFromTimeout()
                && System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            waitForLastTask(handler, Math.max(1L, Math.min(100L, remaining)));
        }
    }

    /**
     * Returns the cumulative number of executor rotations since this executor was created.
     *
     * @return the cumulative number of executor rotations since this executor was created.
     */
    public int getTotalRotationCount() {
        return totalRotationCount;
    }

    /**
     * Returns the number of stalled threads.
     *
     * @return a int.
     */
    public int getNumStalledThreads() {
        // Re-interrupt stalled threads — they may have swallowed earlier
        // interrupts and gone back to blocking.  Also prune dead ones.
        stalledThreads.removeIf(t -> {
            if (!t.isAlive()) {
                return true;
            }
            t.interrupt();
            return false;
        });
        return stalledThreads.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Thread newThread(Runnable r) {
        if (currentThread != null && currentThread.isAlive()) {
            currentThread.setPriority(Thread.MIN_PRIORITY);
            stalledThreads.add(currentThread);
            int numStalled = getNumStalledThreads();
            logger.info("Current number of stalled threads: " + numStalled);
            if (numStalled > 20) {
                logger.warn("High number of stalled execution threads ({}). "
                        + "This may indicate a resource leak from tests that cannot be interrupted.",
                        numStalled);
            }
        }

        if (threadGroup != null) {
            PermissionStatistics.getInstance().countThreads(threadGroup.activeCount());
        }
        threadGroup = new ThreadGroup(TEST_EXECUTION_THREAD_GROUP);
        currentThread = new Thread(threadGroup, r);
        currentThread.setName(TEST_EXECUTION_THREAD + "_" + threadCounter);
        currentThread.setDaemon(true);
        threadCounter++;
        currentThread.setContextClassLoader(TestGenerationContext.getInstance().getClassLoaderForSUT());
        ExecutionTracer.setThread(currentThread);
        return currentThread;
    }

    /**
     * Sets the execution observers.
     *
     * @param observers a {@link java.util.Set} of {@link org.evosuite.testcase.execution.ExecutionObserver} objects.
     */
    public void setExecutionObservers(Set<ExecutionObserver> observers) {
        this.observers = observers;
    }

}
