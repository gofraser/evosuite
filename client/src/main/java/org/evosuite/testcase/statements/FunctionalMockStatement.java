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
package org.evosuite.testcase.statements;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.coverage.mutation.MutationObserver;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.LenientMockAnswer;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.UnmockableTypes;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.evosuite.runtime.instrumentation.RemoveFinalClassAdapter;
import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.UncompilableCodeException;
import org.evosuite.testcase.fm.EvoInvocationListener;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericClassUtils;
import org.mockito.MockSettings;
import org.mockito.Mockito;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.OngoingStubbing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Statement representing the creation and setup of a functional mock.
 * Recall: a functional mock is different from an environment one (eg for file IO and CPU time).
 * A functional mock instantiation can look like:
 *
 * <p>EvoInvocationListener listener = new EvoInvocationListener(); <br>
 * Foo foo = mock(Foo.class, withSettings().invocationListeners(listener)); <br>
 * when(foo.aMethod(any() ...)).thenReturn( v0, v1, ...); <br>
 * when(foo.anotherMethod(...)).thenReturn( k0, k1, ...); <br>
 * ... <br>
 * listener.activate();
 *
 * <p>All these statements will be represented with a single one, where the return
 * value is the instantiated mock "foo", and the input parameters are all the input
 * parameters of all mocked methods (eg v0, k0), in order.
 *
 * <p>Calls to "listener" are essential during the search (eg when the statement is executed),
 * but will not be part of the final generated JUnit tests (ie not part of toCode()).
 *
 * <p>Initially, a functional mock will have 0 input parameters, and no "when" call.
 * After a test is executed, the input parameter lists will be updated based on what
 * "listener" does report. The number of input parameters might vary several times
 * throughout the lifespan of a test during the search (can both increase and decrease).
 *
 * <p>This statement cannot be used to mock the SUT, as it would make no sense whatsoever.
 * However, there might be special cases: eg SUT being an abstract class with no
 * concrete implementation. That would need to be handled specially.
 *
 * <p>Created by Andrea Arcuri on 01/08/15.
 */
public class FunctionalMockStatement extends EntityWithParametersStatement {

    private static final long serialVersionUID = 4416243066612894722L;

    private static final Logger logger = LoggerFactory.getLogger(FunctionalMockStatement.class);

    /**
     * The stubbed methods in the order they are declared, each with the values its successive
     * calls return. A method has at most one stub. {@code super.parameters} holds the values of
     * all stubs in this order: they are the inputs of this statement.
     */
    private final List<Stub> stubs;

    /**
     * Set when {@link #addMethodStubbing} gave a stub new values: its descriptor then no longer
     * tells how many calls the values were made for, so the stubs are updated after the next
     * execution whatever it observes.
     */
    private boolean stubReplacedSinceUpdate;

    protected GenericClass<?> targetClass;

    /**
     * True if this statement was populated by the test parser (via addMethodStubbing)
     * rather than by execution. Used to relax assertions about listener state.
     */
    protected boolean populatedFromParser;

    /**
     * When true, the mock uses a lenient default answer that returns non-null
     * values for all return types (empty strings, empty collections, zero for
     * primitives, and recursive mocks for other types).  This is used by DMoN
     * promotion to match the behavior of the ephemeral mock used during search.
     * Without it, the default Mockito answer returns null for objects, which
     * causes secondary NPEs when the SUT calls methods on the mock before mock
     * stubs are materialized.
     */
    protected boolean useLenientDefaultAnswer;

    /**
     * Marks this mock as having been created by DMoN promotion.
     * Used by the promotion dedup guard to distinguish DMoN mocks from
     * regular functional mocks.
     */
    protected boolean isDmonPromotion;

    protected transient volatile EvoInvocationListener listener;

    /**
     * Constructor for FunctionalMockStatement.
     *
     * @param tc          the test case
     * @param retval      the return value variable reference
     * @param targetClass the class to mock
     * @throws IllegalArgumentException if targetClass is null
     */
    public FunctionalMockStatement(TestCase tc, VariableReference retval, GenericClass<?> targetClass)
            throws IllegalArgumentException {
        super(tc, retval);
        Inputs.checkNull(targetClass);
        this.targetClass = targetClass;
        stubs = new ArrayList<>();
        checkTarget();
        assert parameters.isEmpty();
    }

    /**
     * Constructor for FunctionalMockStatement.
     *
     * @param tc          the test case
     * @param retvalType  the type of the return value
     * @param targetClass the class to mock
     * @throws IllegalArgumentException if targetClass is null or type mismatch
     */
    public FunctionalMockStatement(TestCase tc, Type retvalType, GenericClass<?> targetClass)
            throws IllegalArgumentException {
        this(tc, retvalType, targetClass, true);
    }

