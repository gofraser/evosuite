/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.statements;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.FalsePositiveException;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
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
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericClassUtils;
import org.mockito.MockSettings;
import org.mockito.Mockito;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.OngoingStubbing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final long serialVersionUID = -8177814473724093381L;

    private static final Logger logger = LoggerFactory.getLogger(FunctionalMockStatement.class);
    private static final Set<String> unmockableTargetClasses = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean functionalMockingGloballyDisabled = new AtomicBoolean(false);
    private static final AtomicInteger functionalMockingAttempts = new AtomicInteger(0);
    private static final AtomicInteger functionalMockingInitFailures = new AtomicInteger(0);

    /**
     * Maps a declared mock type to a more specific type that should be used instead.
     * Populated at runtime when a ClassCastException reveals that the SUT downcasts
     * a mock parameter (e.g., Graphics → Graphics2D).
     */
    private static final ConcurrentHashMap<String, String> mockTypeUpgrades = new ConcurrentHashMap<>();

    /**
     * Registers a mock type upgrade: future mocks of {@code declaredType} should
     * use {@code targetType} instead (because the SUT casts to it).
     */
    public static void registerMockTypeUpgrade(String declaredType, String targetType) {
        if (mockTypeUpgrades.putIfAbsent(declaredType, targetType) == null) {
            logger.warn("Registered mock type upgrade: {} → {}", declaredType, targetType);
        }
    }

    /**
     * Returns the upgraded mock type for the given declared type, or null if
     * no upgrade is registered.
     */
    public static String getMockTypeUpgrade(String declaredType) {
        return mockTypeUpgrades.get(declaredType);
    }

    /**
     * Detects when a ClassCastException was caused by a mock being the wrong
     * type, and registers an upgrade so future mocks use the cast target type.
     *
     * <p>For example, if {@code mock(Graphics.class)} is passed to SUT code
     * that casts to {@code Graphics2D}, this registers
     * {@code Graphics → Graphics2D} so subsequent mocks use Graphics2D.</p>
     *
     * @param e    the ClassCastException
     * @param s    the statement that was executing when the exception occurred
     * @param tc   the test case
     */
    public static void detectMockTypeUpgrade(ClassCastException e, Statement s, TestCase tc) {
        try {
            String targetType = parseCastTargetType(e);
            if (targetType == null) {
                return;
            }

            if (!(s instanceof EntityWithParametersStatement)) {
                return;
            }
            for (VariableReference ref :
                    ((EntityWithParametersStatement) s).getParameterReferences()) {
                Statement defStmt = tc.getStatement(ref.getStPosition());
                if (!(defStmt instanceof FunctionalMockStatement)) {
                    continue;
                }
                FunctionalMockStatement mock = (FunctionalMockStatement) defStmt;
                Class<?> mockClass = mock.getTargetClass();
                try {
                    Class<?> castTarget = Class.forName(targetType, false,
                            mockClass.getClassLoader());
                    if (mockClass.isAssignableFrom(castTarget)) {
                        registerMockTypeUpgrade(mockClass.getName(), targetType);
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
        } catch (Exception ex) {
            logger.debug("Failed to detect mock type upgrade", ex);
        }
    }

    /**
     * Parses the cast target type from a ClassCastException message.
     * Returns null if the exception doesn't involve a Mockito mock.
     */
    private static String parseCastTargetType(ClassCastException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.contains("MockitoMock")) {
            return null;
        }
        // JDK 11+: "... cannot be cast to class java.awt.Graphics2D (module info...)"
        int idx = msg.indexOf("cannot be cast to class ");
        if (idx >= 0) {
            String after = msg.substring(idx + "cannot be cast to class ".length());
            int end = indexOfAny(after, ' ', '(');
            return end > 0 ? after.substring(0, end) : after.trim();
        }
        // Older JDKs: "... cannot be cast to java.awt.Graphics2D"
        idx = msg.indexOf("cannot be cast to ");
        if (idx >= 0) {
            return msg.substring(idx + "cannot be cast to ".length()).trim();
        }
        return null;
    }

    private static int indexOfAny(String s, char... chars) {
        int min = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }

    /**
     * This list needs to be kept sorted.
     */
    protected final List<MethodDescriptor> mockedMethods;

    /**
     * key -> MethodDescriptor id,
     * Value -> min,max  inclusive of indices on super.parameters.
     */
    protected final Map<String, int[]> methodParameters;

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

    protected transient Method mockCreator;

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
        mockedMethods = new ArrayList<>();
        methodParameters = new LinkedHashMap<>();
        checkTarget();
        assert parameters.isEmpty();
        //setUpMockCreator();
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
        mockedMethods = new ArrayList<>();
        methodParameters = new LinkedHashMap<>();
        checkTarget();
        assert parameters.isEmpty();
        //setUpMockCreator();
    }

    private void setUpMockCreator() {
        ClassLoader loader = targetClass.getRawClass().getClassLoader();
        try {
            Class<?> mockito = loader.loadClass(Mockito.class.getName());
            mockCreator = mockito.getDeclaredMethod("mock",
                    loader.loadClass(Class.class.getName()), loader.loadClass(MockSettings.class.getName()));

        } catch (Exception e) {
            logger.error("Failed to setup mock creator: " + e.getMessage());
        }
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {

        targetClass.changeClassLoader(loader);
        for (MethodDescriptor descriptor : mockedMethods) {
            if (descriptor != null) {
                descriptor.changeClassLoader(loader);
            }
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

        if (!InstrumentedClass.class.isAssignableFrom(rawClass)
                && Modifier.isFinal(rawClass.getModifiers())) {
            /*
                if a class has not been instrumented (eg because belonging to javax.*),
                then if it is final we cannot mock it :(
                recall that instrumentation does remove the final modifiers
             */
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

        // Reject ClassLoader types.  Mockito's SubclassByteBuddyMockMaker
        // generates a subclass of the mocked type, so a ClassLoader mock IS a
        // ClassLoader.  On JDK 25+, instantiating a dynamically-generated
        // ClassLoader subclass triggers a JVM guarantee failure
        // (moduleEntry.cpp: "unnamed module is null") because the unnamed module
        // for the new ClassLoader is not properly initialized.  Even on older
        // JDKs, mocking ClassLoaders is semantically dangerous — the mock may be
        // used as a parent loader by other code, corrupting the class loading
        // hierarchy.
        if (ClassLoader.class.isAssignableFrom(rawClass)) {
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

        // ad-hoc list of classes we should not really mock
        List<Class<?>> avoid = Arrays.asList(
        // add here if needed
        );

        return !avoid.contains(rawClass);
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


    /**
     * Add a method stubbing to this mock statement. Used by the test parser to
     * reconstruct FunctionalMockStatements from parsed source code.
     *
     * @param md           the method descriptor for the stubbed method
     * @param returnValues the return values for successive calls to the method
     */
    public void addMethodStubbing(MethodDescriptor md, List<VariableReference> returnValues) {
        Inputs.checkNull(md, returnValues);
        populatedFromParser = true;
        mockedMethods.add(md);
        int startIndex = parameters.size();
        parameters.addAll(returnValues);
        int endIndex = parameters.size() - 1;
        if (returnValues.isEmpty()) {
            methodParameters.put(md.getID(), null);
        } else {
            methodParameters.put(md.getID(), new int[]{startIndex, endIndex});
        }
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
     * @return the list of mocked methods
     */
    public List<MethodDescriptor> getMockedMethods() {
        return mockedMethods;
    }

    /**
     * getParameters for a given method id.
     *
     * @param id the method id
     * @return the list of variable references
     * @throws IllegalArgumentException if id is null
     */
    public List<VariableReference> getParameters(String id) throws IllegalArgumentException {
        Inputs.checkNull(id);

        int[] minMax = methodParameters.get(id);
        if (minMax == null) {
            return null;
        }

        List<VariableReference> list = new ArrayList<>();
        for (int i = minMax[0]; i <= minMax[1]; i++) {
            list.add(parameters.get(i));
        }
        return list;
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

                Note: mockedMethods may be non-empty when reconstructed from parsed source
                code (via addMethodStubbing), so we also allow that case.
             */

            assert mockedMethods.isEmpty() || RuntimeSettings.isRunningASystemTest
                    || populatedFromParser;

            return false;
        }

        List<MethodDescriptor> executed = listener.getCopyOfMethodDescriptors();
        if (executed.size() != mockedMethods.size()) {
            return true;
        }

        for (int i = 0; i < executed.size(); i++) {
            MethodDescriptor previous = mockedMethods.get(i);
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
     * updateMockedMethods.
     *
     * @return a ordered, non-null list of types of missing new inputs that will need to be provided
     * @throws ConstructionFailedException if construction fails
     */
    public List<Type> updateMockedMethods() throws ConstructionFailedException {

        logger.debug("Executing updateMockedMethods. Parameter size: {}", parameters.size());

        List<Type> list = new ArrayList<>();

        assert !super.parameters.contains(null);
        assert mockedMethods.size() == methodParameters.size();

        List<VariableReference> copy = new ArrayList<>(super.parameters);
        assert copy.size() == super.parameters.size();

        super.parameters.clear();
        mockedMethods.clear(); //important to remove all the no longer used calls

        Map<String, int[]> mpCopy = new LinkedHashMap<>();

        List<MethodDescriptor> executed = listener.getCopyOfMethodDescriptors();

        int mdIndex = 0;

        for (MethodDescriptor md : executed) {
            mockedMethods.add(md);

            if (!md.shouldBeMocked() || md.getCounter() == 0) {
                //void method or not called, so no parameter needed for it
                mpCopy.put(md.getID(), null);
                continue;
            }

            int added = 0;

            logger.debug("Method called on mock object: {}", md.getMethod());

            // infer parameter mapping of current vars from previous execution, if any
            int[] minMax = methodParameters.get(md.getID());
            int existingParameters; //total number of existing parameters
            if (minMax == null) {
                //before it was not called
                minMax = new int[]{-1, -1};
                existingParameters = 0;
            } else {
                assert minMax[1] >= minMax[0] && minMax[0] >= 0;
                assert minMax[1] < copy.size() : "Max=" + minMax[1] + " but n=" + copy.size();
                existingParameters = 1 + (minMax[1] - minMax[0]);
            }

            assert existingParameters <= Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT;

            //check if less calls
            if (existingParameters > md.getCounter()) {
                //now the method has been called less times,
                //so remove the last calls, ie decrease counter
                minMax[1] -= (existingParameters - md.getCounter());
            }

            if (existingParameters > 0) {
                for (int i = minMax[0]; i <= minMax[1]; i++) {
                    //align super class data structure
                    super.parameters.add(copy.get(i));
                    added++;
                }
            }

            // check if rather more calls
            if (existingParameters < md.getCounter()) {
                for (int i = existingParameters; i < md.getCounter()
                        && i < Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT; i++) {
                    // Create a copy as the typemap is stored in the class during generic instantiation
                    // but we might want to have a different type for each call of the same method invocation
                    GenericClass<?> calleeClass = GenericClassFactory.get(retval.getGenericClass());
                    Type returnType = md.getGenericMethodFor(calleeClass).getGeneratedType();
                    assert !returnType.equals(Void.TYPE);
                    logger.debug("Return type: {} for retval {}", returnType,
                            retval.getGenericClass());
                    list.add(returnType);

                    super.parameters.add(null); //important place holder for following updates
                    added++;
                }
            }


            minMax[0] = mdIndex;
            minMax[1] = (mdIndex + added - 1); //max is inclusive
            assert minMax[1] >= minMax[0] && minMax[0] >= 0; //max >= min
            assert super.parameters.size() == minMax[1] + 1;

            mpCopy.put(md.getID(), minMax);
            mdIndex += added;
        }

        methodParameters.clear();
        methodParameters.putAll(mpCopy);
        for (MethodDescriptor md : mockedMethods) {
            if (!methodParameters.containsKey(md.getID())) {
                methodParameters.put(md.getID(), null);
            }
        }

        return list;
    }

    /**
     * addMissingInputs.
     *
     * @param inputs the list of inputs
     * @throws IllegalArgumentException if inputs is null or mismatch
     */
    public void addMissingInputs(List<VariableReference> inputs) throws IllegalArgumentException {
        Inputs.checkNull(inputs);

        logger.debug("Adding {} missing values", inputs.size());

        if (!inputs.isEmpty()) {

            if (inputs.size() > parameters.size()) {
                //first quick check
                throw new IllegalArgumentException("Not enough parameter place holders");
            }

            int index = 0;
            for (VariableReference ref : inputs) {
                while (parameters.get(index) != null) {
                    index++;
                    if (index >= parameters.size()) {
                        throw new IllegalArgumentException("Not enough parameter place holders");
                    }
                }
                logger.debug("Current input: {} for expected type {}", ref,
                        getExpectedParameterType(index));

                assert ref.isAssignableTo(getExpectedParameterType(index));

                parameters.set(index, ref);
            }
        } //else, nothing to add

        //check if all "holes" have been filled
        for (VariableReference ref : parameters) {
            if (ref == null) {
                throw new IllegalArgumentException("Functional mock not fully set with all"
                        + " needed missing inputs");
            }
        }
    }

    /**
     * fillWithNullRefs.
     */
    public void fillWithNullRefs() {
        for (int i = 0; i < parameters.size(); i++) {
            VariableReference ref = parameters.get(i);
            if (ref == null) {
                Class<?> expected = getExpectedParameterType(i);
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
                parameters.set(i, new ConstantValue(tc, GenericClassFactory.get(expected), value));
            }
        }
    }


    /**
     * getExpectedParameterType.
     *
     * @param i the index
     * @return the expected type
     */
    private Class<?> getExpectedParameterType(int i) {

        for (MethodDescriptor md : mockedMethods) {
            int[] bounds = methodParameters.get(md.getID());
            if (bounds != null && i >= bounds[0] && i <= bounds[1]) {
                return md.getMethod().getReturnType();
            }
        }
        LoggingUtils.getEvoLogger().error("Error for finding expected parameter type: {}, {}",
                i, mockedMethods);
        // TODO: This should not happen and if it is, some bugs are triggered. '
        // Since 'return Object.class' can improve code coverage instead of giving it up,
        // I do not forcefully trigger Error.
        // This should be fixed (find the root causes of bugs and ..)
        return Object.class;
    }

    //------------ override methods ---------------


    @Override
    public void addAssertion(Assertion assertion) {
        //never add an assertion to a functional mock
    }


    @Override
    public Statement copy(TestCase newTestCase, int offset) {


        FunctionalMockStatement copy = new FunctionalMockStatement(
                newTestCase, retval.getType(), targetClass);

        for (VariableReference r : this.parameters) {
            copy.parameters.add(r.copy(newTestCase, offset));
        }

        copy.listener = this.listener; //no need to clone, as only read, and created new instance at each new execution
        copy.populatedFromParser = this.populatedFromParser;
        copy.useLenientDefaultAnswer = this.useLenientDefaultAnswer;
        copy.isDmonPromotion = this.isDmonPromotion;

        for (MethodDescriptor md : this.mockedMethods) {
            copy.mockedMethods.add(md.getCopy());
        }

        for (Map.Entry<String, int[]> entry : methodParameters.entrySet()) {
            int[] array = entry.getValue();
            int[] copiedArray = array == null ? null : new int[]{array[0], array[1]};
            copy.methodParameters.put(entry.getKey(), copiedArray);
        }

        copyProvenanceFrom(copy, this);

        return copy;
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
        if (useLenientDefaultAnswer) {
            settings = settings.defaultAnswer(FunctionalMockStatement::lenientDefaultAnswer);
        }
        return settings;
    }

    /**
     * Lenient default answer that returns sensible non-null values for all
     * return types.  Mirrors the behavior of the ephemeral mocks created by
     * DMoN during the search: empty strings, zero for numerics, empty
     * collections, and recursively mocked objects for other types.
     */
    private static final int MAX_LENIENT_MOCK_DEPTH = 3;
    private static final ThreadLocal<Integer> lenientMockDepth = ThreadLocal.withInitial(() -> 0);

    @SuppressWarnings("unchecked")
    static Object lenientDefaultAnswer(InvocationOnMock invocation) {
        Class<?> returnType = invocation.getMethod().getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType == String.class || returnType == CharSequence.class) {
            return "";
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return false;
        }
        if (returnType == byte.class || returnType == Byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class || returnType == Short.class) {
            return (short) 0;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return 0;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        if (returnType == float.class || returnType == Float.class) {
            return 0.0f;
        }
        if (returnType == double.class || returnType == Double.class) {
            return 0.0;
        }
        if (returnType == char.class || returnType == Character.class) {
            return '\0';
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        // Check collection/map types by verifying the return type IS a collection,
        // not just that a collection could be assigned to it (which would match Object,
        // Serializable, etc. and cause ClassCastExceptions downstream).
        if (List.class.isAssignableFrom(returnType)
                || returnType == Collection.class
                || returnType == Iterable.class) {
            return new ArrayList<>();
        }
        if (Set.class.isAssignableFrom(returnType)) {
            return new HashSet<>();
        }
        if (Map.class.isAssignableFrom(returnType)) {
            return new HashMap<>();
        }
        if (returnType.isArray()) {
            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
        }
        // For other object types, try to create a sub-mock with the same lenient answer.
        // Skip Object and other overly-broad supertypes — mocking them produces opaque
        // proxy objects that cause ClassCastExceptions when the SUT casts to a concrete type.
        // Limit recursion depth to avoid stack overflow from fluent/builder patterns
        // where a method returns its own type (e.g., Builder.withX() → Builder).
        int depth = lenientMockDepth.get();
        if (depth < MAX_LENIENT_MOCK_DEPTH
                && returnType != Object.class
                && returnType != java.io.Serializable.class
                && returnType != Comparable.class
                && returnType != Cloneable.class
                && !Modifier.isFinal(returnType.getModifiers())
                && !returnType.isPrimitive()) {
            try {
                lenientMockDepth.set(depth + 1);
                return Mockito.mock(returnType,
                        (org.mockito.stubbing.Answer<?>) FunctionalMockStatement::lenientDefaultAnswer);
            } catch (Exception ignored) {
                // Cannot mock (e.g., final class, sealed) — fall through to null.
            } finally {
                lenientMockDepth.set(depth);
            }
        }
        return null;
    }

    /**
     * Log Mockito mock-maker configuration once, at the first mock creation attempt.
     * Uses reflection on the shaded MockUtil to report which mock makers are registered
     * and whether the SubclassByteBuddyMockMaker SPI override was picked up.
     */
    private static volatile boolean mockMakerDiagnosticsLogged;

    private static void logMockMakerDiagnosticsOnce() {
        if (mockMakerDiagnosticsLogged || !logger.isDebugEnabled()) {
            return;
        }
        mockMakerDiagnosticsLogged = true;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> makers = (java.util.Map<?, ?>)
                    getDeclaredFieldValue(Mockito.class.getPackage().getName()
                            + ".internal.util.MockUtil", "mockMakers");
            if (makers != null) {
                logger.debug("Mockito mock makers registered: {}, keys: {}",
                        makers.size(), makers.keySet());
            }
        } catch (Throwable ignored) {
            // Reflection may fail on different Mockito versions; not critical
        }
    }

    private static Object getDeclaredFieldValue(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static boolean isCodeUnderTestInitializationFailure(Throwable throwable) {
        return hasCause(throwable, NoClassDefFoundError.class)
                || hasCause(throwable, ExceptionInInitializerError.class)
                || hasCause(throwable, ClassNotFoundException.class)
                || hasCause(throwable, LinkageError.class);
    }

    private static boolean shouldDisableFunctionalMockingGlobally(int attempts, int failures) {
        // Only the GLOBAL mode triggers the global disable.  CLASS mode only
        // blacklists individual problematic classes — it must NOT disable mocking
        // globally, because that prevents mocking of unrelated classes that work
        // fine (e.g., MainFrame mock for a JDialog-extending CUT).
        if (Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE != Properties.FunctionalMockingFailoverMode.GLOBAL) {
            return false;
        }
        if (attempts <= 0 || failures < Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT) {
            return false;
        }
        double ratio = ((double) failures) / ((double) attempts);
        return ratio >= Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO;
    }

    private static boolean isClassLevelFailoverEnabled() {
        return Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE == Properties.FunctionalMockingFailoverMode.CLASS;
    }

    private static boolean isClassBlacklisted(String className) {
        return unmockableTargetClasses.contains(className);
    }

    private static void handleRecoverableMockFailure(GenericClass<?> targetClass, Throwable failure)
            throws CodeUnderTestException {
        handleRecoverableMockFailure(targetClass.getRawClass().getName(), failure);
    }

    private static void handleRecoverableMockFailure(String className, Throwable failure)
            throws CodeUnderTestException {
        int failures = functionalMockingInitFailures.incrementAndGet();
        int attempts = functionalMockingAttempts.get();

        if (isClassLevelFailoverEnabled() && unmockableTargetClasses.add(className)) {
            AtMostOnceLogger.warn(logger,
                    "Disabling functional mocking for class " + className
                            + " due to initialization failure: "
                            + failure.getClass().getSimpleName());
        }

        if (shouldDisableFunctionalMockingGlobally(attempts, failures)
                && functionalMockingGloballyDisabled.compareAndSet(false, true)) {
            Properties.P_FUNCTIONAL_MOCKING = 0.0;
            Properties.MOCK_IF_NO_GENERATOR = false;
            AtMostOnceLogger.warn(logger,
                    "Disabling functional mocking globally after " + failures + "/" + attempts
                            + " initialization failures");
        }

        throw new CodeUnderTestException(failure);
    }

    static void resetMockingFailoverStateForTests() {
        unmockableTargetClasses.clear();
        functionalMockingGloballyDisabled.set(false);
        functionalMockingAttempts.set(0);
        functionalMockingInitFailures.set(0);
    }

    static boolean isFunctionalMockingGloballyDisabledForTests() {
        return functionalMockingGloballyDisabled.get();
    }

    static boolean isClassBlacklistedForTests(String className) {
        return isClassBlacklisted(className);
    }

    static boolean isCodeUnderTestInitializationFailureForTests(Throwable throwable) {
        return isCodeUnderTestInitializationFailure(throwable);
    }

    static void registerMockAttemptForTests() {
        functionalMockingAttempts.incrementAndGet();
    }

    static void registerRecoverableMockFailureForTests(String className, Throwable failure)
            throws CodeUnderTestException {
        handleRecoverableMockFailure(className, failure);
    }

    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws InvocationTargetException, IllegalArgumentException,
            IllegalAccessException, InstantiationException {

        Throwable exceptionThrown = null;

        try {
            return super.exceptionHandler(new MockStatementExecuter(scope));
        } catch (InvocationTargetException e) {
            exceptionThrown = e.getCause();
        }
        return exceptionThrown;
    }

    private class MockStatementExecuter extends Executer {
        private final Scope scope;

        public MockStatementExecuter(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void execute() throws InvocationTargetException,
                IllegalArgumentException, IllegalAccessException,
                InstantiationException, CodeUnderTestException {

            String className = targetClass.getRawClass().getName();
            if (functionalMockingGloballyDisabled.get()) {
                throw new CodeUnderTestException(new IllegalStateException(
                        "Functional mocking disabled globally due to repeated initialization failures"));
            }
            if (isClassBlacklisted(className)) {
                throw new CodeUnderTestException(new IllegalStateException(
                        "Functional mocking disabled for class due to prior initialization failure: " + className));
            }

            // First create the listener
            listener = createInvocationListener();

            //then create the mock
            Object ret = null;
            try {
                functionalMockingAttempts.incrementAndGet();
                logMockMakerDiagnosticsOnce();

                MockSettings settings = createMockSettings();
                // Check if a more specific type should be used (e.g., Graphics2D
                // instead of Graphics) because the SUT downcasts the parameter.
                String upgrade = getMockTypeUpgrade(targetClass.getRawClass().getName());
                if (upgrade != null) {
                    try {
                        Class<?> upgraded = Class.forName(upgrade, false,
                                targetClass.getRawClass().getClassLoader());
                        if (targetClass.getRawClass().isAssignableFrom(upgraded)
                                && canBeFunctionalMocked(upgraded)) {
                            targetClass = GenericClassFactory.get(upgraded);
                            retval.setType(upgraded);
                        }
                    } catch (ClassNotFoundException e) {
                        logger.debug("Could not load upgraded mock type {}: {}", upgrade, e.getMessage());
                    }
                }
                logger.debug("Mockito: create mock for {}", targetClass);
                ret = mock(targetClass.getRawClass(), settings);

                // When the lenient default answer is active, skip explicit when/thenReturn
                // stubs entirely — the default answer already handles all method calls with
                // sensible non-null values.  Explicit stubs would override the defaults with
                // null (from fillWithNullRefs/satisfyParameters), defeating the purpose.
                if (!useLenientDefaultAnswer) {

                //execute all "when" statements
                int index = 0;

                logger.debug("Mockito: going to mock {} different methods", mockedMethods.size());
                for (MethodDescriptor md : mockedMethods) {

                    if (!md.shouldBeMocked()) {
                        //no need to mock a method that returns void
                        logger.debug("Mockito: method {} cannot be mocked", md.getMethodName());
                        continue;
                    }

                    Method method = md.getMethod(); //target method, eg foo.aMethod(...)

                    // this is needed if method is protected: it couldn't be called here,
                    // although fine in the generated JUnit tests
                    method.setAccessible(true);

                    //target inputs
                    Object[] targetInputs = new Object[md.getNumberOfInputParameters()];
                    for (int i = 0; i < targetInputs.length; i++) {
                        logger.debug("Mockito: executing matcher {}/{}", (1 + i),
                                targetInputs.length);
                        targetInputs[i] = md.executeMatcher(i);
                    }

                    logger.debug("Mockito: going to invoke method {} with {} matchers",
                            method.getName(), targetInputs.length);

                    if (!method.getDeclaringClass().isAssignableFrom(ret.getClass())) {

                        String msg = "Mismatch between callee's class " + ret.getClass()
                                + " and method's class " + method.getDeclaringClass();
                        msg += "\nTarget class classloader "
                                + targetClass.getRawClass().getClassLoader()
                                + " vs method's classloader "
                                + method.getDeclaringClass().getClassLoader();
                        throw new EvosuiteError(msg);
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
                        logger.error("Invocation of mocked {}.{}() threw an exception. "
                                + "This means the method was not mocked",
                                targetClass.getClassName(), method.getName());
                        throw e;
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
                        int size = Math.min(md.getCounter(),
                                Properties.FUNCTIONAL_MOCKING_INPUT_LIMIT);

                        thenReturnInputs = new Object[size];

                        for (int i = 0; i < thenReturnInputs.length; i++) {

                            int k = i + index; //the position in flat parameter list
                            if (k >= parameters.size()) {
                                throw new CodeUnderTestException(new FalsePositiveException(
                                        "EvoSuite ERROR: index " + k + " out of "
                                                + parameters.size()));
                            }

                            VariableReference parameterVar = parameters.get(i + index);
                            thenReturnInputs[i] = parameterVar.getObject(scope);

                            CodeUnderTestException codeUnderTestException = null;

                            if (thenReturnInputs[i] == null && method.getReturnType().isPrimitive()) {
                                codeUnderTestException = new CodeUnderTestException(
                                        new NullPointerException());

                            } else if (thenReturnInputs[i] != null
                                    && !TypeUtils.isAssignable(thenReturnInputs[i].getClass(),
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

                    index += thenReturnInputs == null ? 0 : thenReturnInputs.length;
                }

                } // end if (!useLenientDefaultAnswer)
            } catch (CodeUnderTestException e) {
                throw e;
            } catch (java.lang.NoClassDefFoundError e) {
                AtMostOnceLogger.error(logger, "Cannot use Mockito on " + targetClass
                        + " due to failed class initialization: " + e.getMessage());
                handleRecoverableMockFailure(targetClass, e);
            } catch (IllegalStateException e) {
                AtMostOnceLogger.error(logger, "Cannot use Mockito on " + targetClass
                        + " due to ISE: " + e.getMessage());
                if (isCodeUnderTestInitializationFailure(e)) {
                    handleRecoverableMockFailure(targetClass, e);
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
                if (isCodeUnderTestInitializationFailure(t)) {
                    handleRecoverableMockFailure(targetClass, t);
                }
                throw new EvosuiteError(t);
            }

            //finally, activate the listener
            listener.activate();

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

        if (fms.mockedMethods.size() != mockedMethods.size()) {
            return false;
        }

        for (int i = 0; i < mockedMethods.size(); i++) {
            if (!mockedMethods.get(i).getID().equals(fms.mockedMethods.get(i).getID())) {
                return false;
            }
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
        if (mockedMethods.size() != that.mockedMethods.size()) {
            return false;
        }
        for (int i = 0; i < mockedMethods.size(); i++) {
            if (!java.util.Objects.equals(mockedMethods.get(i).getID(), that.mockedMethods.get(i).getID())) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(parameters, retval, targetClass);
        for (MethodDescriptor md : mockedMethods) {
            result = 31 * result + java.util.Objects.hashCode(md.getID());
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
