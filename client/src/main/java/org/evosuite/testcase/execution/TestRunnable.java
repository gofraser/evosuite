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
import org.evosuite.runtime.System.SystemExitException;
import org.evosuite.runtime.jvm.ShutdownHookHandler;
import org.evosuite.runtime.thread.KillSwitch;
import org.evosuite.runtime.thread.ThreadStopper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.dmon.DmonCoordinator;
import org.evosuite.testcase.dmon.DmonFailureSite;
import org.evosuite.testcase.dmon.DmonInjectionDiscovery;
import org.evosuite.testcase.dmon.DmonPromotionPlan;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeoutException;
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
     * Map a thrown exception ('value') with the the position ('key') in the
     * test sequence in which it was thrown from.
     */
    protected Map<Integer, Throwable> exceptionsThrown = new HashMap<>();

    protected Set<ExecutionObserver> observers;

    protected final ThreadStopper threadStopper;

    private final Deque<DmonRollbackAction> dmonRollbackLog = new ArrayDeque<>();

    private interface DmonRollbackAction {

        void rollback() throws Exception;

        String describe();
    }

    private static final class DmonStaticWrite implements DmonRollbackAction {
        private final Field field;
        private final Object oldValue;

        private DmonStaticWrite(Field field, Object oldValue) {
            this.field = field;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(null, oldValue);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    private static final class DmonSetterRollback implements DmonRollbackAction {
        private final Method setter;
        private final Object oldValue;

        private DmonSetterRollback(Method setter, Object oldValue) {
            this.setter = setter;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws Exception {
            setter.setAccessible(true);
            setter.invoke(null, oldValue);
        }

        @Override
        public String describe() {
            return setter.getDeclaringClass().getName() + "." + setter.getName() + "(...)";
        }
    }

    private static final class DmonInstanceWrite implements DmonRollbackAction {
        private final Field field;
        private final Object target;
        private final Object oldValue;

        private DmonInstanceWrite(Field field, Object target, Object oldValue) {
            this.field = field;
            this.target = target;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(target, oldValue);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "." + field.getName() + " (instance)";
        }
    }

    private static final class DmonInstanceSetterRollback implements DmonRollbackAction {
        private final Method setter;
        private final Object receiver;
        private final Object oldValue;

        private DmonInstanceSetterRollback(Method setter, Object receiver, Object oldValue) {
            this.setter = setter;
            this.receiver = receiver;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws Exception {
            setter.setAccessible(true);
            setter.invoke(receiver, oldValue);
        }

        @Override
        public String describe() {
            return setter.getDeclaringClass().getName() + "." + setter.getName() + "(...) (instance)";
        }
    }

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
        this.observers = observers;
        runFinished = false;

        KillSwitch killSwitch = ExecutionTracer::setKillSwitch;
        Set<String> threadsToIgnore = new LinkedHashSet<>();
        threadsToIgnore.add(TestCaseExecutor.TEST_EXECUTION_THREAD);
        threadsToIgnore.addAll(Arrays.asList(Properties.IGNORE_THREADS));

        threadStopper = new ThreadStopper(killSwitch, threadsToIgnore, Properties.TIMEOUT);
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
        ExecutionTracer.disable();
        try {
            observers.forEach(o -> o.afterStatement(s, scope, exceptionThrown));
        } finally {
            ExecutionTracer.enable();
        }
    }

    protected void informObservers_finished(ExecutionResult result) {
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

        exceptionsThrown.clear();

        runFinished = false;
        ExecutionResult result = new ExecutionResult(test, null);
        resetMockitoProgressStateBestEffort();
        // TODO: Moved this to TestCaseExecutor so it is not part of the test execution timeout
        //        Runtime.getInstance().resetRuntime();
        ExecutionTracer.enable();

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
            logger.info("Found error in " + test.toCode(), e);
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
            if (!rollbackDmonWrites(result)) {
                result.setDmonContaminated(true);
            }
            resetMockitoProgressStateBestEffort();
            if (!Properties.PRINT_TO_SYSTEM) {
                LoggingUtils.restorePreviousOutAndErrStream();
            }
            if (Properties.REPLACE_CALLS) {
                /*
                 * For simplicity, we call it here. Ideally, we could call it among the
                 * statements, with "non-safe" version, to check if any exception is thrown.
                 * But that would be quite a bit of work, which maybe is not really warranted
                 */
                ShutdownHookHandler.getInstance().safeExecuteAddedHooks();
            }

            runFinished = true;
        }

        result.setTrace(ExecutionTracer.getExecutionTracer().getTrace());
        result.setExecutionTime(System.currentTimeMillis() - threadStopper.getStartTime());
        result.setExecutedStatements(num.get());
        result.setThrownExceptions(exceptionsThrown);
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

            if (Thread.currentThread().isInterrupted() || Thread.interrupted()) {
                logger.info("Thread interrupted at statement " + num + ": " + s.getCode());
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
            maybeCaptureFieldAssignmentRollback(s);
            Throwable exceptionThrown = s.execute(scope, out);

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

                exceptionThrown = maybeAssistAndRecordDmonCandidate(result, s, exceptionThrown, out);
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
                        logger.warn("OOM in {} at statement {}/{}: {}\nFull test case:\n{}",
                                s.getClass().getSimpleName(), num.get(), test.size(),
                                s.getCode(), test.toCode());
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
        informObservers_finished(result);
        //TODO
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

    private static void maybeRecordDmonCandidate(ExecutionResult result, Statement statement, Throwable throwable) {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return;
        }
        if (!(statement instanceof ConstructorStatement)) {
            logger.trace("DMoN runtime: skip candidate [reason=NOT_CONSTRUCTOR_STATEMENT]");
            return;
        }
        if (!(throwable instanceof NullPointerException)) {
            logger.trace("DMoN runtime: skip candidate [reason=NOT_NPE, type={}]", throwable.getClass().getName());
            return;
        }
        if (result.getDmonPromotionPlan() != null) {
            logger.debug("DMoN runtime: candidate already recorded for this execution");
            return;
        }
        ConstructorStatement constructorStatement = (ConstructorStatement) statement;
        Optional<DmonPromotionPlan> plan = DmonCoordinator.getInstance()
                .analyzeConstructorFailure(constructorStatement, throwable);
        if (plan.isPresent()) {
            result.setDmonPromotionPlan(plan.get());
            DmonFailureSite fs = plan.get().getFailureSite();
            logger.debug("DMoN runtime: candidate recorded [owner={}, method={}, line={}, member={}, inferredType={}]",
                    fs.getOwnerClass(), fs.getMethodName(), fs.getLineNumber(),
                    plan.get().getMemberToken().orElse(""),
                    plan.get().getInferredMissingTypeName().orElse(""));
        } else {
            logger.debug("DMoN runtime: analyzer returned no candidate");
        }
    }

    private Throwable maybeAssistAndRecordDmonCandidate(ExecutionResult result,
                                                         Statement statement,
                                                         Throwable throwable,
                                                         PrintStream out)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return throwable;
        }
        if (!(statement instanceof ConstructorStatement)) {
            return throwable;
        }

        ConstructorStatement constructorStatement = (ConstructorStatement) statement;
        Throwable lastThrown = throwable;
        NullPointerException currentNpe = extractNpe(throwable);
        if (currentNpe == null) {
            return throwable;
        }
        int maxRetries = Math.max(1, Properties.DMON_MAX_EPHEMERAL_RETRIES);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            maybeRecordDmonCandidate(result, statement, currentNpe);
            Optional<DmonPromotionPlan> analyzedPlan = DmonCoordinator.getInstance()
                    .analyzeConstructorFailure(constructorStatement, currentNpe);
            if (!analyzedPlan.isPresent()) {
                logger.debug("DMoN runtime: cascading assist stopped [attempt={}, reason=NO_PLAN]",
                        attempt + 1);
                return lastThrown;
            }
            if (!tryEphemeralInjection(constructorStatement, analyzedPlan.get())) {
                logger.debug("DMoN runtime: cascading assist stopped [attempt={}, reason=INJECTION_FAILED]",
                        attempt + 1);
                return lastThrown;
            }
            logger.debug("DMoN runtime: cascading assist applied [attempt={}, maxRetries={}]",
                    attempt + 1, maxRetries);
            Throwable retried = statement.execute(scope, out);
            if (retried == null) {
                return null;
            }
            lastThrown = retried;
            currentNpe = extractNpe(retried);
            if (currentNpe == null) {
                return retried;
            }
        }
        logger.debug("DMoN runtime: cascading assist exhausted retry budget [maxRetries={}]", maxRetries);
        return lastThrown;
    }

    private static NullPointerException extractNpe(Throwable throwable) {
        if (throwable instanceof NullPointerException) {
            return (NullPointerException) throwable;
        }
        if (throwable instanceof CodeUnderTestException && throwable.getCause() instanceof NullPointerException) {
            return (NullPointerException) throwable.getCause();
        }
        return null;
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

    private void maybeCaptureFieldAssignmentRollback(Statement statement) {
        if (!(statement instanceof AssignmentStatement)) {
            return;
        }
        VariableReference target = statement.getReturnValue();
        if (!(target instanceof FieldReference)) {
            return;
        }
        FieldReference fieldRef = (FieldReference) target;
        Field field = fieldRef.getField().getField();
        if (Modifier.isFinal(field.getModifiers())) {
            return;
        }
        try {
            field.setAccessible(true);
            if (fieldRef.getField().isStatic()) {
                Object oldValue = field.get(null);
                dmonRollbackLog.push(new DmonStaticWrite(field, oldValue));
                return;
            }
            VariableReference sourceRef = fieldRef.getSource();
            if (sourceRef == null) {
                return;
            }
            Object receiver = sourceRef.getObject(scope);
            if (receiver == null) {
                return;
            }
            Object oldValue = field.get(receiver);
            dmonRollbackLog.push(new DmonInstanceWrite(field, receiver, oldValue));
        } catch (Throwable t) {
            logger.debug("Could not snapshot field assignment for rollback: {}", t.getMessage());
        }
    }

    private boolean tryEphemeralInjection(ConstructorStatement constructorStatement, DmonPromotionPlan plan) {
        Class<?> ownerClass = resolveOwnerClassForEphemeral(constructorStatement, plan);
        if (ownerClass == null) {
            return false;
        }

        String fieldName = DmonInjectionDiscovery.resolveFieldName(plan);
        Field field = DmonInjectionDiscovery.findStaticField(ownerClass, fieldName);
        Field instanceField = field == null ? DmonInjectionDiscovery.findInstanceField(ownerClass, fieldName) : null;

        Class<?> dependencyType = null;
        if (field != null) {
            dependencyType = field.getType();
        } else if (instanceField != null) {
            dependencyType = instanceField.getType();
        } else if (plan.getInferredMissingTypeName().isPresent()) {
            try {
                dependencyType = Class.forName(plan.getInferredMissingTypeName().get(),
                        false, ownerClass.getClassLoader());
            } catch (ClassNotFoundException expected) {
                dependencyType = null;
            }
        }
        if (dependencyType == null || dependencyType.isPrimitive()) {
            return false;
        }

        if (tryEphemeralSetterInjection(ownerClass, dependencyType, fieldName)) {
            return true;
        }
        if (tryEphemeralFieldInjection(field, dependencyType)) {
            return true;
        }
        return tryEphemeralInstanceInjection(ownerClass, instanceField, dependencyType, fieldName);
    }

    private static Class<?> resolveOwnerClassForEphemeral(
            ConstructorStatement constructorStatement, DmonPromotionPlan plan) {
        String ownerName = plan.getFailureSite() == null ? null : plan.getFailureSite().getOwnerClass();
        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        if (ownerName != null && !ownerName.trim().isEmpty()) {
            try {
                return Class.forName(ownerName, false, sutLoader);
            } catch (ClassNotFoundException expected) {
            }
        }
        return constructorStatement.getConstructor().getConstructor().getDeclaringClass();
    }

    private boolean tryEphemeralSetterInjection(Class<?> ownerClass, Class<?> dependencyType, String fieldName) {
        Method setter = DmonInjectionDiscovery.findStaticSetter(ownerClass, dependencyType, fieldName);
        if (setter == null) {
            return false;
        }
        Field rollbackField = DmonInjectionDiscovery.resolveRollbackFieldForSetter(ownerClass, setter, fieldName);
        if (rollbackField == null) {
            return false;
        }
        try {
            rollbackField.setAccessible(true);
            Object oldValue = rollbackField.get(null);
            Class<?> setterType = setter.getParameterTypes()[0];
            Object mock = createEphemeralMock(setterType);
            setter.setAccessible(true);
            setter.invoke(null, mock);
            dmonRollbackLog.push(new DmonSetterRollback(setter, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral setter injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceInjection(Class<?> ownerClass,
                                                  Field field,
                                                  Class<?> dependencyType,
                                                  String fieldName) {
        if (ownerClass == null || dependencyType == null || dependencyType.isPrimitive()) {
            return false;
        }
        Method accessor = DmonInjectionDiscovery.findStaticInstanceAccessor(ownerClass);
        if (accessor == null) {
            return false;
        }
        try {
            accessor.setAccessible(true);
            Object receiver = accessor.invoke(null);
            if (receiver == null) {
                return false;
            }
            if (tryEphemeralInstanceSetterInjection(ownerClass, receiver, dependencyType, fieldName)) {
                return true;
            }
            return tryEphemeralInstanceFieldInjection(ownerClass, receiver, field, dependencyType, fieldName);
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance accessor injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceSetterInjection(Class<?> ownerClass,
                                                        Object receiver,
                                                        Class<?> dependencyType,
                                                        String fieldName) {
        Method setter = findInstanceSetter(ownerClass, dependencyType, fieldName);
        if (setter == null) {
            return false;
        }
        Field rollbackField = DmonInjectionDiscovery
                .resolveRollbackFieldForInstanceSetter(ownerClass, setter, fieldName);
        if (rollbackField == null) {
            return false;
        }
        try {
            rollbackField.setAccessible(true);
            Object oldValue = rollbackField.get(receiver);
            Class<?> setterType = setter.getParameterTypes()[0];
            Object mock = createEphemeralMock(setterType);
            setter.setAccessible(true);
            setter.invoke(receiver, mock);
            dmonRollbackLog.push(new DmonInstanceSetterRollback(setter, receiver, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance setter injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceFieldInjection(Class<?> ownerClass,
                                                       Object receiver,
                                                       Field field,
                                                       Class<?> dependencyType,
                                                       String fieldName) {
        Field targetField = field != null ? field : DmonInjectionDiscovery.findInstanceField(ownerClass, fieldName);
        if (targetField == null) {
            return false;
        }
        try {
            targetField.setAccessible(true);
            Object oldValue = targetField.get(receiver);
            Object mock = createEphemeralMock(dependencyType);
            targetField.set(receiver, mock);
            dmonRollbackLog.push(new DmonInstanceWrite(targetField, receiver, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance field injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private static Method findInstanceSetter(Class<?> ownerClass, Class<?> dependencyType, String fieldNameHint) {
        if (ownerClass == null || dependencyType == null) {
            return null;
        }
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        String normalizedFieldHint = fieldNameHint == null ? "" : fieldNameHint.toLowerCase(Locale.ROOT);
        for (Method method : ownerClass.getMethods()) {
            int mods = method.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isPublic(mods)) {
                continue;
            }
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(dependencyType) && !dependencyType.isAssignableFrom(param)) {
                continue;
            }
            String lowerName = method.getName().toLowerCase(Locale.ROOT);
            if (!lowerName.startsWith("set")) {
                continue;
            }
            int score = 20;
            if (!normalizedFieldHint.isEmpty() && lowerName.contains(normalizedFieldHint)) {
                score += 30;
            }
            if (param.equals(dependencyType)) {
                score += 5;
            }
            if (best == null || score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean tryEphemeralFieldInjection(Field field, Class<?> dependencyType) {
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            Object oldValue = field.get(null);
            Object mock = createEphemeralMock(dependencyType);
            field.set(null, mock);
            dmonRollbackLog.push(new DmonStaticWrite(field, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral field injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean rollbackDmonWrites(ExecutionResult result) {
        boolean ok = true;
        while (!dmonRollbackLog.isEmpty()) {
            DmonRollbackAction write = dmonRollbackLog.pop();
            try {
                write.rollback();
            } catch (Throwable t) {
                ok = false;
                logger.warn("DMoN rollback failed for {}: {}", write.describe(), t.getMessage());
            }
        }
        if (!ok && result != null) {
            result.setDmonContaminated(true);
        }
        return ok;
    }

    private static Object createEphemeralMock(Class<?> type) {
        return Mockito.mock(type, invocation -> createEphemeralReturnValue(invocation.getMethod().getReturnType()));
    }

    private static Object createEphemeralReturnValue(Class<?> returnType) {
        if (returnType == null || returnType.equals(Void.TYPE)) {
            return null;
        }
        if (returnType.equals(String.class)
                || returnType.equals(Object.class)
                || returnType.equals(CharSequence.class)) {
            return "";
        }
        if (returnType.isPrimitive()) {
            return defaultPrimitiveValue(returnType);
        }
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        if (returnType.isEnum()) {
            Object[] values = returnType.getEnumConstants();
            return values != null && values.length > 0 ? values[0] : null;
        }
        if (Modifier.isFinal(returnType.getModifiers())
                && returnType.getName().startsWith("java.lang.")) {
            return null;
        }
        try {
            return Mockito.mock(returnType, invocation ->
                    createEphemeralReturnValue(invocation.getMethod().getReturnType()));
        } catch (Throwable expected) {
            return null;
        }
    }

    private static Object defaultPrimitiveValue(Class<?> type) {
        if (type.equals(Boolean.TYPE)) {
            return false;
        }
        if (type.equals(Character.TYPE)) {
            return '\0';
        }
        if (type.equals(Byte.TYPE)) {
            return (byte) 0;
        }
        if (type.equals(Short.TYPE)) {
            return (short) 0;
        }
        if (type.equals(Integer.TYPE)) {
            return 0;
        }
        if (type.equals(Long.TYPE)) {
            return 0L;
        }
        if (type.equals(Float.TYPE)) {
            return 0.0f;
        }
        if (type.equals(Double.TYPE)) {
            return 0.0d;
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
            if (exceptionThrown.getCause() != null) {
                logger.debug("Cause: "
                        + exceptionThrown.getCause().getClass().getName()
                        + " - " + exceptionThrown.getCause().getMessage());
                for (StackTraceElement elem : exceptionThrown.getCause().getStackTrace()) {
                    logger.debug(elem.toString());
                }
            } else {
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

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRunFinished() {
        return runFinished;
    }

}
