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

import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.dse.VMError;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.StandardStreams;
import org.evosuite.runtime.System.SystemExitException;
import org.evosuite.runtime.jvm.ShutdownHookHandler;
import org.evosuite.runtime.thread.KillSwitch;
import org.evosuite.runtime.thread.ThreadStopper;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.dmon.DmonEphemeralAssist;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 * TestRunnable class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class TestRunnable implements InterfaceTestRunnable {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnable.class);

    private static final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

    private final TestCase test;

    private final Scope scope;

    protected volatile boolean runFinished;

    /**
     * Set by the thread that waited for this run as soon as it starts handling the timeout.
     * The worker keeps going afterwards, but its result is discarded and the sandbox is no
     * longer its own. This only silences observers and sandbox writes; who restores the
     * streams is decided by {@link #streamsHandedBack}.
     */
    private volatile boolean resultDiscarded;

    /**
     * The streams this run has to hand back, taken at the start of {@link #call()}; null
     * before that.
     */
    private volatile StandardStreams.Snapshot standardStreams;

    /** Whether this run muted the streams and so has to unmute them. */
    private volatile boolean streamsMuted;

    /**
     * Set by whichever side hands the streams back first: the worker when it unwinds, or the
     * waiting thread when it gives up on a worker that does not.
     */
    private final AtomicBoolean streamsHandedBack = new AtomicBoolean(true);

    /** The thread currently inside {@link #call()}, or null; guarded by {@code this}. */
    private Thread worker;

    /**
     * Map a thrown exception ('value') with the the position ('key') in the
     * test sequence in which it was thrown from.
     */
    protected Map<Integer, Throwable> exceptionsThrown = new HashMap<>();

    protected Set<ExecutionObserver> observers;

    protected final ThreadStopper threadStopper;

    private volatile int currentStatementIndex = -1;

    /**
     * The statement currently being executed, kept so that a timeout can report which
     * one the worker was stuck on. Held as the statement rather than its rendered
     * source: rendering builds a whole TestCodeVisitor and, for a method or constructor
     * statement, reflects over the declaring class and every ancestor up to Object to
     * decide whether a null argument needs a disambiguating cast. That is far too much
     * work to do for every statement of every execution during the search, when the
     * result is only ever read on the timeout path.
     */
    private volatile Statement currentStatement;

    private final DmonEphemeralAssist dmonAssist;

    /**
     * <p>
     * Constructor for TestRunnable.
     * </p>
     *
     * @param tc        a {@link org.evosuite.testcase.TestCase} object.
     * @param scope     a {@link org.evosuite.testcase.execution.Scope} object.
     * @param observers a {@link java.util.Set} object.
     */
    public TestRunnable(TestCase tc, Scope scope, Set<ExecutionObserver> observers) {
        test = tc;
        this.scope = scope;
        this.dmonAssist = new DmonEphemeralAssist(scope);
        this.observers = observers;
        runFinished = false;

        KillSwitch killSwitch = ExecutionTracer::setKillSwitch;
        threadStopper = new ThreadStopper(killSwitch, threadsToIgnore(), Properties.TIMEOUT);
    }

    /**
     * Thread-name prefixes the {@link ThreadStopper} must leave alone after a test: the
     * execution thread itself and the user's {@code ignore_threads}. Threads that EvoSuite
     * itself owns (LLM workers, compile threads, ...) are exempted separately, by identity,
     * via {@link org.evosuite.runtime.thread.EvoSuiteOwnedThreads} — not by name.
     */
    static Set<String> threadsToIgnore() {
        Set<String> threadsToIgnore = new LinkedHashSet<>();
        threadsToIgnore.add(TestCaseExecutor.TEST_EXECUTION_THREAD);
        threadsToIgnore.addAll(Arrays.asList(Properties.IGNORE_THREADS));
        return threadsToIgnore;
    }

    /**
     * <p>
     * After the test case is executed, if any SUT thread is still running, we
     * will wait for their termination. To identify which thread belong to SUT,
     * before test case execution we should check which are the threads that are
     * running.
     * </p>
     *
     * <p>
     * WARNING: The sandbox might prevent accessing thread informations, so best
     * to call this method from outside this class
     * </p>
     */
    public void storeCurrentThreads() {
        threadStopper.storeCurrentThreads();
    }

    /**
     * Try to kill (and then join) the SUT threads. Killing the SUT threads is
     * important, because some spawn threads could just wait on objects/locks,
     * and so make the test case executions always last TIMEOUT ms.
     */
    public void killAndJoinClientThreads() throws IllegalStateException {
        threadStopper.killAndJoinClientThreads();
    }

    /**
     * Inform all observers that we are going to execute the input statement.
     *
     * @param s the statement to execute
     */
    protected void informObservers_before(Statement s) {
        if (resultDiscarded) {
            return; // the result of this run is discarded
        }
        ExecutionTracer.disable();
        try {
            observers.forEach(o -> o.beforeStatement(s, scope));
        } finally {
            ExecutionTracer.enable();
        }
    }

    /**
     * Inform all observers that input statement has been executed.
     *
     * @param s               the executed statement
     * @param exceptionThrown the exception thrown when executing the statement, if any (can
     *                        be null)
     */
    protected void informObservers_after(Statement s, Throwable exceptionThrown) {
        if (resultDiscarded) {
            return; // the result of this run is discarded
        }
        ExecutionTracer.disable();
        try {
            observers.forEach(o -> o.afterStatement(s, scope, exceptionThrown));
        } finally {
            ExecutionTracer.enable();
        }
    }

    protected void informObservers_finished(ExecutionResult result) {
        if (resultDiscarded) {
            return; // the result of this run is discarded
        }
        ExecutionTracer.disable();
        try {
            observers.forEach(o -> o.testExecutionFinished(result, scope));
        } finally {
            ExecutionTracer.enable();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("removal") // ThreadDeath is deprecated for removal but still needed for Thread.stop() handling
    public ExecutionResult call() {

        // Ensure each run starts with the expected loader, even if prior SUT code changed it.
        // ForkJoinWorkerThread (specifically InnocuousForkJoinWorkerThread) hard-codes a
        // SecurityException in setContextClassLoader, so skip it for those thread types.
        if (!(Thread.currentThread() instanceof java.util.concurrent.ForkJoinWorkerThread)) {
            Thread.currentThread().setContextClassLoader(TestGenerationContext.getInstance().getClassLoaderForSUT());
        }

        synchronized (this) {
            // A pooled thread that timed out on an earlier test is in use again.
            worker = Thread.currentThread();
            org.evosuite.runtime.sandbox.Sandbox.reinstateThread(worker);
        }

        exceptionsThrown.clear();

        runFinished = false;
        ExecutionResult result = new ExecutionResult(test, null);
        resetMockitoProgressStateBestEffort();
        // TODO: Moved this to TestCaseExecutor so it is not part of the test execution timeout
        //        Runtime.getInstance().resetRuntime();
        ExecutionTracer.enable();

        // The code under test may replace System.out/err (a logger that wraps them on
        // construction); they are handed back below whether or not print_to_system is set,
        // or one wrapper would be stacked per execution.
        // Taken from LoggingUtils: an earlier, timed-out execution may still have the streams
        // muted, and System would then hand back the discarding stream.
        PrintStream[] unmuted = LoggingUtils.currentUnmutedOutAndErrStream();
        streamsMuted = !Properties.PRINT_TO_SYSTEM;
        standardStreams = StandardStreams.of(unmuted[0], unmuted[1]);
        streamsHandedBack.set(false);
        PrintStream out = (Properties.PRINT_TO_SYSTEM ? System.out : new PrintStream(byteStream));
        byteStream.reset();

        if (!Properties.PRINT_TO_SYSTEM) {
            LoggingUtils.muteCurrentOutAndErrStream();
        }

        threadStopper.startRecordingTime();

        /*
         *  need AtomicInteger as we want to get latest updated value even if exception is thrown in the 'try' block.
         *  we practically use it as wrapper for int, which we can then pass by reference
         */
        AtomicInteger num = new AtomicInteger(0);

        try {
            if (Properties.REPLACE_CALLS) {
                ShutdownHookHandler.getInstance().initHandler();
            }

            executeStatements(result, out, num);
        } catch (ThreadDeath e) { // can't stop these guys
            // Not rendered: this thread is being stopped, and rendering a live test can
            // initialise classes (left unusable if the stop lands there) and repairs mocks.
            logger.info("Thread stopped at statement {} of {}", num.get(), test.size(), e);
            throw e; // this needs to be propagated
        } catch (TimeoutException | TestCaseExecutor.TimeoutExceeded e) {
            logger.info("Test timed out!");
        } catch (Throwable e) {
            if (e instanceof EvosuiteError) {
                logger.info("Evosuite Error!", e);
                throw (EvosuiteError) e;
            }
            if (e instanceof VMError) {
                logger.info("VM Error!", e);
                throw (VMError) e;
            }
            logger.info("Exception at statement " + num + "! " + e);
            for (StackTraceElement elem : e.getStackTrace()) {
                logger.info(elem.toString());
            }
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                logger.info("Cause: " + e.getCause().toString(), e);
                e = e.getCause();
            }
            if (e instanceof AssertionError
                    && e.getStackTrace()[0].getClassName().contains(PackageInfo.getEvoSuitePackage())) {
                logger.error("Assertion Error in evosuitecode, for statement \n"
                        + test.getStatement(num.get()).getCode() + " \n which is number: "
                        + num + " testcase \n" + test.toCode(), e);
                throw (AssertionError) e;
            }

            logger.error("Suppressed/ignored exception during test case execution on class "
                    + Properties.TARGET_CLASS + ": "
                    + e.getMessage(), e);
        } finally {
            dmonAssist.rollback(result);
            resetMockitoProgressStateBestEffort();
            // A no-op if the thread that gave up on this run has already handed them back:
            // whatever executes next may be muting them by now.
            handBackStreams();
            if (Properties.REPLACE_CALLS) {
                /*
                 * For simplicity, we call it here. Ideally, we could call it among the
                 * statements, with "non-safe" version, to check if any exception is thrown.
                 * But that would be quite a bit of work, which maybe is not really warranted
                 */
                ShutdownHookHandler.getInstance().safeExecuteAddedHooks(this::isResultDiscarded);
            }

            runFinished = true;
            synchronized (this) {
                worker = null;
            }
        }

        result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
        result.setExecutionTime(System.currentTimeMillis() - threadStopper.getStartTime());
        result.setExecutedStatements(num.get());
        result.setThrownExceptions(exceptionsThrown);
        result.setFinalScope(scope);
        result.setReadProperties(org.evosuite.runtime.System.getAllPropertiesReadSoFar());
        result.setWasAnyPropertyWritten(org.evosuite.runtime.System.wasAnyPropertyWritten());

        return result;
    }

    /**
     * Clear any half-finished Mockito stubbing state left over from a prior
     * test that may have timed out mid-stubbing. Without this, the next test
     * would fail with {@code UnfinishedStubbingException}.
     */
    private static void resetMockitoProgressStateBestEffort() {
        try {
            Class<?> progressClass = Class.forName(
                    "org.mockito.internal.progress.ThreadSafeMockingProgress");
            java.lang.reflect.Method mockingProgress =
                    progressClass.getMethod("mockingProgress");
            Object progress = mockingProgress.invoke(null);
            if (progress != null) {
                java.lang.reflect.Method reset =
                        progress.getClass().getMethod("reset");
                reset.invoke(progress);
            }
        } catch (Throwable expected) {
            // Mockito internals unavailable or changed; proceed without reset.
        }
    }

    /**
     * Iterate over all statements in the test case, and execute them one at a time.
     *
     * @param result the execution result.
     * @param out the output stream.
     * @param num the number.
     * @throws TimeoutException .
     * @throws InvocationTargetException .
     * @throws IllegalAccessException .
     * @throws InstantiationException .
     * @throws VMError .
     * @throws EvosuiteError .
     */
    private void executeStatements(ExecutionResult result, PrintStream out,
                                   AtomicInteger num) throws TimeoutException,
            InvocationTargetException, IllegalAccessException,
            InstantiationException, VMError, EvosuiteError {

        for (Statement s : test) {
            currentStatementIndex = num.get();
            currentStatement = s;

            if (Thread.currentThread().isInterrupted() || Thread.interrupted()) {
                logger.info("Thread interrupted at statement {}: {}", num, s.getClass().getSimpleName());
                throw new TimeoutException();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Executing statement " + s.getCode());
            }

            ExecutionTracer.statementExecuted();
            informObservers_before(s);

            /*
             * Here actually execute a statement of the SUT
             */
            dmonAssist.captureFieldAssignment(s);
            Throwable exceptionThrown;
            try {
                exceptionThrown = s.execute(scope, out);
            } finally {
                // Safety net: if a GUI mock constructor throws before its own
                // restore call, ensure we do not leak a disabled-headless scope
                // into subsequent statements.
                GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
            }

            if (exceptionThrown != null) {
                // if internal error, then throw exception
                // -------------------------------------------------------
                if (exceptionThrown instanceof VMError) {
                    throw (VMError) exceptionThrown;
                }
                if (exceptionThrown instanceof EvosuiteError) {
                    throw (EvosuiteError) exceptionThrown;
                }
                // -------------------------------------------------------

                // When a ClassCastException involves a mock object, register a
                // mock type upgrade so future mocks use the more specific type.
                // E.g., mock(Graphics.class) cast to Graphics2D → upgrade to Graphics2D.
                ClassCastException classCast = findClassCastException(exceptionThrown);
                if (classCast != null) {
                    FunctionalMockStatement.detectMockTypeUpgrade(classCast, s, test);
                }

                exceptionThrown = dmonAssist.assist(result, s, exceptionThrown, out);
                if (exceptionThrown == null) {
                    informObservers_after(s, null);
                    num.incrementAndGet();
                    continue;
                }

                // When a constructor or method causes OOM, register the class
                // so that future test generation caps its int arguments and
                // rejectAbsurdIntInputs kicks in for that class.
                if (exceptionThrown instanceof OutOfMemoryError) {
                    String className = null;
                    if (s instanceof ConstructorStatement) {
                        className = ((ConstructorStatement) s).getDeclaringClassName();
                        try {
                            Object[] args = extractIntArgs((ConstructorStatement) s, test);
                            org.evosuite.testcase.StatementFactory
                                    .addAllocationSensitiveMethod(className, "<init>", args);
                        } catch (Exception ex) {
                            logger.debug("Could not extract args for constructor OOM registration", ex);
                        }
                    } else if (s instanceof MethodStatement) {
                        className = ((MethodStatement) s).getMethod()
                                .getMethod().getDeclaringClass().getName();
                        // Register the specific method with its arg values
                        // so future calls get a threshold derived from
                        // the values that caused this OOM.
                        try {
                            MethodStatement ms = (MethodStatement) s;
                            String methodName = ms.getMethod().getMethod().getName();
                            // Extract int arg values from the test case statements
                            // rather than the scope (which may be corrupted after OOM)
                            Object[] args = extractIntArgs(ms, test);
                            org.evosuite.testcase.StatementFactory
                                    .addAllocationSensitiveMethod(className, methodName, args);
                        } catch (Exception ex) {
                            logger.debug("Could not extract args for method OOM registration", ex);
                        }
                    }
                    if (className != null) {
                        org.evosuite.testcase.StatementFactory.addAllocationSensitiveClass(className);
                        // Not rendered: this thread can be stopped by the timeout at any point,
                        // and a stop that lands in a class initialiser run by the rendering
                        // leaves that class unusable for the rest of the run.
                        logger.warn("OOM in {} at statement {}/{} of {}",
                                s.getClass().getSimpleName(), num.get(), test.size(), className);
                    }

                    ConstantPoolManager.AdaptiveSeedLimitUpdate update =
                            ConstantPoolManager.getInstance().tightenSeededNumericLimitAfterOom();
                    int sanitizedLiterals = ConstantPoolManager.sanitizeTestCaseNumericLiterals(test);
                    if (update.isEnabled() || sanitizedLiterals > 0) {
                        logger.warn("OOM mitigation applied: adaptiveSeedLimit={}, "
                                        + "activations={}, prunedConstants={}, sanitizedLiterals={}",
                                update.getLimit(), update.getActivationCount(),
                                update.getPrunedConstants(), sanitizedLiterals);
                    }
                }

                /*
                 * This is implemented in this way due to ExecutionResult.hasTimeout().
                 * Also unwrap CodeUnderTestException wrapping TimeoutExceeded — this
                 * happens when our allocation guards (rejectAbsurdIntInputs,
                 * rejectLargeIntInputs) simulate a timeout to give worst-case fitness.
                 */
                Throwable timeoutCandidate = exceptionThrown;
                if (timeoutCandidate instanceof CodeUnderTestException
                        && timeoutCandidate.getCause() instanceof TestCaseExecutor.TimeoutExceeded) {
                    timeoutCandidate = timeoutCandidate.getCause();
                }
                if (timeoutCandidate instanceof TestCaseExecutor.TimeoutExceeded) {
                    timeoutCandidate = enrichTimeoutDiagnostic((TestCaseExecutor.TimeoutExceeded) timeoutCandidate,
                            num.get(), s.getCode());
                    logger.debug("Test timed out!");
                    exceptionsThrown.put(test.size(), timeoutCandidate);
                    result.setThrownExceptions(exceptionsThrown);
                    result.reportNewThrownException(test.size(), timeoutCandidate);
                    result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
                    break;
                }

                // keep track if the exception and where it was thrown
                exceptionsThrown.put(num.get(), exceptionThrown);

                // check if it was an explicit exception
                // --------------------------------------------------------
                if (ExecutionTracer.getExecutionTracer().getLastException() == exceptionThrown) {
                    result.getExplicitExceptions().put(num.get(), true);
                } else {
                    result.getExplicitExceptions().put(num.get(), false);
                }
                // --------------------------------------------------------

                printDebugInfo(s, exceptionThrown);
                // --------------------------------------------------------

                /*
                 * If an exception is thrown, we stop the execution of the test case, because the
                 * internal state could be corrupted, and not possible to verify the behavior of
                 * any following function call. Predicate should be true by default
                 */
                if (Properties.BREAK_ON_EXCEPTION
                        || exceptionThrown instanceof SystemExitException) {
                    informObservers_after(s, exceptionThrown);
                    break;
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Done statement " + s.getCode());
            }

            informObservers_after(s, exceptionThrown);

            num.incrementAndGet();
        } // end of loop
        currentStatementIndex = -1;
        currentStatement = null;
        informObservers_finished(result);
        //TODO
    }

    private TestCaseExecutor.TimeoutExceeded enrichTimeoutDiagnostic(TestCaseExecutor.TimeoutExceeded timeout,
                                                                     int statementIndex,
                                                                     String statementCode) {
        if (timeout == null) {
            return null;
        }
        if (timeout.getStatementPosition() != null
                && timeout.getStatementCode() != null
                && !timeout.getStatementCode().trim().isEmpty()) {
            return timeout;
        }
        return new TestCaseExecutor.TimeoutExceeded(
                timeout.getMessage(),
                timeout.getStackTrace(),
                statementIndex,
                statementCode);
    }

    /**
     * Extracts the int argument values from a statement with parameters by looking up
     * the PrimitiveStatements that define the parameter variables.
     * Safe to call after OOM (doesn't use scope).
     */
    private static Object[] extractIntArgs(
            org.evosuite.testcase.statements.EntityWithParametersStatement ms, TestCase tc) {
        List<VariableReference> params = ms.getParameterReferences();
        Object[] args = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            VariableReference ref = params.get(i);
            Statement defStmt = tc.getStatement(ref.getStPosition());
            if (defStmt instanceof org.evosuite.testcase.statements.PrimitiveStatement) {
                args[i] = ((org.evosuite.testcase.statements.PrimitiveStatement<?>) defStmt).getValue();
            }
        }
        return args;
    }

    private static ClassCastException findClassCastException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ClassCastException) {
                return (ClassCastException) current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private void printDebugInfo(Statement s, Throwable exceptionThrown) {
        // some debugging info
        // --------------------------------------------------------

        if (logger.isDebugEnabled()) {
            logger.debug("Exception thrown in statement: " + s.getCode()
                    + " - " + exceptionThrown.getClass().getName() + " - "
                    + exceptionThrown.getMessage());
            for (StackTraceElement elem : exceptionThrown.getStackTrace()) {
                logger.debug(elem.toString());
            }
            Throwable current = exceptionThrown.getCause();
            int depth = 0;
            while (current != null && depth < 20) {
                logger.debug("Cause[{}]: {} - {}", depth, current.getClass().getName(), current.getMessage());
                for (StackTraceElement elem : current.getStackTrace()) {
                    logger.debug(elem.toString());
                }
                Throwable next = current.getCause();
                if (next == current) {
                    break;
                }
                current = next;
                depth++;
            }
            if (exceptionThrown.getCause() == null) {
                logger.debug("Cause is null");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Throwable> getExceptionsThrown() {
        return new HashMap<>(exceptionsThrown);
    }

    @Override
    public int getCurrentStatementIndex() {
        return currentStatementIndex;
    }

    @Override
    public String getCurrentStatementCode() {
        Statement s = currentStatement;
        if (s == null) {
            return null;
        }
        try {
            return s.getCode();
        } catch (Throwable t) {
            // Only ever called to describe a timeout. Rendering reflects over the
            // statement's declaring class, which on a project with a broken classpath
            // can throw a LinkageError; a diagnostic must not replace the timeout it
            // is describing.
            logger.debug("Could not render the statement the test timed out on", t);
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRunFinished() {
        return runFinished;
    }

    /**
     * Records that the thread waiting for this run timed out and is handling it. The result
     * is discarded from here on; the streams stay as they are until {@link #handBackStreams()}.
     */
    public void markResultDiscarded() {
        resultDiscarded = true;
        /*
         * The worker goes on running this test, but the sandbox state is global
         * and now belongs to whatever executes next: whatever the worker's
         * observers or hooks do to it from here on must not take effect.
         */
        synchronized (this) {
            if (worker != null) {
                org.evosuite.runtime.sandbox.Sandbox.abandonThread(worker);
            }
        }
    }

    /**
     * @return true if the thread waiting for this run timed out and its result is discarded
     */
    public boolean isResultDiscarded() {
        return resultDiscarded;
    }

    /**
     * Unmutes the streams if this run muted them and restores the snapshot taken when it
     * started. Called by the worker when it unwinds and by the waiting thread when it gives
     * up on a worker that does not; only the first call does anything, so a late worker
     * cannot clobber the streams of whatever executes next.
     */
    public void handBackStreams() {
        StandardStreams.Snapshot snapshot = standardStreams;
        if (snapshot == null || !streamsHandedBack.compareAndSet(false, true)) {
            return;
        }
        if (streamsMuted) {
            LoggingUtils.restorePreviousOutAndErrStream();
        }
        snapshot.restore();
    }

}