    /**
     * Internal constructor variant used by clone/copy paths.
     *
     * @param tc             the test case
     * @param retvalType     the type of the return value
     * @param targetClass    the class to mock
     * @param validateTarget whether to re-run target mockability checks
     */
    private FunctionalMockStatement(TestCase tc, Type retvalType, GenericClass<?> targetClass,
                                    boolean validateTarget) throws IllegalArgumentException {
        super(tc, retvalType);
        Inputs.checkNull(targetClass);

        Class<?> rawType = GenericClassFactory.get(retvalType).getRawClass();
        Class<?> targetRawClass = targetClass.getRawClass();
        if (!targetRawClass.equals(rawType)
                && !targetRawClass.isAssignableFrom(rawType)
                && !rawType.isAssignableFrom(targetRawClass)) {
            throw new IllegalArgumentException("Mismatch between raw type " + rawType + " and target class "
                    + targetClass);
        }

        this.targetClass = targetClass;
        stubs = new ArrayList<>();
        if (validateTarget) {
            checkTarget();
        }
        assert parameters.isEmpty();
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {

        targetClass.changeClassLoader(loader);
        for (Stub stub : stubs) {
            stub.method.changeClassLoader(loader);
        }
        if (listener != null) {
            listener.changeClassLoader(loader);
        }
        super.changeClassLoader(loader);
    }

    protected void checkTarget() {
        if (!canBeFunctionalMocked(targetClass.getRawClass())) {
            throw new IllegalArgumentException("Cannot create a basic functional mock for class " + targetClass);
        }
    }

    /**
     * Checks if a type can be functional mocked, including the SUT.
     *
     * @param type the type to check
     * @return true if it can be mocked
     */
    public static boolean canBeFunctionalMockedIncludingSUT(Type type) {

        Class<?> rawClass = GenericClassFactory.get(type).getRawClass();

        if (EvoSuiteMock.class.isAssignableFrom(rawClass)
                || MockList.isAMockClass(rawClass.getName())
                || rawClass.equals(Class.class)
                || rawClass.isArray() || rawClass.isPrimitive() || rawClass.isAnonymousClass()
                || rawClass.isEnum()
                || isSealed(rawClass)
                || isRecord(rawClass)
                // note: Mockito can handle package-level classes,
                // but we get all kinds of weird exceptions with instrumentation :(
                || !Modifier.isPublic(rawClass.getModifiers())) {
            return false;
        }

        if (UnmockableTypes.isUnmockable(rawClass)) {
            return false;
        }

        if (!InstrumentedClass.class.isAssignableFrom(rawClass)
                && Modifier.isFinal(rawClass.getModifiers())) {
            /*
                if a class has not been instrumented (eg because belonging to javax.*),
                then if it is final we cannot mock it :(
                recall that instrumentation does remove the final modifiers
             */
            return false;
        }

        if (MethodDescriptor.wasOriginallyFinal(rawClass)) {
            // Final in the original bytecode: the check above no longer sees that once
            // InstrumentingClassLoader has stripped the modifier. The generated test's JVM
            // usually loads the class unstripped (see MethodDescriptor.shouldBeMocked), and
            // there Mockito's subclass mock maker cannot mock a final class at all.
            return false;
        }

        if (InetSocketAddress.class.equals(rawClass)) {
            /*
             InetSocketAddress declares hashCode as final and thus cannot be mocked:
             https://github.com/mockito/mockito/issues/310
             */
            return false;
        }

        try {
            // If dependencies are missing, this may throw a NoClassDefFoundException
            rawClass.getDeclaredMethods();
        } catch (NoClassDefFoundError e) {
            AtMostOnceLogger.warn(logger, "Problem with class " + rawClass.getName() + ": " + e);
            return false;
        }

        // avoid cases of infinite recursions
        boolean onlySelfReturns = true;
        for (Method m : rawClass.getDeclaredMethods()) {
            if (!rawClass.equals(m.getReturnType())) {
                onlySelfReturns = false;
                break;
            }
        }

        if (onlySelfReturns && rawClass.getDeclaredMethods().length > 0) {
            // avoid weird cases like java.lang.Appendable
            return false;
        }

        // Reject I/O source classes whose consumers loop until a termination
        // signal (e.g. EOF = -1).  A mock's default int return is 0, which is a
        // valid character/byte, so the consumer loops forever and the thread
        // stalls until timeout.
        if (java.io.Reader.class.isAssignableFrom(rawClass)
                || java.io.InputStream.class.isAssignableFrom(rawClass)) {
            return false;
        }

        // A Mockito Path does not satisfy the NIO contract: default answers
        // return null for getFileSystem()/getParent(), which makes Files.*
        // calls fail before useful SUT behavior is exercised.
        if (java.nio.file.Path.class.isAssignableFrom(rawClass)) {
            return false;
        }

        // Reject ForkJoinTask types.  A mocked ForkJoinTask's exec() returns
        // false (default mock), so the task never "completes".  When SUT code
        // calls invokeAll()/join() on such a mock, ForkJoinTask.awaitDone()
        // parks the thread forever.  Worse, awaitDone() swallows interrupts
        // (sets a flag but continues waiting), so Thread.interrupt() from the
        // timeout handler cannot stop it.
        if (java.util.concurrent.ForkJoinTask.class.isAssignableFrom(rawClass)) {
            return false;
        }

        // Reject ManagedBlocker types.  ForkJoinPool.managedBlock() loops:
        //   while (!blocker.isReleasable()) if (blocker.block()) break;
        // A mock's isReleasable() returns false and block() returns false,
        // creating an infinite CPU-burning loop with no interrupt check.
        if (java.util.concurrent.ForkJoinPool.ManagedBlocker.class.isAssignableFrom(rawClass)) {
            return false;
        }

        return true;
    }

    private static boolean isSealed(Class<?> rawClass) {
        try {
            Method isSealed = Class.class.getMethod("isSealed");
            return (Boolean) isSealed.invoke(rawClass);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }

    private static boolean isRecord(Class<?> rawClass) {
        try {
            Method isRecord = Class.class.getMethod("isRecord");
            return (Boolean) isRecord.invoke(rawClass);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }

    /**
     * Checks if a type can be functional mocked.
     *
     * @param type the type to check
     * @return true if it can be mocked
     */
    public static boolean canBeFunctionalMocked(Type type) {

        Class<?> rawClass = GenericClassFactory.get(type).getRawClass();
        String targetClassName = Properties.TARGET_CLASS;
        if (targetClassName != null && !targetClassName.isEmpty()) {
            String rawClassName = rawClass.getName();
            if (rawClassName.equals(targetClassName)) {
                return false;
            }
        }

        final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

        if (targetClass != null && GenericClassUtils.isAssignable(targetClass, rawClass)) {
            return false;
        }

        return canBeFunctionalMockedIncludingSUT(type);
    }

    private static final Map<Class<?>, Boolean> usefulMockSurfaceCache = new ConcurrentHashMap<>();

    /**
     * Checks whether mocking {@code rawClass} could ever let the search control any
     * observable outcome. A functional mock only helps the search by letting it stub
     * a method's return value; {@link MethodDescriptor#shouldBeMocked()} already refuses
     * void methods, {@code equals}/{@code hashCode}/{@code toString}, private methods, and
     * (since a class' methods can only be intercepted if not final in the original
     * bytecode - see {@link MethodDescriptor#shouldBeMocked()}) methods that were final
     * before instrumentation. When every instance method of a type falls into one of
     * those buckets, a mock of it is inert: Mockito creates the object, but every call
     * on it either runs unstubbable real code or returns Mockito's default answer, with
     * no way for the search to steer it. In that situation the search should prefer a
     * real generator over spending evaluations on a mock that cannot be controlled.
     *
     * <p>This applies the method filter of {@code MethodDescriptor.shouldBeMocked()}
     * ({@link MethodDescriptor#isStubbable(Method)}) but needs no {@code GenericMethod}
     * instance, so it can be asked before a mock is actually created.
     *
     * @param rawClass the type that would be mocked
     * @return true if at least one method exists that a functional mock could usefully stub
     */
    public static boolean hasUsefulMockSurface(Class<?> rawClass) {
        // Called on the object-creation hot path (once per interface/abstract dependency
        // resolution), so cache the per-class reflection scan below.
        return usefulMockSurfaceCache.computeIfAbsent(rawClass, FunctionalMockStatement::computeHasUsefulMockSurface);
    }

    private static boolean computeHasUsefulMockSurface(Class<?> rawClass) {
        Set<Method> candidates = new LinkedHashSet<>();
        for (Class<?> c = rawClass; c != null; c = c.getSuperclass()) {
            candidates.addAll(Arrays.asList(c.getDeclaredMethods()));
        }
        candidates.addAll(Arrays.asList(rawClass.getMethods()));

        for (Method method : candidates) {
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
                continue;
            }
            if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                continue;
            }
            if (MethodDescriptor.isStubbable(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears the {@link #hasUsefulMockSurface(Class)} cache. Called by
     * {@code TestGenerationContext.resetContext()} alongside {@link RemoveFinalClassAdapter#reset()},
     * whose bookkeeping this cache is derived from.
     */
    public static void resetUsefulMockSurfaceCache() {
        usefulMockSurfaceCache.clear();
    }

    /**
     * Add a method stubbing to this mock statement. Used by the test parser to
     * reconstruct FunctionalMockStatements from parsed source code.
     *
     * <p>A method is stubbed at most once. Stubbing a method again replaces its values, as a
     * second {@code doReturn(...).when(mock)} for the same method does in the generated test.
     *
     * @param md           the method descriptor for the stubbed method
     * @param returnValues the return values for successive calls to the method
     */
    public void addMethodStubbing(MethodDescriptor md, List<VariableReference> returnValues) {
        Inputs.checkNull(md, returnValues);
        populatedFromParser = true;
        Stub existing = findStub(stubs, md.getID());
        if (existing == null) {
            stubs.add(new Stub(md, returnValues));
        } else {
            existing.values.clear();
            existing.values.addAll(returnValues);
            stubReplacedSinceUpdate = true;
        }
        refreshParameters();
    }

    private static Stub findStub(List<Stub> stubs, String id) {
        for (Stub stub : stubs) {
            if (stub.method.getID().equals(id)) {
                return stub;
            }
        }
        return null;
    }

    /**
     * Remove all method stubs whose return-value {@link VariableReference}s are
     * no longer defined by any statement in the test case. This is called after
     * {@link org.evosuite.testcase.TestCase#chop} so that crossover cannot produce
     * orphaned references that crash {@code TestCase.clone()} in a later generation.
     * Stubs whose values are all still resolvable are kept intact.
     */
    public void dropUnresolvableStubs() {
        if (parameters.isEmpty()) {
            return;
        }
        // Collect all VariableReferences currently defined in the test case.
        Set<VariableReference> present = new HashSet<>();
        for (int i = 0; i < tc.size(); i++) {
            try {
                VariableReference rv = tc.getStatement(i).getReturnValue();
                if (rv != null) {
                    present.add(rv);
                }
            } catch (Throwable ignored) {
                // Defensive: keep scanning.
            }
        }

        // A stub that references a removed variable is dropped; the mock falls back to its
        // default answer for that method. null and ConstantValue are self-contained.
        stubs.removeIf(stub -> {
            for (VariableReference ref : stub.values) {
                if (ref != null && !(ref instanceof ConstantValue) && !present.contains(ref)) {
                    return true;
                }
            }
            return false;
        });
        refreshParameters();
    }

    /**
     * getTargetClass.
     *
     * @return the target class
     */
    public Class<?> getTargetClass() {
        return targetClass.getRawClass();
    }

    /**
     * Enables the lenient default answer for this mock. When enabled, unstubbed
     * method calls return sensible non-null defaults instead of null.
     */
    public void setUseLenientDefaultAnswer(boolean lenient) {
        this.useLenientDefaultAnswer = lenient;
    }

    public boolean isUseLenientDefaultAnswer() {
        return useLenientDefaultAnswer;
    }

    public void setDmonPromotion(boolean dmonPromotion) {
        this.isDmonPromotion = dmonPromotion;
    }

    public boolean isDmonPromotion() {
        return isDmonPromotion;
    }

    /**
     * getMockedMethods.
     *
     * @return the mocked methods, in the order of their stubs
     */
    public List<MethodDescriptor> getMockedMethods() {
        List<MethodDescriptor> methods = new ArrayList<>(stubs.size());
        for (Stub stub : stubs) {
            methods.add(stub.method);
        }
        return Collections.unmodifiableList(methods);
    }

    /**
     * @return the stubs of this mock in the order they are declared, including methods that
     * have no values
     */
    public List<Stub> getStubs() {
        return Collections.unmodifiableList(stubs);
    }

    /**
     * Immutable description of an invocation observed during the most recent execution which
     * still needs one or more return values. This deliberately contains no listener or Mockito
     * state, so setup-repair recipes can be copied and cached safely.
     */
    public static final class MockInvocationObservation {
        private final MethodDescriptor descriptor;
        private final Type returnType;
        private final int missingReturnValues;

        private MockInvocationObservation(MethodDescriptor descriptor, Type returnType,
                                          int missingReturnValues) {
            this.descriptor = descriptor.getCopy();
            this.returnType = returnType;
            this.missingReturnValues = missingReturnValues;
        }

        public MethodDescriptor getDescriptor() {
            return descriptor.getCopy();
        }

        public Type getReturnType() {
            return returnType;
        }

        public int getMissingReturnValues() {
            return missingReturnValues;
        }
    }

    /**
     * Returns a non-mutating snapshot of newly required mock return values learned from the most
     * recent execution. Existing stubs and the global functional-mocking input bound are honored.
     */
    public List<MockInvocationObservation> getInvocationObservationsNeedingStubs() {
        if (listener == null || useLenientDefaultAnswer) {
            return Collections.emptyList();
        }
        List<MockInvocationObservation> observations = new ArrayList<>();
        GenericClass<?> calleeClass = GenericClassFactory.get(retval.getGenericClass());
        for (MethodDescriptor descriptor : listener.getCopyOfMethodDescriptors()) {
            if (descriptor == null || !descriptor.shouldBeMocked() || descriptor.getCounter() <= 0) {
                continue;
            }
            Stub stub = findStub(stubs, descriptor.getID());
            int existing = stub == null ? 0 : stub.values.size();
            int required = Math.min(descriptor.getCounter(), Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT);
            int missing = required - existing;
            if (missing <= 0) {
                continue;
            }
            try {
                Type returnType = descriptor.getGenericMethodFor(calleeClass).getGeneratedType();
                if (!Void.TYPE.equals(returnType)) {
                    observations.add(new MockInvocationObservation(descriptor, returnType, missing));
                }
            } catch (ConstructionFailedException failure) {
                logger.debug("Could not resolve observed functional-mock return type for {}",
                        descriptor.getID(), failure);
            }
        }
        return Collections.unmodifiableList(observations);
    }

    /**
     * The emitted code calls each stubbed method on the mock
     * ({@code doReturn(...).when(mock).method(args)}), so its checked
     * exceptions must be either caught or declared in the surrounding
     * method signature, even though Mockito intercepts the call at runtime.
     */
    @Override
    public Set<Class<?>> getDeclaredExceptions() {
        Set<Class<?>> ex = super.getDeclaredExceptions();
        if (useLenientDefaultAnswer) {
            return ex;
        }
        for (Stub stub : stubs) {
            MethodDescriptor md = stub.method;
            if (!md.shouldBeMocked()) {
                continue;
            }
            Method m = md.getMethod();
            if (m == null) {
                continue;
            }
            ex.addAll(Arrays.asList(m.getExceptionTypes()));
        }
        return ex;
    }

    /**
     * One stubbed method of a mock and the values its successive calls return. A method that
     * cannot be stubbed or was not called has no values.
     */
    public static final class Stub implements Serializable {

        private static final long serialVersionUID = 3606227935226745466L;

        private final MethodDescriptor method;
        private final List<VariableReference> values;

        private Stub(MethodDescriptor method, List<VariableReference> values) {
            this.method = method;
            this.values = new ArrayList<>(values);
        }

        public MethodDescriptor getMethod() {
            return method;
        }

        public List<VariableReference> getValues() {
            return Collections.unmodifiableList(values);
        }
    }

    /**
     * Rebuilds {@code super.parameters}, which holds the values of all stubs in order, from the
     * stubs. Called after every change of the stubs.
     */
    private void refreshParameters() {
        parameters.clear();
        for (Stub stub : stubs) {
            parameters.addAll(stub.values);
        }
    }

    /**
     * Writes {@code super.parameters} back into the stubs, after the base class replaced some of
     * the values in place.
     */
    private void readBackParameters() {
        int index = 0;
        for (Stub stub : stubs) {
            for (int i = 0; i < stub.values.size(); i++) {
                stub.values.set(i, parameters.get(index++));
            }
        }
    }

    @Override
    protected void applyReplacement(VariableReference var1, VariableReference var2) {
        super.applyReplacement(var1, var2);
        readBackParameters();
    }

    @Override
    public void replaceParameterReference(VariableReference var, int numParameter)
            throws IllegalArgumentException {
        super.replaceParameterReference(var, numParameter);
        readBackParameters();
    }

    /**
     * The stubs of the generated code: every mocked method that returns a value, with its values.
     * A mock whose stubs lag behind its last execution is shown as
     * {@link #completePendingStubsWithNulls()} would leave it, but that completion is done on
     * a copy of the stubs, so this statement is not changed.
     *
     * @return the stubs in the order they are declared
     */
    public List<Stub> getStubsToRender() {
        List<Stub> source = stubs;
        if (doesNeedToUpdateInputs()) {
            source = new ArrayList<>(stubs.size());
            for (Stub stub : stubs) {
                source.add(new Stub(stub.method, stub.values));
            }
            completePendingStubsWithNulls(source);
        }

        List<Stub> rendered = new ArrayList<>();
        for (Stub stub : source) {
            if (stub.method.shouldBeMocked() && !stub.values.isEmpty()) {
                rendered.add(stub);
            }
        }
        return rendered;
    }

    /**
     * Brings the stubs in line with the last execution, as the generated test declares them:
     * a call that has no value yet returns the value of the previous call of the same method, or
     * null (a default value for a primitive). The search instead generates values for those
     * calls; see {@link #updateMockedMethods()} and {@link #addMissingInputs(List)}.
     */
    public void completePendingStubsWithNulls() {
        if (doesNeedToUpdateInputs()) {
            stubReplacedSinceUpdate = false;
            completePendingStubsWithNulls(stubs);
        }
    }

    /**
     * {@link #completePendingStubsWithNulls()} for every functional mock of the test.
     *
     * @param test the test whose mocks are completed
     */
    public static void completePendingStubsWithNulls(TestCase test) {
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof FunctionalMockStatement) {
                ((FunctionalMockStatement) statement).completePendingStubsWithNulls();
            }
        }
    }

    private void completePendingStubsWithNulls(List<Stub> stubs) {
        try {
            completeStubs(stubs, null);
        } catch (ConstructionFailedException | RuntimeException e) {
            // A return type that cannot be resolved leaves the stubs as far as they were updated
        }
    }

    /**
     * Creates the values of the calls that a stub has no value for yet.
     */
    @FunctionalInterface
    public interface StubValueFactory {
        /**
         * Creates the values; it may add statements in front of the mock to do so.
         *
         * @param types the return types of the calls without a value, in the order of the stubs;
         *              possibly empty
         * @return one value for each type, in the same order
         * @throws ConstructionFailedException if the values cannot be created
         */
        List<VariableReference> create(List<Type> types) throws ConstructionFailedException;
    }

    /**
     * Brings the stubs in line with the last execution, as {@link #updateMockedMethods()} does,
     * and gives each call that has no value yet the value that {@code values} creates for it. If
     * that fails, the calls still without a value are answered as in
     * {@link #completePendingStubsWithNulls()} before the failure is passed on, so the stubs are
     * complete either way.
     *
     * @param values creates the values of the calls that have none
     * @return the values that were added
     * @throws ConstructionFailedException if a return type cannot be resolved or the values cannot
     *                                     be created
     */
    public List<VariableReference> completeStubs(StubValueFactory values) throws ConstructionFailedException {
        Inputs.checkNull(values);
        stubReplacedSinceUpdate = false;
        return completeStubs(stubs, values);
    }

    /**
     * Completes the given stubs, which are either this statement's own or a copy of them.
     *
     * @param values creates the missing values; if null, a call without a value returns the value
     *               of the previous call of its method, or null
     */
    private List<VariableReference> completeStubs(List<Stub> stubs, StubValueFactory values)
            throws ConstructionFailedException {
        boolean own = stubs == this.stubs;
        List<VariableReference> created = Collections.emptyList();
        try {
            List<Type> missing = updateStubs(stubs);
            if (values != null) {
                if (own) {
                    // the placeholders are inputs of this statement while the values are created
                    refreshParameters();
                }
                created = values.create(missing);
                setMissingValues(stubs, created);
            }
        } catch (ConstructionFailedException | RuntimeException e) {
            fillWithNullRefs(stubs);
            if (own) {
                refreshParameters();
            }
            throw e;
        }
        fillWithNullRefs(stubs);
        if (own) {
            refreshParameters();
        }
        return created;
    }

    /**
     * Check if the last execution of the test case has led a change in the usage of the mock.
     * This will result in adding/removing variable references.
     *
     * @return true if update is needed
     */
    public boolean doesNeedToUpdateInputs() {
        // Lenient mocks (DMoN) don't need explicit stubs — the lenient default
        // answer already returns sensible non-null values for all return types.
        // Materializing would add null stubs that override the lenient defaults.
        if (useLenientDefaultAnswer) {
            return false;
        }
        if (listener == null) {
            /*
                Tricky case: if no execution yet, then there should be no mocked method yet.
                However, this method is also executed when JUnit source code is generated.
                If this is done in a system test for debugging, then it would be a problem,
                as serialized tests sent from Client to Master have no listener (it has to be
                transient). So, we can just skip it, as info used only for debugging.

                Note: stubs may exist when reconstructed from parsed source
                code (via addMethodStubbing), so we also allow that case.
             */

            assert stubs.isEmpty() || RuntimeSettings.isRunningASystemTest
                    || populatedFromParser;

            return false;
        }

        if (stubReplacedSinceUpdate) {
            // the descriptor of a replaced stub does not tell how many calls its values cover
            return true;
        }

        List<MethodDescriptor> executed = listener.getCopyOfMethodDescriptors();
        if (executed.size() != stubs.size()) {
            return true;
        }

        for (int i = 0; i < executed.size(); i++) {
            MethodDescriptor previous = stubs.get(i).method;
            MethodDescriptor now = executed.get(i);

            if (!previous.getID().equals(now.getID())) {
                return true;
            }

            if (!now.shouldBeMocked()) {
                /*
                    Do not change in the usage of non-mockable methods, because anyway
                    we do not have any VarRef for them
                 */
                continue;
            }

            /*
                need to be a mismatch. However, even in that case, either the current should
                not have reached the limit (and so we could not increase) OR if it is reached
                then the needed number of mocked v has increased.

                For example, if limit is 5, and previous is 10, then decreasing by 5 or
                 increasing by any amount should have no impact
             */

            if (now.getCounter() != previous.getCounter()
                    && (now.getCounter() < Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT
                    || previous.getCounter() < Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT)
            ) {
                return true;
            }
        }

        return false;
    }


    /**
     * Updates the stubs from the last execution: a stub for every method that was called, in
     * the order of the observed calls, keeping the values the method already had. A value for
     * a call that has none yet is a null placeholder, to be set with
     * {@link #addMissingInputs(List)} or {@link #fillWithNullRefs()}.
     *
     * @return a ordered, non-null list of types of missing new inputs that will need to be provided
     * @throws ConstructionFailedException if construction fails
     */
    public List<Type> updateMockedMethods() throws ConstructionFailedException {
        stubReplacedSinceUpdate = false;
        try {
            return updateStubs(stubs);
        } finally {
            refreshParameters();
        }
    }

    /**
     * Updates the given stubs, which are either this statement's own or a copy of them, from
     * the last execution. If a return type cannot be resolved, the stubs are left as far as
     * they were updated: the stub of that method keeps the values it had.
     *
     * @return the types of the values that are now missing, which have null placeholders
     */
    private List<Type> updateStubs(List<Stub> stubs) throws ConstructionFailedException {

        logger.debug("Executing updateMockedMethods. Number of stubs: {}", stubs.size());

        List<Type> list = new ArrayList<>();
        List<Stub> updated = new ArrayList<>();

        try {
            for (MethodDescriptor md : listener.getCopyOfMethodDescriptors()) {
                Stub previous = findStub(stubs, md.getID());
                Stub stub = new Stub(md, Collections.emptyList());
                updated.add(stub);

                if (!md.shouldBeMocked() || md.getCounter() == 0) {
                    //void method or not called, so no parameter needed for it
                    continue;
                }

                logger.debug("Method called on mock object: {}", md.getMethod());

                int needed = Math.min(md.getCounter(), Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT);
                if (previous != null) {
                    // keep the values of the calls that still happen, as far as the limit allows
                    assert !previous.values.contains(null);
                    stub.values.addAll(previous.values.subList(0, Math.min(needed, previous.values.size())));
                }

                // values for the calls that are new
                while (stub.values.size() < needed) {
                    // Create a copy as the typemap is stored in the class during generic instantiation
                    // but we might want to have a different type for each call of the same method invocation
                    GenericClass<?> calleeClass = GenericClassFactory.get(retval.getGenericClass());
                    Type returnType = md.getGenericMethodFor(calleeClass).getGeneratedType();
                    assert !returnType.equals(Void.TYPE);
                    logger.debug("Return type: {} for retval {}", returnType,
                            retval.getGenericClass());
                    list.add(returnType);

                    stub.values.add(null); //important place holder for following updates
                }
            }
        } finally {
            stubs.clear();
            stubs.addAll(updated);
        }

        return list;
    }

    /**
     * addMissingInputs.
     *
     * @param inputs the values for the null placeholders, in order
     * @throws IllegalArgumentException if inputs is null or mismatch
     */
    public void addMissingInputs(List<VariableReference> inputs) throws IllegalArgumentException {
        try {
            setMissingValues(stubs, inputs);
        } finally {
            refreshParameters();
        }
    }

    private static void setMissingValues(List<Stub> stubs, List<VariableReference> inputs)
            throws IllegalArgumentException {
        Inputs.checkNull(inputs);

        logger.debug("Adding {} missing values", inputs.size());

        int size = 0;
        for (Stub stub : stubs) {
            size += stub.values.size();
        }
        if (inputs.size() > size) {
            //first quick check
            throw new IllegalArgumentException("Not enough parameter place holders");
        }

        Iterator<VariableReference> next = inputs.iterator();
        for (Stub stub : stubs) {
            for (int i = 0; i < stub.values.size() && next.hasNext(); i++) {
                if (stub.values.get(i) == null) {
                    VariableReference ref = next.next();
                    assert ref.isAssignableTo(stub.method.getMethod().getReturnType());
                    stub.values.set(i, ref);
                }
            }
        }
        if (next.hasNext()) {
            throw new IllegalArgumentException("Not enough parameter place holders");
        }

        //check if all "holes" have been filled
        for (Stub stub : stubs) {
            if (stub.values.contains(null)) {
                throw new IllegalArgumentException("Functional mock not fully set with all"
                        + " needed missing inputs");
            }
        }
    }

    /**
     * Sets every null placeholder: a call returns the value of the previous call of the same
     * method, or null (a default value for a primitive) if it is the first call.
     */
    public void fillWithNullRefs() {
        fillWithNullRefs(stubs);
        refreshParameters();
    }

    /**
     * A mock answers every call beyond its last stubbed value with that last value, so a call
     * that was not stubbed when the test was observed returned it there; a null in its place
     * would make the rendered test behave differently from the observation.
     */
    private void fillWithNullRefs(List<Stub> stubs) {
        for (Stub stub : stubs) {
            for (int i = 0; i < stub.values.size(); i++) {
                if (stub.values.get(i) != null) {
                    continue;
                }
                if (i > 0) {
                    stub.values.set(i, stub.values.get(i - 1));
                    continue;
                }
                Class<?> expected = stub.method.getMethod().getReturnType();
                Object value = null;
                if (expected.isPrimitive()) {
                    //can't fill a primitive with null
                    if (expected.equals(Integer.TYPE)) {
                        value = 0;
                    } else if (expected.equals(Float.TYPE)) {
                        value = 0f;
                    } else if (expected.equals(Double.TYPE)) {
                        value = 0d;
                    } else if (expected.equals(Long.TYPE)) {
                        value = 0L;
                    } else if (expected.equals(Boolean.TYPE)) {
                        value = false;
                    } else if (expected.equals(Short.TYPE)) {
                        value = Short.valueOf("0");
                    } else if (expected.equals(Byte.TYPE)) {
                        value = Byte.valueOf("0");
                    } else if (expected.equals(Character.TYPE)) {
                        value = 'a';
                    }
                }
                stub.values.set(i, new ConstantValue(tc, GenericClassFactory.get(expected), value));
            }
        }
    }

    //------------ override methods ---------------


    @Override
    public void addAssertion(Assertion assertion) {
        //never add an assertion to a functional mock
    }


    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        FunctionalMockStatement copy = new FunctionalMockStatement(
                newTestCase, retval.getType(), targetClass, false);
        copyStubsInto(copy, newTestCase, offset);
        return copy;
    }

    /**
     * Gives a new copy of this mock its stubs, listener and flags.
     *
     * @param copy        the copy, which has no stubs yet
     * @param newTestCase the test of the copy
     * @param offset      the offset of the copy's statements in {@code newTestCase}
     */
    protected void copyStubsInto(FunctionalMockStatement copy, TestCase newTestCase, int offset) {
        for (Stub stub : stubs) {
            List<VariableReference> values = new ArrayList<>(stub.values.size());
            for (VariableReference r : stub.values) {
                values.add(r.copy(newTestCase, offset));
            }
            copy.stubs.add(new Stub(stub.method.getCopy(), values));
        }
        copy.refreshParameters();

        copy.listener = this.listener; //no need to clone, as only read, and created new instance at each new execution
        copy.populatedFromParser = this.populatedFromParser;
        copy.useLenientDefaultAnswer = this.useLenientDefaultAnswer;
        copy.isDmonPromotion = this.isDmonPromotion;
        copy.stubReplacedSinceUpdate = this.stubReplacedSinceUpdate;

        copyProvenanceFrom(copy, this);
    }

    protected EvoInvocationListener createInvocationListener() {
        return new EvoInvocationListener(retval.getGenericClass());
    }

    protected MockSettings createMockSettings() {
        // stubOnly() prevents Mockito from recording invocations in its internal
        // LinkedList, which otherwise grows unboundedly when a mock is called in
        // a tight loop and causes OOM.  EvoSuite tracks invocations independently
        // via EvoInvocationListener, so the Mockito invocation log is never needed.
        //
        // No explicit MockMaker is specified here.  The SubclassByteBuddyMockMaker
        // is configured as the default via the mockito-extensions SPI file
        // (mockito-extensions/org.evosuite.shaded.org.mockito.plugins.MockMaker).
        // Specifying MockMakers.SUBCLASS explicitly would cause Mockito 5.x to
        // load a SECOND mock maker instance (because the alias "mock-maker-subclass"
        // now resolves to ByteBuddyMockMaker, not SubclassByteBuddyMockMaker),
        // triggering an internal AssertionError in MockUtil.getMockHandlerOrNull.
        MockSettings settings = withSettings().stubOnly().invocationListeners(listener);
        Answer<Object> defaultAnswer = useLenientDefaultAnswer
                ? SEARCH_LENIENT_ANSWER
                : Mockito.RETURNS_DEFAULTS;
        if (!Properties.USE_SEPARATE_CLASSLOADER) {
            defaultAnswer = callRealMethodIfOriginallyFinal(defaultAnswer);
        }
        return settings.defaultAnswer(defaultAnswer);
    }

    /**
     * InstrumentingClassLoader strips {@code final} from methods, so during the search Mockito
     * intercepts a final method that SUT code calls on a mock and the default answer (null, 0)
     * comes back. The generated test's JVM usually loads the class unstripped, where Mockito's
     * subclass mock maker cannot intercept the method and the real implementation runs on the
     * mock's uninitialised fields (typically a NullPointerException from inside the final
     * method). To keep search and replay in agreement, a method that was final in the original
     * bytecode runs its real implementation during the search too. With
     * use_separate_classloader the test JVM strips the modifier as well, so this is not needed.
     */
    static Answer<Object> callRealMethodIfOriginallyFinal(Answer<Object> fallback) {
        return invocation -> {
            if (MethodDescriptor.wasOriginallyFinal(invocation.getMethod())) {
                return invocation.callRealMethod();
            }
            return fallback.answer(invocation);
        };
    }

    /**
     * The lenient default answer of a mock during the search. It is the {@link LenientMockAnswer}
     * of the generated test, so that a DMoN-promoted mock answers the same in both, except that it
     * does not hand out mocks of classes that were final in the original bytecode: the test JVM
     * usually loads those unstripped, where they cannot be mocked. The ephemeral mocks DMoN uses
     * to probe a failure (TestRunnable) answer differently on purpose.
     */
    static final class SearchLenientAnswer extends LenientMockAnswer {

        SearchLenientAnswer() {
            super(0);
        }

        private SearchLenientAnswer(int chain) {
            super(chain);
        }

        @Override
        protected LenientMockAnswer forMockAt(int chain) {
            return new SearchLenientAnswer(chain);
        }

        @Override
        protected boolean canMock(Class<?> type) {
            // getModifiers() no longer shows final once the class is instrumented
            return super.canMock(type) && !MethodDescriptor.wasOriginallyFinal(type);
        }
    }

    private static final Answer<Object> SEARCH_LENIENT_ANSWER = new SearchLenientAnswer();

    /** The answer of a mock with the lenient default answer during the search. */
    static Object lenientDefaultAnswer(InvocationOnMock invocation) throws Throwable {
        return SEARCH_LENIENT_ANSWER.answer(invocation);
    }

    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws InvocationTargetException, IllegalArgumentException,
            IllegalAccessException, InstantiationException {

        Throwable exceptionThrown = null;

        // A mutant active during this execution can change the SUT's control flow
        // -- skipping or repeating a call to a mocked method -- so the invocation
        // pattern this run observes need not match the one the real, unmutated run
        // produced, which is the run the emitted assertions and this statement's
        // persisted stub values are based on. TestCodeVisitor treats "listener" as
        // the authoritative record of what to stub and silently rewrites the return
        // values (falling back to defaults) whenever it looks stale, so letting a
        // mutant's run update it desynchronizes the emitted stub from the emitted
        // assertion and JUnitAnalyzer later discards the test as unstable. Keep the
        // bookkeeping tied to real executions only.
        final boolean executingUnderMutation = MutationObserver.activeMutation != -1;
        final EvoInvocationListener listenerBeforeExecution = listener;
        final MockStatementExecuter executer = new MockStatementExecuter(scope);

        try {
            return super.exceptionHandler(executer);
        } catch (InvocationTargetException e) {
            exceptionThrown = e.getCause();
        } finally {
            // A set-up that failed leaves an empty listener, and the stubs are rebuilt from the
            // listener: the record of the last good execution is the one to keep.
            if (executingUnderMutation || (!executer.activated && listenerBeforeExecution != null)) {
                listener = listenerBeforeExecution;
            }
        }
        return exceptionThrown;
    }

    private class MockStatementExecuter extends Executer {
        private final Scope scope;
        /** Whether the mock was set up and its listener activated. */
        private boolean activated;

        public MockStatementExecuter(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void execute() throws InvocationTargetException,
                IllegalArgumentException, IllegalAccessException,
                InstantiationException, CodeUnderTestException {

            String className = targetClass.getRawClass().getName();
            if (FunctionalMockFailover.isFunctionalMockingGloballyDisabled()) {
                throw new CodeUnderTestException(new IllegalStateException(
                        "Functional mocking disabled globally due to repeated initialization failures"));
            }
            if (FunctionalMockFailover.isClassBlacklisted(className)) {
                throw new CodeUnderTestException(new IllegalStateException(
                        "Functional mocking disabled for class due to prior initialization failure: " + className));
            }

            // First create the listener
            listener = createInvocationListener();

            //then create the mock
            Object ret = null;
            try {
                FunctionalMockFailover.registerMockAttempt();

                MockSettings settings = createMockSettings();
                // Check if a more specific type should be used (e.g., Graphics2D
                // instead of Graphics) because the SUT downcasts the parameter.
                Class<?> mockedClass = targetClass.getRawClass();
                String upgrade = FunctionalMockFailover.getMockTypeUpgrade(mockedClass.getName());
                if (upgrade != null) {
                    try {
                        Class<?> upgraded = Class.forName(upgrade, false,
                                targetClass.getRawClass().getClassLoader());
                        if (targetClass.getRawClass().isAssignableFrom(upgraded)
                                && canBeFunctionalMocked(upgraded)) {
                            mockedClass = upgraded;
                            // The target class is part of hashCode() and of how the statement
                            // is rendered: only a real execution changes it.
                            if (MutationObserver.activeMutation == -1) {
                                targetClass = GenericClassFactory.get(upgraded);
                                retval.setType(upgraded);
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        logger.debug("Could not load upgraded mock type {}: {}", upgrade, e.getMessage());
                    }
                }
                logger.debug("Mockito: create mock for {}", targetClass);
                ret = mock(mockedClass, settings);

                // When the lenient default answer is active, skip explicit when/thenReturn
                // stubs entirely — the default answer already handles all method calls with
                // sensible non-null values.  Explicit stubs would override the defaults with
                // null (from fillWithNullRefs/satisfyParameters), defeating the purpose.
                if (!useLenientDefaultAnswer) {

                    //execute all "when" statements
                    logger.debug("Mockito: going to mock {} different methods", stubs.size());
                    for (Stub stub : stubs) {
                        MethodDescriptor md = stub.method;

                        if (!md.shouldBeMocked()) {
                            //no need to mock a method that returns void
                            logger.debug("Mockito: method {} cannot be mocked", md.getMethodName());
                            continue;
                        }

                        // target method, eg foo.aMethod(...), as the generated test names it
                        MethodDescriptor.StubTarget stubTarget =
                                md.getStubTarget(targetClass.getRawClass(), retval.getGenericClass());
                        Method method = resolveMethodOnRuntimeClass(stubTarget.getMethod(), ret.getClass());
                        if (method == null) {
                            AtMostOnceLogger.warn(logger,
                                    "Skipping mock stub due to classloader/signature mismatch for "
                                            + stubTarget.getMethod().getDeclaringClass().getName()
                                            + "." + md.getMethodName());
                            continue;
                        }

                        // this is needed if method is protected: it couldn't be called here,
                        // although fine in the generated JUnit tests
                        method.setAccessible(true);

                        //target inputs
                        Object[] targetInputs = new Object[stubTarget.getNumberOfParameters()];
                        for (int i = 0; i < targetInputs.length; i++) {
                            logger.debug("Mockito: executing matcher {}/{}", (1 + i),
                                    targetInputs.length);
                            targetInputs[i] = stubTarget.executeMatcher(i);
                        }

                        logger.debug("Mockito: going to invoke method {} with {} matchers",
                                method.getName(), targetInputs.length);

                        if (!method.getDeclaringClass().isAssignableFrom(ret.getClass())) {
                            AtMostOnceLogger.warn(logger,
                                    "Skipping mock stub due to incompatible callee "
                                            + ret.getClass().getName()
                                            + " for method "
                                            + method.getDeclaringClass().getName()
                                            + "." + method.getName());
                            continue;
                        }

                        //actual call foo.aMethod(...)
                        Object targetMethodResult;

                        try {
                            if (targetInputs.length == 0) {
                                targetMethodResult = method.invoke(ret);
                            } else {
                                targetMethodResult = method.invoke(ret, targetInputs);
                            }
                        } catch (InvocationTargetException e) {
                            // The mock setup call on the Mockito proxy threw — the method
                            // was not properly intercepted by Mockito (e.g., a final method,
                            // or Mockito's subclass proxy delegated to the real implementation).
                            // This is a mock infrastructure failure, not an EvoSuite bug —
                            // treat it as CodeUnderTestException so the search can continue.
                            AtMostOnceLogger.warn(logger,
                                    "Invocation of mocked " + targetClass.getClassName()
                                    + "." + method.getName() + "() was not intercepted by Mockito"
                                    + " and threw: "
                                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                            throw new CodeUnderTestException(
                                    e.getCause() != null ? e.getCause() : e);
                        } catch (IllegalArgumentException | IllegalAccessError e) {
                            // FIXME: Happens for reasons I don't understand. By throwing a
                            // CodeUnderTestException EvoSuite will just ignore that
                            // mocking statement and continue, instead of crashing
                            logger.error("IAE on <{}> when called with {}", method,
                                    Arrays.toString(targetInputs));
                            throw new CodeUnderTestException(e);
                        }

                        //when(...)
                        logger.debug("Mockito: call 'when'");
                        OngoingStubbing<Object> retForThen = Mockito.when(targetMethodResult);

                        //thenReturn(...)
                        Object[] thenReturnInputs = null;
                        try {
                            List<VariableReference> stubValues = stub.values;
                            int size = stubValues.size();

                            thenReturnInputs = new Object[size];

                            for (int i = 0; i < thenReturnInputs.length; i++) {

                                VariableReference parameterVar = stubValues.get(i);
                                thenReturnInputs[i] = parameterVar.getObject(scope);

                                CodeUnderTestException codeUnderTestException = null;

                                if (thenReturnInputs[i] == null && method.getReturnType().isPrimitive()) {
                                    codeUnderTestException = new CodeUnderTestException(
                                            new NullPointerException());

                                } else if (thenReturnInputs[i] != null
                                        && !TypeUtils.isAssignable(thenReturnInputs[i].getClass(),
                                        method.getReturnType())
                                        && !isAssignableByName(thenReturnInputs[i].getClass(),
                                        method.getReturnType())) {
                                    codeUnderTestException = new CodeUnderTestException(
                                            new UncompilableCodeException("Cannot assign "
                                                    + parameterVar.getVariableClass().getName()
                                                    + " to " + method.getReturnType()));
                                }

                                if (codeUnderTestException != null) {
                                    throw codeUnderTestException;
                                }

                                thenReturnInputs[i] = fixBoxing(thenReturnInputs[i],
                                        method.getReturnType());
                            }
                        } catch (Exception e) {
                            // be sure "then" is always called after a "when", otherwise
                            // Mockito might end up in a inconsistent state
                            retForThen.thenThrow(new RuntimeException("Failed to setup mock: "
                                    + e.getMessage()));
                            throw e;
                        }


                        //final call when(...).thenReturn(...)
                        logger.debug("Mockito: executing 'thenReturn'");
                        if (thenReturnInputs == null || thenReturnInputs.length == 0) {
                            retForThen.thenThrow(new RuntimeException("No valid return value"));
                        } else if (thenReturnInputs.length == 1) {
                            retForThen.thenReturn(thenReturnInputs[0]);
                        } else {
                            Object[] values = Arrays.copyOfRange(thenReturnInputs, 1,
                                    thenReturnInputs.length);
                            retForThen.thenReturn(thenReturnInputs[0], values);
                        }
                    }

                } // end if (!useLenientDefaultAnswer)
            } catch (CodeUnderTestException e) {
                throw e;
            } catch (java.lang.NoClassDefFoundError e) {
                AtMostOnceLogger.error(logger, "Cannot use Mockito on " + targetClass
                        + " due to failed class initialization: " + e.getMessage());
                FunctionalMockFailover.handleRecoverableMockFailure(
                        targetClass.getRawClass().getName(), e);
            } catch (IllegalStateException e) {
                AtMostOnceLogger.error(logger, "Cannot use Mockito on " + targetClass
                        + " due to ISE: " + e.getMessage());
                if (FunctionalMockFailover.isCodeUnderTestInitializationFailure(e)) {
                    FunctionalMockFailover.handleRecoverableMockFailure(
                            targetClass.getRawClass().getName(), e);
                }
                throw new CodeUnderTestException(e);
            } catch (MockitoException | IllegalAccessException | IllegalAccessError
                    | IllegalArgumentException e) {
                // FIXME: Happens for reasons I don't understand. By throwing a
                // CodeUnderTestException EvoSuite will just ignore that mocking
                // statement and continue, instead of crashing
                if (isSealed(targetClass.getRawClass()) || isRecord(targetClass.getRawClass())) {
                    AtMostOnceLogger.warn(logger, "Cannot use Mockito on " + targetClass
                            + " as it is a sealed class or a record: " + e.getMessage());
                } else {
                    AtMostOnceLogger.error(logger, "Cannot use Mockito on " + targetClass
                            + " due to IAE: " + e.getMessage());
                }
                throw new CodeUnderTestException(e); //or should throw an exception?
            } catch (Throwable t) {
                AtMostOnceLogger.error(logger, "Failed to use Mockito on " + targetClass
                        + ": " + t.getMessage());
                if (FunctionalMockFailover.isCodeUnderTestInitializationFailure(t)
                        || FunctionalMockFailover.isMockAccessRefusal(t)) {
                    FunctionalMockFailover.handleRecoverableMockFailure(
                            targetClass.getRawClass().getName(), t);
                }
                throw new EvosuiteError(t);
            }

            //finally, activate the listener
            listener.activate();
            activated = true;

            try {
                retval.setObject(scope, ret);
            } catch (CodeUnderTestException e) {
                throw e;
            } catch (Throwable e) {
                throw new EvosuiteError(e);
            }
        }

        @Override
        public Set<Class<? extends Throwable>> throwableExceptions() {
            Set<Class<? extends Throwable>> t = new LinkedHashSet<>();
            t.add(InvocationTargetException.class);
            return t;
        }
    }

    /**
     * a "char" can be used for a "int". But problem is that Mockito takes as input
     * Object, and so those get boxed. However, a Character cannot be used for a "int",
     * so we need to be sure to convert it here.
     *
     * @param value        the value.
     * @param expectedType the expected type.
     * @return .
     */
    private static Object fixBoxing(Object value, Class<?> expectedType) {

        if (!expectedType.isPrimitive()) {
            return value;
        }

        Class<?> valuesClass = value.getClass();
        assert !valuesClass.isPrimitive();

        if (expectedType.equals(Integer.TYPE)) {
            if (valuesClass.equals(Character.class)) {
                value = (int) (Character) value;
            } else if (valuesClass.equals(Byte.class)) {
                value = ((Byte) value).intValue();
            } else if (valuesClass.equals(Short.class)) {
                value = ((Short) value).intValue();
            }
        }

        if (expectedType.equals(Double.TYPE)) {
            if (valuesClass.equals(Integer.class)) {
                value = (double) (Integer) value;
            } else if (valuesClass.equals(Byte.class)) {
                value = (double) ((Byte) value).intValue();
            } else if (valuesClass.equals(Character.class)) {
                value = (double) (Character) value;
            } else if (valuesClass.equals(Short.class)) {
                value = (double) ((Short) value).intValue();
            } else if (valuesClass.equals(Long.class)) {
                value = (double) (Long) value;
            } else if (valuesClass.equals(Float.class)) {
                value = (double) (Float) value;
            }
        }

        if (expectedType.equals(Float.TYPE)) {
            if (valuesClass.equals(Integer.class)) {
                value = (float) (Integer) value;
            } else if (valuesClass.equals(Byte.class)) {
                value = (float) ((Byte) value).intValue();
            } else if (valuesClass.equals(Character.class)) {
                value = (float) (Character) value;
            } else if (valuesClass.equals(Short.class)) {
                value = (float) ((Short) value).intValue();
            } else if (valuesClass.equals(Long.class)) {
                value = (float) (Long) value;
            }
        }

        if (expectedType.equals(Long.TYPE)) {
            if (valuesClass.equals(Integer.class)) {
                value = (long) (Integer) value;
            } else if (valuesClass.equals(Byte.class)) {
                value = (long) ((Byte) value).intValue();
            } else if (valuesClass.equals(Character.class)) {
                value = (long) (Character) value;
            } else if (valuesClass.equals(Short.class)) {
                value = (long) ((Short) value).intValue();
            }
        }

        if (expectedType.equals(Short.TYPE)) {
            if (valuesClass.equals(Integer.class)) {
                value = (short) ((Integer) value).intValue();
            } else if (valuesClass.equals(Byte.class)) {
                value = (short) ((Byte) value).intValue();
            } else if (valuesClass.equals(Short.class)) {
                value = (short) ((Short) value).intValue();
            } else if (valuesClass.equals(Character.class)) {
                value = (short) ((Character) value).charValue();
            } else if (valuesClass.equals(Long.class)) {
                value = (short) ((Long) value).intValue();
            }
        }

        if (expectedType.equals(Byte.TYPE)) {
            if (valuesClass.equals(Integer.class)) {
                value = (byte) ((Integer) value).intValue();
            } else if (valuesClass.equals(Short.class)) {
                value = (byte) ((Short) value).intValue();
            } else if (valuesClass.equals(Byte.class)) {
                value = (byte) ((Byte) value).intValue();
            } else if (valuesClass.equals(Character.class)) {
                value = (byte) ((Character) value).charValue();
            } else if (valuesClass.equals(Long.class)) {
                value = (byte) ((Long) value).intValue();
            }
        }

        return value;
    }

    private static Method resolveMethodOnRuntimeClass(Method method, Class<?> runtimeClass) {
        if (method == null || runtimeClass == null) {
            return null;
        }
        if (method.getDeclaringClass().isAssignableFrom(runtimeClass)) {
            return method;
        }

        String methodName = method.getName();
        Class<?>[] expectedParams = method.getParameterTypes();
        String expectedReturnType = method.getReturnType().getName();

        for (Method candidate : runtimeClass.getMethods()) {
            if (!sameRawSignatureByName(candidate, methodName, expectedParams, expectedReturnType)) {
                continue;
            }
            return candidate;
        }
        for (Class<?> c = runtimeClass; c != null; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (!sameRawSignatureByName(candidate, methodName, expectedParams, expectedReturnType)) {
                    continue;
                }
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameRawSignatureByName(Method candidate, String methodName,
                                                  Class<?>[] expectedParams, String expectedReturnType) {
        if (!candidate.getName().equals(methodName)) {
            return false;
        }
        Class<?>[] candidateParams = candidate.getParameterTypes();
        if (candidateParams.length != expectedParams.length) {
            return false;
        }
        if (!candidate.getReturnType().getName().equals(expectedReturnType)) {
            return false;
        }
        for (int i = 0; i < candidateParams.length; i++) {
            if (!candidateParams[i].getName().equals(expectedParams[i].getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssignableByName(Class<?> actual, Class<?> expected) {
        if (actual == null || expected == null || expected.isPrimitive()) {
            return false;
        }
        return expected.isAssignableFrom(actual) || GenericClassUtils.isSubtypeByName(expected, actual);
    }

    @Override
    public GenericAccessibleObject<?> getAccessibleObject() {
        return null; //not defined for FM
    }

    @Override
    public boolean isAssignmentStatement() {
        return false;
    }

    @Override
    public boolean same(Statement s) {
        if (this == s) {
            return true;
        }
        if (s == null) {
            return false;
        }
        if (getClass() != s.getClass()) {
            return false;
        }

        FunctionalMockStatement fms = (FunctionalMockStatement) s;

        if (fms.parameters.size() != parameters.size()) {
            return false;
        }

        for (int i = 0; i < parameters.size(); i++) {
            if (!parameters.get(i).same(fms.parameters.get(i))) {
                return false;
            }
        }

        if (!retval.same(fms.retval)) {
            return false;
        }

        if (!targetClass.equals(fms.targetClass)) {
            return false;
        }

        if (useLenientDefaultAnswer != fms.useLenientDefaultAnswer) {
            return false;
        }

        if (!sameStubbedMethods(fms)) {
            return false;
        }

        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        FunctionalMockStatement that = (FunctionalMockStatement) o;

        if (!java.util.Objects.equals(parameters, that.parameters)) {
            return false;
        }
        if (!java.util.Objects.equals(retval, that.retval)) {
            return false;
        }
        if (!java.util.Objects.equals(targetClass, that.targetClass)) {
            return false;
        }
        if (useLenientDefaultAnswer != that.useLenientDefaultAnswer) {
            return false;
        }
        return sameStubbedMethods(that);
    }

    private boolean sameStubbedMethods(FunctionalMockStatement other) {
        if (stubs.size() != other.stubs.size()) {
            return false;
        }
        for (int i = 0; i < stubs.size(); i++) {
            if (!stubs.get(i).method.getID().equals(other.stubs.get(i).method.getID())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(parameters, retval, targetClass, useLenientDefaultAnswer);
        for (Stub stub : stubs) {
            result = 31 * result + java.util.Objects.hashCode(stub.method.getID());
        }
        return result;
    }

    @Override
    public String toString() {
        return "mock(" + retval.getType() + ")";
    }

    @Override
    public String getDescriptor() {
        return "()L" + PackageInfo.getNameWithSlash(retval.getVariableClass()) + ";";
    }

    @Override
    public String getDeclaringClassName() {
        return retval.getClassName();
    }

    @Override
    public String getMethodName() {
        return "mock";
    }
}
