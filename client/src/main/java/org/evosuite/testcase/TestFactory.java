/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase;

import com.googlecode.gentyref.CaptureType;
import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.ClassUtils;
import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.VariableResolutionConfig;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.statements.reflection.PrivateMethodStatement;
import org.evosuite.testcase.statements.reflection.ReflectionFactory;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;

/*
 * A note about terminology: this class currently uses the term "object" or
 * "Object" with ambiguous meanings. Depending on the context, it may refer to
 * one of the following:
 *  - objects in the OOP sense as instances of classes (i.e., complex data
 *    types that extend java.lang.Object). For example: method createObject
 *    creates an object for a given class
 *  - object referring to the class java.lang.Object, e.g.,
 *    attemptInstantiationOfObjectClass tries to find objects of some data
 *    type T that can be assigned to Object and safely downcast to T
 *  - object referring to the object-representation of a reflected field,
 *    method or constructor of a class, i.e., GenericAccessibleObject
 */

/**
 * Factory class for creating and modifying test cases.
 *
 * @author Gordon Fraser
 */
public class TestFactory {

    private static final Logger logger = LoggerFactory.getLogger(TestFactory.class);

    /**
     * Singleton instance.
     */
    private static TestFactory instance = null;

    private ReflectionFactory reflectionFactory;

    private final VariableResolver variableResolver;

    private final StatementFactory statementFactory;

    private final TestMutator testMutator;

    ReflectionFactory getReflectionFactory() {
        if (reflectionFactory == null) {
            final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();
            reflectionFactory = new ReflectionFactory(targetClass);
        }
        return reflectionFactory;
    }

    private TestFactory() {
        this.variableResolver = new VariableResolver(this);
        this.statementFactory = new StatementFactory(this);
        this.testMutator = new TestMutator(this);
        reset();
    }

    /**
     * We keep track of calls already attempted to avoid infinite recursion.
     */
    public void reset() {
        reflectionFactory = null;
    }

    /**
     * Returns the singleton instance of the TestFactory.
     *
     * @return the singleton instance
     */
    public static TestFactory getInstance() {
        if (instance == null) {
            instance = new TestFactory();
        }
        return instance;
    }

    /**
     * Adds a call of the field or method represented by {@code call} to the
     * test case {@code test} at the given {@code position} with {@code callee}
     * as the callee of {@code call}.
     * Note that constructor calls are <em>not</em> supported
     * Returns {@code true} if the operation was successful, {@code false} otherwise.
     *
     * @param test     the test case the call should be added to
     * @param callee   reference to the owning object of {@code call}
     * @param call     the {@code GenericAccessibleObject}
     * @param position the position within {@code test} at which to add the call
     * @return {@code true} if successful, {@code false} otherwise
     */
    private boolean addCallFor(TestCase test, VariableReference callee,
                               GenericAccessibleObject<?> call, int position) {
        return addCallFor(test, callee, call, position, new GenerationContext());
    }

    boolean addCallFor(TestCase test, VariableReference callee,
                               GenericAccessibleObject<?> call, int position,
                               GenerationContext context) {

        logger.trace("addCallFor {}", callee.getName());

        StatementInserter inserter = new StatementInserter(test, position);

        try {
            if (call.isMethod()) {
                GenericMethod method = (GenericMethod) call;
                if (call.isStatic()) {
                    // Static methods can be modifiers of the SUT if the SUT depends on static fields
                    addMethod(test, method, position, context);
                } else {
                    if (!MethodStatement.isCompatibleCalleeType(method, callee.getType())) {
                        throw new ConstructionFailedException("Cannot apply method to this callee");
                    }
                    addMethodFor(test,
                            callee,
                            (GenericMethod) call.copyWithNewOwner(callee.getGenericClass()),
                            position, context);
                }
            } else if (call.isField()) {
                // A modifier for the SUT could also be a static field in another class
                if (call.isStatic()) {
                    addFieldAssignment(test, (GenericField) call, position, context);
                } else {
                    addFieldFor(test,
                            callee,
                            (GenericField) call.copyWithNewOwner(callee.getGenericClass()),
                            position, context);
                }
            }
            return true;
        } catch (ConstructionFailedException e) {
            logger.debug("Inserting call {} has failed: {} Rolling back statements", call, e);
            inserter.rollback();
            if (logger.isDebugEnabled()) {
                logger.debug("Test after removal: {}", test.toCode());
            }
            return false;
        }
    }


    /**
     * Adds a functional mock to the test case.
     *
     * @param test           the test case
     * @param type           the type to mock
     * @param position       the position to insert the mock
     * @param context        the generation context
     * @return reference to the created mock
     * @throws ConstructionFailedException if construction fails
     * @throws IllegalArgumentException    if arguments are invalid
     */
    public VariableReference addFunctionalMock(TestCase test, Type type,
                                               int position, GenerationContext context)
            throws ConstructionFailedException, IllegalArgumentException {
        return statementFactory.addFunctionalMock(test, type, position, context);
    }

    /**
     * Adds a functional mock for an abstract class to the test case.
     *
     * @param test           the test case
     * @param type           the type to mock
     * @param position       the position to insert the mock
     * @param context        the generation context
     * @return reference to the created mock
     * @throws ConstructionFailedException if construction fails
     * @throws IllegalArgumentException    if arguments are invalid
     */
    public VariableReference addFunctionalMockForAbstractClass(TestCase test,
                                                               Type type,
                                                               int position,
                                                               GenerationContext context)
            throws ConstructionFailedException, IllegalArgumentException {
        return statementFactory.addFunctionalMockForAbstractClass(test, type, position, context);
    }

    /**
     * Inserts a call to the given {@code constructor} into the {@code test} case at the specified
     * {@code position}.
     *
     * <p>Callers of this method have to supply the current recursion depth. This
     * allows for better management of test generation resources. If this method is called from
     * another method that already has a recursion depth as formal parameter, passing that
     * recursion depth + 1 is appropriate. Otherwise, 0 should be used.
     *
     * <p>Returns a reference to the return value of the constructor call. If the
     * {@link Properties#MAX_RECURSION maximum recursion depth} has been reached a
     * {@code ConstructionFailedException} is thrown.
     *
     * @param test           the test case in which to insert
     * @param constructor    the constructor for which to add the call
     * @param position       the position at which to insert
     * @param recursionDepth the current recursion depth
     * @return a reference to the result of the constructor call
     * @throws ConstructionFailedException if the maximum recursion depth has been reached
     */
    public VariableReference addConstructor(TestCase test,
                                            GenericConstructor constructor, int position, int recursionDepth)
            throws ConstructionFailedException {
        return addConstructor(test, constructor, position, GenerationContext.fromDepth(recursionDepth));
    }

    public VariableReference addConstructor(TestCase test,
                                            GenericConstructor constructor, int position,
                                            GenerationContext context)
            throws ConstructionFailedException {
        return addConstructor(test, constructor, null, position, context);
    }

    /**
     * Inserts a call to the given {@code constructor} into the {@code test} case at the specified
     * {@code position}.
     *
     * <p>Callers of this method have to supply the current generation context. This
     * allows for better management of test generation resources.
     *
     * <p>Returns a reference to the return value of the constructor call. If the
     * {@link Properties#MAX_RECURSION maximum recursion depth} has been reached a
     * {@code ConstructionFailedException} is thrown.
     *
     * @param test           the test case in which to insert
     * @param constructor    the constructor for which to add the call
     * @param exactType the exact type.
     * @param position       the position at which to insert
     * @param context        the current generation context
     * @return a reference to the result of the constructor call
     * @throws ConstructionFailedException if the maximum recursion depth has been reached
     */
    public VariableReference addConstructor(TestCase test,
                                            GenericConstructor constructor, Type exactType, int position,
                                            GenerationContext context)
            throws ConstructionFailedException {
        return statementFactory.addConstructor(test, constructor, exactType, position, context);
    }


    /**
     * Adds the given {@code field} to the {@code test} case at the given {@code position}.
     *
     * <p>Callers of this method have to supply the current recursion depth. This
     * allows for better management of test generation resources. If this method is called from
     * another method that already has a recursion depth as formal parameter, passing that
     * recursion depth + 1 is appropriate. Otherwise, 0 should be used.
     *
     * <p>Returns a reference to the inserted field. If the {@link Properties#MAX_RECURSION maximum
     * recursion depth} has been reached a {@code ConstructionFailedException} is thrown.
     *
     * @param test           the test case to which to add
     * @param field          the field to add
     * @param position       the position at which to add the field
     * @param recursionDepth the current recursion depth
     * @return a reference to the inserted field
     * @throws ConstructionFailedException if the maximum recursion depth has been reached
     */
    public VariableReference addField(TestCase test, GenericField field, int position,
                                      int recursionDepth) throws ConstructionFailedException {
        return addField(test, field, position, GenerationContext.fromDepth(recursionDepth));
    }

    public VariableReference addField(TestCase test, GenericField field, int position,
                                      GenerationContext context) throws ConstructionFailedException {
        return statementFactory.addField(test, field, position, context);
    }

    /**
     * Add field assignment at given position if max recursion depth has not been reached.
     *
     * @param test           the test case.
     * @param field          the field.
     * @param position       the position in the test case.
     * @param recursionDepth the current recursion depth.
     * @return the reference to the result of the assignment
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addFieldAssignment(TestCase test, GenericField field,
                                                int position, int recursionDepth) throws ConstructionFailedException {
        return addFieldAssignment(test, field, position, GenerationContext.fromDepth(recursionDepth));
    }

    public VariableReference addFieldAssignment(TestCase test, GenericField field,
                                                int position, GenerationContext context)
            throws ConstructionFailedException {
        return statementFactory.addFieldAssignment(test, field, position, context);
    }

    /**
     * Add reference to a field of variable "callee".
     *
     * @param test     the test case.
     * @param callee   the callee object.
     * @param field    the field.
     * @param position the position in the test case.
     * @return the reference to the field
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addFieldFor(TestCase test, VariableReference callee,
                                         GenericField field, int position) throws ConstructionFailedException {
        return addFieldFor(test, callee, field, position, new GenerationContext());
    }

    public VariableReference addFieldFor(TestCase test, VariableReference callee,
                                         GenericField field, int position, GenerationContext context)
            throws ConstructionFailedException {
        return statementFactory.addFieldFor(test, callee, field, position, context);
    }

    static Type normalizeTypeVariablesToWildcardsIfNeeded(Type type) {
        GenericClass<?> parameterClass = GenericClassFactory.get(type);
        Type normalizedType = type;
        if (parameterClass.hasTypeVariables()) {
            normalizedType = parameterClass.getWithWildcardTypes().getType();
        }
        return normalizeClassLiteralTypeArgumentByErasure(normalizedType);
    }

    /**
     * Class literals are reified to raw classes (eg, LinkedList.class has type Class&lt;LinkedList&gt;).
     * If a reflected field expects Class&lt;SomeGenericType&gt;, normalize it to Class&lt;SomeRawType&gt; so that
     * generated class literals can be assigned without triggering false generic mismatches.
     */
    static Type normalizeClassLiteralTypeArgumentByErasure(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return type;
        }

        ParameterizedType parameterizedType = (ParameterizedType) type;
        if (!(parameterizedType.getRawType() instanceof Class)
                || !Class.class.equals(parameterizedType.getRawType())) {
            return type;
        }

        Type[] args = parameterizedType.getActualTypeArguments();
        if (args.length != 1) {
            return type;
        }

        // Only normalize the class-literal corner case where a reflected signature
        // expects Class<SomeParameterizedType> but class literals are reified as
        // Class<SomeRawType> (e.g. LinkedList.class -> Class<LinkedList>).
        // Do not erase wildcard/type-variable based Class bounds such as
        // Class<? extends Session>, otherwise they degrade to Class<Object>.
        if (!(args[0] instanceof ParameterizedType)) {
            return type;
        }

        Class<?> erasedClassArgument = GenericClassFactory.get(args[0]).getRawClass();
        if (erasedClassArgument == null) {
            return type;
        }

        return GenericClassFactory.get(Class.class).getWithParameterTypes(new Type[]{erasedClassArgument}).getType();
    }

    /**
     * Adds the given {@code method} call to the {@code test} at the specified {@code position}.
     * For non-static methods, the callee object of the method is chosen at random.
     *
     * <p>Clients have to supply the current recursion depth. This allows for better
     * management of test generation resources. If this method is called from another method that
     * already has a recursion depth as formal parameter, passing that recursion depth + 1 is
     * appropriate. Otherwise, 0 should be used.
     *
     * <p>Returns a reference to the return value of the inserted method call. If the
     * {@link Properties#MAX_RECURSION maximum  recursion depth} has been reached a
     * {@code ConstructionFailedException} is thrown.
     *
     * @param test           the test case in which to insert
     * @param method         the method call to insert
     * @param position       the position at which to add the call
     * @param recursionDepth the current recursion depth (see above)
     * @return a reference to the return value of the inserted method call
     * @throws ConstructionFailedException if the maximum recursion depth has been reached
     */
    public VariableReference addMethod(TestCase test, GenericMethod method, int position,
                                       int recursionDepth) throws ConstructionFailedException {
        return addMethod(test, method, position, GenerationContext.fromDepth(recursionDepth));
    }

    public VariableReference addMethod(TestCase test, GenericMethod method, int position,
                                       GenerationContext context) throws ConstructionFailedException {
        return statementFactory.addMethod(test, method, position, context);
    }

    /**
     * Adds the given {@code method} call to the {@code test} at the specified {@code position},
     * using the supplied {@code VariableReference} as {@code callee} object of the {@code method}.
     * Only intended to be used for <em>non-static</em> methods! If a static {@code method} is
     * supplied, the behavior is undefined.
     *
     * <p>Returns a reference to the return value of the inserted method call. Throws a
     * {@code ConstructionFailedException} if the given {@code position} is invalid, i.e., if
     * {@code callee} is undefined (or has not been defined yet) at {@code position}.
     *
     * @param test     the test case in which to insert
     * @param callee   reference to the object on which to call the {@code method}
     * @param method   the method call to insert
     * @param position the position at which to add the call
     * @return a reference to the return value of the inserted method call
     * @throws ConstructionFailedException if the given position is invalid (see above)
     */
    public VariableReference addMethodFor(TestCase test, VariableReference callee,
                                          GenericMethod method, int position) throws ConstructionFailedException {
        return addMethodFor(test, callee, method, position, new GenerationContext());
    }

    public VariableReference addMethodFor(TestCase test, VariableReference callee,
                                          GenericMethod method, int position, GenerationContext context)
            throws ConstructionFailedException {
        return statementFactory.addMethodFor(test, callee, method, position, context);
    }

    /**
     * Adds the given primitive statement {@code old} at the specified {@code
     * position} to the test case {@code test}.
     *
     * @param test     the test case to which to add the statement
     * @param old      the primitive statement to add
     * @param position the position in {@code test} at which to add the statement
     * @return a reference to the return value of the added statement
     */
    private VariableReference addPrimitive(TestCase test, PrimitiveStatement<?> old,
                                           int position, int recursionDepth) throws ConstructionFailedException {
        return addPrimitive(test, old, position, GenerationContext.fromDepth(recursionDepth));
    }

    private VariableReference addPrimitive(TestCase test, PrimitiveStatement<?> old,
                                           int position, GenerationContext context) throws ConstructionFailedException {
        logger.debug("Adding primitive");
        Statement st = old.clone(test);
        return test.addStatement(st, position);
    }

    /**
     * Appends the given {@code statement} at the end of the test case {@code test}, trying to
     * satisfy parameters.
     *
     * <p>Called from TestChromosome when doing crossover
     *
     * @param test the test case.
     * @param statement the statement.
     */
    public void appendStatement(TestCase test, Statement statement)
            throws ConstructionFailedException {
        GenerationContext context = new GenerationContext();

        if (statement instanceof ConstructorStatement) {
            addConstructor(test, ((ConstructorStatement) statement).getConstructor(),
                    test.size(), context);
        } else if (statement instanceof MethodStatement) {
            GenericMethod method = ((MethodStatement) statement).getMethod();
            addMethod(test, method, test.size(), context);
        } else if (statement instanceof PrimitiveStatement<?>) {
            addPrimitive(test, (PrimitiveStatement<?>) statement, test.size(), context);
            // test.statements.add((PrimitiveStatement) statement);
        } else if (statement instanceof FieldStatement) {
            addField(test, ((FieldStatement) statement).getField(), test.size(), context);
        }
    }

    /**
     * Assign a value to an array index.
     *
     * @param test the test case.
     * @param array the array.
     * @param arrayIndex the index in the array.
     * @param position the position in the test case.
     * @throws ConstructionFailedException .
     */
    public void assignArray(TestCase test, VariableReference array, int arrayIndex,
                            int position) throws ConstructionFailedException {
        List<VariableReference> objects = test.getObjects(array.getComponentType(),
                position);
        Iterator<VariableReference> iterator = objects.iterator();
        GenericClass<?> componentClass = GenericClassFactory.get(array.getComponentType());
        // Remove assignments from the same array
        while (iterator.hasNext()) {
            VariableReference var = iterator.next();
            if (var instanceof ArrayIndex) {
                if (((ArrayIndex) var).getArray().equals(array)) {
                    iterator.remove();
                } else {
                    // Do not assign values of same type as array to elements
                    // This may e.g. happen if we have Object[], we could otherwise assign Object[] as values
                    if (((ArrayIndex) var).getArray().getType().equals(array.getType())) {
                        iterator.remove();
                    }
                }
            }
            if (componentClass.isWrapperType()) {
                Class<?> rawClass = ClassUtils.wrapperToPrimitive(componentClass.getRawClass());
                if (!var.getVariableClass().equals(rawClass)
                        && !var.getVariableClass().equals(componentClass.getRawClass())) {
                    iterator.remove();
                }
            }

        }
        logger.debug("Reusable objects: {}", objects);
        assignArray(test, array, arrayIndex, position, objects);
    }

    /**
     * Assign a value to an array index for a given set of objects.
     *
     * @param test the test case.
     * @param array the array.
     * @param arrayIndex the index in the array.
     * @param position the position in the test case.
     * @param objects the list of objects.
     * @throws ConstructionFailedException .
     */
    protected void assignArray(TestCase test, VariableReference array, int arrayIndex,
                               int position, List<VariableReference> objects)
            throws ConstructionFailedException {
        assert (array instanceof ArrayReference);
        ArrayReference arrRef = (ArrayReference) array;

        if (!objects.isEmpty()
                && Randomness.nextDouble() <= Properties.OBJECT_REUSE_PROBABILITY) {
            // Assign an existing value
            // TODO:
            // Do we need a special "[Array]AssignmentStatement"?
            VariableReference choice = Randomness.choice(objects);
            logger.debug("Reusing value: {}", choice);

            ArrayIndex index = new ArrayIndex(test, arrRef, arrayIndex);
            Statement st = new AssignmentStatement(test, index, choice);
            test.addStatement(st, position);
        } else {
            // Assign a new value
            // Need a primitive, method, constructor, or field statement where
            // retval is set to index
            // Need a version of attemptGeneration that takes retval as
            // parameter

            // OR: Create a new variablereference and then assign it to array
            // (better!)
            int oldLength = test.size();
            logger.debug("Attempting generation of object of type {}", array.getComponentType());
            VariableReference var = attemptGeneration(test, array.getComponentType(),
                    position);
            // Generics instantiation may lead to invalid types, so better double check
            if (!var.isAssignableTo(arrRef.getComponentType())) {
                throw new ConstructionFailedException("Error");
            }

            position += test.size() - oldLength;
            ArrayIndex index = new ArrayIndex(test, arrRef, arrayIndex);
            Statement st = new AssignmentStatement(test, index, var);
            test.addStatement(st, position);
        }
    }

    /**
     * Attempt to generate a non-null object; initialize recursion level to 0.
     */
    public VariableReference attemptGeneration(TestCase test, Type type, int position)
            throws ConstructionFailedException {
        return attemptGeneration(test, type, position, new GenerationContext(), false, null, true, true);
    }


    /**
     * Try to generate an object of a given type.
     *
     * @param test                      the test case.
     * @param type                      the type.
     * @param position                  the position in the test case.
     * @param context                   the generation context.
     * @param config                    the variable resolution configuration.
     * @return the generated variable reference
     * @throws ConstructionFailedException if construction fails
     */
    protected VariableReference attemptGeneration(TestCase test, Type type, int position,
                                                  GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.attemptGeneration(test, type, position, context, config);
    }

    protected VariableReference attemptGeneration(TestCase test, Type type, int position,
                                                  GenerationContext context, boolean allowNull,
                                                  VariableReference generatorRefToExclude,
                                                  boolean canUseMocks, boolean canReuseExistingVariables)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withAllowNull(allowNull)
                .withExcludeVar(generatorRefToExclude)
                .withCanUseMocks(canUseMocks)
                .withCanReuseExistingVariables(canReuseExistingVariables)
                .build();
        return attemptGeneration(test, type, position, context, config);
    }

    /**
     * In the test case {@code test}, tries to generate an object at the specified {@code
     * position} suitable to serve as instance for the class {@code java.lang.Object}. This might
     * be useful when generating tests for "legacy code" before the advent of generics in Java.
     * Such code is likely to use (unsafe) down-casts from {@code Object} to some other subclass.
     * Since {@code Object} is at the root of the type hierarchy the information that something is
     * of type {@code Object} is essentially as valuable as no type information at all. For this
     * reason, this method scans the byte code of the UUT for subsequent down-casts and tries to
     * generate an instance of the subclass being cast to. If {@code allowNull} is {@code true} it
     * is also possible to assign the {@code null} reference.
     *
     * <p>Clients have to supply the current recursion depth. This allows for better
     * management of test generation resources. If this method is called from another method that
     * already has a recursion depth as formal parameter, passing that recursion depth + 1 is
     * appropriate. Otherwise, 0 should be used.
     *
     * <p>Returns a reference to the created object of type {@code java.lang.Object}, or throws a
     * {@code ConstructionFailedException} if an error occurred.
     *
     * @param test           the test case in which to insert
     * @param position       the position at which to insert
     * @param context        the generation context
     * @param allowNull      whether to allow the creation of  the {@code null} reference
     * @return a reference to the created object
     * @throws ConstructionFailedException if creation fails
     */
    protected VariableReference attemptObjectGeneration(TestCase test, int position,
                                                        GenerationContext context, boolean allowNull)
            throws ConstructionFailedException {
        return variableResolver.attemptObjectGeneration(test, position, context, allowNull);
    }

    /**
     * Replace the statement with a new statement using given call.
     *
     * @param test the test case.
     * @param statement the statement.
     * @param call the call.
     * @throws ConstructionFailedException .
     */
    public void changeCall(TestCase test, Statement statement,
                           GenericAccessibleObject<?> call) throws ConstructionFailedException {
        testMutator.changeCall(test, statement, call);
    }

    /**
     * Returns a random non-null, non-primitive object from the test case.
     *
     * @param tc       the test case
     * @param type     the type of object to return
     * @param position the position in the test case
     * @return a random non-null, non-primitive object
     * @throws ConstructionFailedException if no such object is found
     */
    VariableReference getRandomNonNullNonPrimitiveObject(TestCase tc, Type type, int position)
            throws ConstructionFailedException {
        Inputs.checkNull(type);

        List<VariableReference> variables = tc.getObjects(type, position);
        variables.removeIf(var -> var instanceof NullReference
                || tc.getStatement(var.getStPosition()) instanceof PrimitiveStatement
                || var.isPrimitive()
                || var.isWrapperType()
                || tc.getStatement(var.getStPosition()) instanceof FunctionalMockStatement);

        if (variables.isEmpty()) {
            throw new ConstructionFailedException("Found no variables of type " + type
                    + " at position " + position);
        }

        return Randomness.choice(variables);
    }

    /**
     * Changes a random call in the test case.
     *
     * @param test      the test case
     * @param statement the statement to change
     * @return true if the call was changed
     */
    public boolean changeRandomCall(TestCase test, Statement statement) {
        return testMutator.changeRandomCall(test, statement);
    }

    /**
     * In the test case {@code test}, creates a new non-null array of the component type
     * represented by the given {@code arrayClass} at the specified {@code position}.
     *
     * <p>Clients have to supply the current generation context. This allows for better
     * management of test generation resources.
     *
     * <p>Returns a reference to the created array, or throws a {@code GenerationFailedException} if
     * generation was unsuccessful.
     *
     * @param test           the test case in which to insert the array
     * @param arrayClass     the component type of the array
     * @param position       the position at which to insert the array
     * @param context        the current generation context
     * @return a reference to the created array
     * @throws ConstructionFailedException if creation failed
     */
    VariableReference createArray(TestCase test, GenericClass<?> arrayClass,
                                          int position, GenerationContext context) throws ConstructionFailedException {

        logger.debug("Creating array of type {}", arrayClass.getTypeName());
        if (arrayClass.hasWildcardOrTypeVariables()) {
            // if (arrayClass.getComponentClass().isClass()) {
            //    arrayClass = arrayClass.getWithWildcardTypes();
            // } else {
            arrayClass = arrayClass.getGenericInstantiation();
            logger.debug("Setting generic array to type {}", arrayClass.getTypeName());
            // }
        }
        // Create array with random size
        ArrayStatement statement = new ArrayStatement(test, arrayClass.getType());
        VariableReference reference = test.addStatement(statement, position);
        position++;
        logger.debug("Array length: {}", statement.size());
        logger.debug("Array component type: {}", reference.getComponentType());

        // For each value of array, call attemptGeneration
        List<VariableReference> objects = test.getObjects(reference.getComponentType(),
                position);

        // Don't assign values to other values in the same array initially
        Iterator<VariableReference> iterator = objects.iterator();
        while (iterator.hasNext()) {
            VariableReference current = iterator.next();
            if (current instanceof ArrayIndex) {
                ArrayIndex index = (ArrayIndex) current;
                if (index.getArray().equals(statement.getReturnValue())) {
                    iterator.remove();
                } else {
                    // Do not assign values of same type as array to elements
                    // This may e.g. happen if we have Object[], we could otherwise assign Object[] as values
                    if (index.getArray().getType().equals(arrayClass.getType())) {
                        iterator.remove();
                    }
                }

            }
        }

        objects.remove(statement.getReturnValue());
        logger.debug("Found assignable objects: {}", objects.size());

        for (int i = 0; i < statement.size(); i++) {
            logger.debug("Assigning array index {}", i);
            int oldLength = test.size();
            assignArray(test, reference, i, position, objects);
            position += test.size() - oldLength;
        }
        reference.setDistance(context.getDepth());
        return reference;
    }

    /**
     * In the given test case {@code test} at the specified {@code position}, creates and returns a
     * new variable of the primitive or "simple data object" data type represented by {@code clazz}.
     * In detail, the following data types are accepted:
     * <ul>
     *     <li>all primitive data types ({@code byte}, {@code short}, {@code int}, {@code long},
     *     {@code float}, {@code double}, {@code boolean}, {@code char}),</li>
     *     <li>{@code String}s,</li>
     *     <li>enumeration types ("enums"),</li>
     *     <li>EvoSuite environment data types as defined in
     *     {@link org.evosuite.runtime.testdata.EnvironmentDataList EnvironmentDataList}, and</li>
     *     <li>class primitives ({@code Class.class}).</li>
     * </ul>
     * The {@code null} reference and arrays receive special treatment by their own dedicated
     * methods, {@code createNull} and {@code createArray}.
     *
     * <p>Clients have to supply the current generation context. This allows for better
     * management of test generation resources.
     *
     * <p>Returns a reference to the created primitive value, or throws a
     * {@code ConstructionFailedException} if creation is not possible.
     *
     * @param test           the test case for which to create the variable
     * @param clazz          the primitive data type of the variable to create (see above)
     * @param position       the position at which to insert the created variable
     * @param context        the current generation context
     * @return a reference to the created variable
     * @throws ConstructionFailedException if variable creation is not possible
     */
    VariableReference createPrimitive(TestCase test, GenericClass<?> clazz,
                                              int position, GenerationContext context)
            throws ConstructionFailedException {
        // Special case: we cannot instantiate Class<Class<?>>
        if (clazz.isClass()) {
            if (clazz.hasWildcardOrTypeVariables()) {
                logger.debug("Getting generic instantiation of class");
                clazz = clazz.getGenericInstantiation();
                logger.debug("Chosen: {}", clazz);
            }
            Type parameterType = clazz.getParameterTypes().get(0);
            if (!(parameterType instanceof WildcardType)
                    && GenericTypeReflector.erase(parameterType).equals(Class.class)) {
                throw new ConstructionFailedException(
                        "Cannot instantiate a class with a class");
            }
        }
        Statement st = PrimitiveStatement.getRandomStatement(test, clazz, position);
        VariableReference ret = test.addStatement(st, position);
        ret.setDistance(context.getDepth());
        return ret;
    }

    /**
     * Creates a new {@code null} variable of the given {@code type} at the given {@code position}
     * in the {@code test} case.
     *
     * <p>Clients have to supply the current generation context. This allows for better
     * management of test generation resources.
     *
     * <p>Returns a reference to the inserted {@code null} variable. If the creation of the variable
     * fails a {@code ConstructionFailedException} is thrown.
     *
     * @param test           the test case for which to create the {@code null} variable
     * @param type           represents the type of the variable to create
     * @param position       the position in {@code test} at which to insert
     * @param context        the current generation context
     * @return a reference to the inserted {@code null} variable
     * @throws ConstructionFailedException if the creation of the variable fails
     */
    VariableReference createNull(TestCase test, Type type, int position,
                                         GenerationContext context) throws ConstructionFailedException {
        GenericClass<?> genericType = GenericClassFactory.get(type);

        // For example, HashBasedTable.Factory in Guava is private but used as a parameter
        // in a public method. This would lead to compile errors
        if (!TestUsageChecker.canUse(genericType.getRawClass())) {
            throw new ConstructionFailedException("Cannot use class " + type);
        }
        if (genericType.hasWildcardOrTypeVariables()) {
            type = genericType.getGenericInstantiation().getType();
        }
        Statement st = new NullStatement(test, type);
        test.addStatement(st, position);
        VariableReference ret = test.getStatement(position).getReturnValue();
        ret.setDistance(context.getDepth());
        return ret;
    }


    /**
     * Creates a new object of the given complex (i.e. non-primitive) {@code type} and adds it to
     * the {@code test} case at the desired {@code position}. If the test case already contains an
     * object of the specified type, this method might simply return a reference to the already
     * existing object. Also, the insertion of a {@code null} reference is possible. The decision
     * about which action to take is made probabilistically.
     *
     * <p>Clients have to supply the current recursion depth. This allows for better
     * management of test generation resources. If this method is called from another method that
     * already has a recursion depth as formal parameter, passing that recursion depth + 1 is
     * appropriate. Otherwise, 0 should be used.
     *
     * <p>Returns a reference to the created object or throws a {@code ConstructionFailedException} if
     * generation was not possible.
     *
     * @param test                  the test case for which to create the object
     * @param type                  represents the type of the object to create
     * @param position              the position in {@code test} at which to insert the reference to the object
     * @param recursionDepth        the current recursion depth (see above)
     * @param generatorRefToExclude the generator reference to exclude.
     * @return a reference to the generated object
     * @throws ConstructionFailedException if generation was not possible
     */
    public VariableReference createObject(TestCase test, Type type, int position,
                                          int recursionDepth, VariableReference generatorRefToExclude)
            throws ConstructionFailedException {
        return createObject(test, type, position, GenerationContext.fromDepth(recursionDepth),
                generatorRefToExclude, true, true, true);
    }

    public VariableReference createObject(TestCase test, Type type, int position,
                                          GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.createObject(test, type, position, context, config);
    }

    /**
     * Creates an object.
     *
     * @param test the test case
     * @param type the type
     * @param position the position
     * @param context the generation context
     * @param generatorRefToExclude the generator to exclude
     * @param allowNull whether to allow null
     * @param canUseFunctionalMocks whether mocks can be used
     * @param canReuseVariables whether variables can be reused
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference createObject(TestCase test, Type type, int position,
                                          GenerationContext context, VariableReference generatorRefToExclude,
                                          boolean allowNull, boolean canUseFunctionalMocks,
                                          boolean canReuseVariables) throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(generatorRefToExclude)
                .withAllowNull(allowNull)
                .withCanUseMocks(canUseFunctionalMocks)
                .withCanReuseExistingVariables(canReuseVariables)
                .build();
        return createObject(test, type, position, context, config);
    }

    /**
     * Creates or reuses a variable.
     *
     * @param test the test case
     * @param parameterType the parameter type
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    VariableReference createOrReuseVariable(TestCase test, Type parameterType,
                                                    int position, GenerationContext context,
                                                    VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.resolveVariable(test, parameterType, position, context, config);
    }

    VariableReference createOrReuseVariable(TestCase test, Type parameterType,
                                                    int position, GenerationContext context, VariableReference exclude,
                                                    boolean allowNull, boolean excludeCalleeGenerators,
                                                    boolean canUseMocks)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(exclude)
                .withAllowNull(allowNull)
                .withExcludeCalleeGenerators(excludeCalleeGenerators)
                .withCanUseMocks(canUseMocks)
                .build();
        return createOrReuseVariable(test, parameterType, position, context, config);
    }

    private VariableReference createOrReuseVariableInternal(TestCase test, Type parameterType,
                                                             int position, GenerationContext context,
                                                             VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.resolveVariableInternal(test, parameterType, position, context, config);
    }

    private VariableReference createOrReuseVariableInternal(TestCase test, Type parameterType,
                                                             int position, GenerationContext context,
                                                             VariableReference exclude, boolean allowNull,
                                                             boolean excludeCalleeGenerators, boolean canUseMocks)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(exclude)
                .withAllowNull(allowNull)
                .withExcludeCalleeGenerators(excludeCalleeGenerators)
                .withCanUseMocks(canUseMocks)
                .build();
        return createOrReuseVariableInternal(test, parameterType, position, context, config);
    }

    private VariableReference createVariable(TestCase test, Type parameterType,
                                             int position, int recursionDepth, VariableReference exclude,
                                             boolean allowNull,
                                             boolean excludeCalleeGenerators, boolean canUseMocks,
                                             boolean canReuseExistingVariables)
            throws ConstructionFailedException {
        return createVariable(test, parameterType, position, GenerationContext.fromDepth(recursionDepth), exclude,
                allowNull, excludeCalleeGenerators, canUseMocks, canReuseExistingVariables);
    }

    /**
     * In the given {@code test} case, tries to create a variable of the type represented by
     * {@code parameterType} at the specified {@code position}. Clients can tweak the creation
     * process using the following parameters:
     * <ul>
     *     <li>If {@code allowNull} is set to {@code true} the generation of {@code null} objects
     *     is possible. Only applies if {@code parameterType} represents a non-primitive type.</li>
     *     <li>If {@code canUseMocks} is set to {@code true} the generation of mocks for the
     *     specified {@code parameterType} is possible.</li>
     *     <li>If {@code canReuseExistingVariables} is set to {@code true} the method is
     *     allowed to return a reference to an already existing object of the given type
     *     instead of generating a new one. The given {@code position} is ignored in this case.</li>
     * </ul>
     *
     * <p>Clients have to supply the current generation context. This allows for better
     * management of test generation resources.
     *
     * @param test                      the test case for which to create a new variable
     * @param parameterType             represents the type of the variable to create
     * @param position                  the desired position for the insertion of the variable
     * @param context                   the current generation context
     * @param config                    the variable resolution configuration
     * @return a reference to the created variable
     * @throws ConstructionFailedException if creation of the variable failed
     */
    private VariableReference createVariable(TestCase test, Type parameterType,
                                             int position, GenerationContext context,
                                             VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.createVariable(test, parameterType, position, context, config);
    }

    private VariableReference createVariable(TestCase test, Type parameterType,
                                             int position, GenerationContext context, VariableReference exclude,
                                             boolean allowNull,
                                             boolean excludeCalleeGenerators, boolean canUseMocks,
                                             boolean canReuseExistingVariables)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(exclude)
                .withAllowNull(allowNull)
                .withExcludeCalleeGenerators(excludeCalleeGenerators)
                .withCanUseMocks(canUseMocks)
                .withCanReuseExistingVariables(canReuseExistingVariables)
                .build();
        return createVariable(test, parameterType, position, context, config);
    }

    private List<VariableReference> getCandidatesForReuse(TestCase test, Type parameterType, int position,
                                                          VariableReference exclude,
                                                          boolean allowNull, boolean canUseMocks) {

        // look at all vars defined before pos
        List<VariableReference> objects = test.getObjects(parameterType, position);

        // if an exclude var was specified, then remove it
        if (exclude != null) {
            objects.remove(exclude);
            if (exclude.getAdditionalVariableReference() != null) {
                objects.remove(exclude.getAdditionalVariableReference());
            }

            objects.removeIf(v -> exclude.equals(v.getAdditionalVariableReference()));
        }

        List<VariableReference> additionalToRemove = new ArrayList<>();

        // no mock should be used more than once
        Iterator<VariableReference> iter = objects.iterator();
        while (iter.hasNext()) {
            VariableReference ref = iter.next();
            if (!(test.getStatement(ref.getStPosition()) instanceof FunctionalMockStatement)) {
                continue;
            }

            // check if current mock var is used anywhere: if so, then we cannot choose it
            for (int i = ref.getStPosition() + 1; i < test.size(); i++) {
                Statement st = test.getStatement(i);
                if (st.getVariableReferences().contains(ref)) {
                    iter.remove();
                    additionalToRemove.add(ref);
                    break;
                }
            }
        }

        // check for null
        if (!allowNull) {
            iter = objects.iterator();
            while (iter.hasNext()) {
                VariableReference ref = iter.next();

                if (ConstraintHelper.isNull(ref, test)) {
                    iter.remove();
                    additionalToRemove.add(ref);
                }
            }
        }

        // check for mocks
        if (!canUseMocks) {
            iter = objects.iterator();
            while (iter.hasNext()) {
                VariableReference ref = iter.next();

                if (test.getStatement(ref.getStPosition()) instanceof FunctionalMockStatement) {
                    iter.remove();
                    additionalToRemove.add(ref);
                }
            }
        }

        // further remove all other vars that have the deleted ones as additionals
        iter = objects.iterator();
        while (iter.hasNext()) {
            VariableReference ref = iter.next();
            VariableReference additional = ref.getAdditionalVariableReference();
            if (additional == null) {
                continue;
            }
            if (additionalToRemove.contains(additional)) {
                iter.remove();
            }
        }

        // avoid using characters as values for numeric types arguments
        iter = objects.iterator();
        String parCls = parameterType.getTypeName();
        if (Integer.TYPE.getTypeName().equals(parCls) || Long.TYPE.getTypeName().equals(parCls)
                || Float.TYPE.getTypeName().equals(parCls) || Double.TYPE.getTypeName().equals(parCls)) {
            while (iter.hasNext()) {
                VariableReference ref = iter.next();
                String cls = ref.getType().getTypeName();
                if ((Character.TYPE.getTypeName().equals(cls))) {
                    iter.remove();
                }
            }
        }

        // final safety filter: ensure type compatibility is consistent
        iter = objects.iterator();
        while (iter.hasNext()) {
            VariableReference ref = iter.next();
            if (!ref.isAssignableTo(parameterType)) {
                logger.debug("Removing incompatible reuse candidate {} for type {}", ref, parameterType);
                iter.remove();
            }
        }

        return objects;
    }

    /**
     * In the given test case {@code test}, tries to insert a reference to an object compatible with
     * {@code java.lang.Object} at the desired {@code position}. This method is specifically
     * intended to create or reuse a variable that can be assigned to {@code java.lang.Object}.
     * For any other type, {@code createOrReuseVariable} should be used instead.
     *
     * <p>Source code using {@code Object} often dates back to pre-generic versions of Java. As such,
     * it was necessary to specify {@code Object} as data type for parameters or variables and use
     * (unsafe) downcasts if polymorphism was desired. The inherent drawback was the circumvention
     * of the type system and thus the loss of static type information, among others. This poses a
     * great challenge for test generation. In an attempt to tackle this challenge, this method
     * scans the byte code for subsequent downcasts, and only returns references to objects of the
     * type being downcast to. This is more likely to yield tests that don't fail at runtime due to
     * casting errors.
     *
     * <p>Clients have to supply the current generation context. This allows for better
     * management of test generation resources.
     *
     * <p>Returns a reference to the created variable, or throws a {@code ConstructionFailedException}
     * if creation failed.
     *
     * @param test           the test in which to insert
     * @param position       the position at which to insert
     * @param context        the current generation context
     * @param config         the variable resolution configuration
     * @return a reference to the created variable
     * @throws ConstructionFailedException if creation fails
     */
    private VariableReference createOrReuseObjectVariable(TestCase test, int position,
                                                          GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {
        return variableResolver.resolveObjectVariable(test, position, context, config);
    }

    private VariableReference createOrReuseObjectVariable(TestCase test, int position,
                                                          GenerationContext context, VariableReference exclude,
                                                          boolean allowNull, boolean canUseMocks)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(exclude)
                .withAllowNull(allowNull)
                .withCanUseMocks(canUseMocks)
                .build();
        return createOrReuseObjectVariable(test, position, context, config);
    }

    /**
     * Delete the statement at position from the test case and remove all
     * references to it.
     *
     * @param test the test case.
     * @param position the position in the test case.
     * @return false if it was not possible to delete the statement
     * @throws ConstructionFailedException .
     */
    public boolean deleteStatement(TestCase test, int position) {
        return testMutator.deleteStatement(test, position);
    }

    /**
     * Summary.
     * @param test the test case.
     * @param position the position in the test case.
     * @return true if statements was deleted or any dependency was modified
     * @throws ConstructionFailedException .
     */
    public boolean deleteStatementGracefully(TestCase test, int position)
            throws ConstructionFailedException {
        return testMutator.deleteStatementGracefully(test, position);
    }

    /**
     * Inserts a random reflection call into the test case.
     *
     * @param test           the test case
     * @param position       the position to insert the call
     * @param recursionDepth current recursion depth
     * @return true if successful
     * @throws ConstructionFailedException if construction fails
     */
    private boolean insertRandomReflectionCall(TestCase test, int position, int recursionDepth)
            throws ConstructionFailedException {
        return insertRandomReflectionCall(test, position, GenerationContext.fromDepth(recursionDepth));
    }

    boolean insertRandomReflectionCall(TestCase test, int position, GenerationContext context)
            throws ConstructionFailedException {

        logger.debug("Recursion depth: {}", context.getDepth());
        if (context.getDepth() > Properties.MAX_RECURSION) {
            logger.debug("Max recursion depth reached");
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        int length = test.size();
        List<VariableReference> parameters = null;
        Statement st = null;

        ReflectionFactory rf = getReflectionFactory();
        if (rf.nextUseField()) {
            Field field = rf.nextField();
            parameters = satisfyParameters(test, null,
                    // we need a reference to the SUT, and one to a variable of same type of chosen field
                    Arrays.asList(rf.getReflectedClass(), field.getType()), null,
                    position, context.deeper(), true, false, true);

            try {
                st = new PrivateFieldStatement(test, rf.getReflectedClass(), field.getName(),
                        parameters.get(0), parameters.get(1));
            } catch (NoSuchFieldException e) {
                logger.error("Reflection problem: {}", e.getMessage(), e);
                throw new ConstructionFailedException("Reflection problem");
            }
        } else {
            // method
            Method method = rf.nextMethod();
            List<Type> list = new ArrayList<>();
            list.add(rf.getReflectedClass());
            list.addAll(Arrays.asList(method.getGenericParameterTypes()));

            // Added 'null' as additional parameter - fix for @NotNull annotations issue on evo mailing list
            parameters = satisfyParameters(test, null, list, null, position, context.deeper(), true, false, true);
            VariableReference callee = parameters.remove(0);

            st = new PrivateMethodStatement(test, rf.getReflectedClass(), method,
                    callee, parameters, Modifier.isStatic(method.getModifiers()));
        }

        int newLength = test.size();
        position += (newLength - length);

        test.addStatement(st, position);
        return true;
    }

    /**
     * Inserts a random reflection call on a specific object into the test case.
     *
     * @param test           the test case
     * @param callee         the object to call the method on
     * @param position       the position to insert the call
     * @param recursionDepth current recursion depth
     * @return true if successful
     * @throws ConstructionFailedException if construction fails
     */
    private boolean insertRandomReflectionCallOnObject(TestCase test, VariableReference callee,
                                                       int position, int recursionDepth)
            throws ConstructionFailedException {
        return insertRandomReflectionCallOnObject(test, callee, position, GenerationContext.fromDepth(recursionDepth));
    }

    boolean insertRandomReflectionCallOnObject(TestCase test, VariableReference callee,
                                                       int position, GenerationContext context)
            throws ConstructionFailedException {

        logger.debug("Recursion depth: {}", context.getDepth());
        if (context.getDepth() > Properties.MAX_RECURSION) {
            logger.debug("Max recursion depth reached");
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        ReflectionFactory rf = getReflectionFactory();
        if (!rf.getReflectedClass().isAssignableFrom(callee.getVariableClass())) {
            logger.debug("Reflection not performed on class {}", callee.getVariableClass());
            return false;
        }

        int length = test.size();
        List<VariableReference> parameters = null;
        Statement st = null;

        if (rf.nextUseField()) {
            Field field = rf.nextField();

            /*
                In theory, there might be cases in which using null in PA might help increasing
                coverage. However, likely most of the time we ll end up in useless tests throwing
                NPE on the private fields. As we maximize the number of methods throwing exceptions,
                we could end up with a lot of useless tests
             */
            boolean allowNull = false;

            // Added 'null' as additional parameter - fix for @NotNull annotations issue on evo mailing list
            parameters = satisfyParameters(test, callee,
                    // we need a reference to the SUT, and one to a variable of same type of chosen field
                    Collections.singletonList(field.getType()), null,
                    position, context.deeper(), allowNull, false, true);

            try {
                st = new PrivateFieldStatement(test, rf.getReflectedClass(), field.getName(),
                        callee, parameters.get(0));
            } catch (NoSuchFieldException e) {
                logger.error("Reflection problem: {}", e.getMessage(), e);
                throw new ConstructionFailedException("Reflection problem");
            }
        } else {
            // method
            Method method = rf.nextMethod();
            List<Type> list = new ArrayList<>(Arrays.asList(method.getParameterTypes()));
            // Added 'null' as additional parameter - fix for @NotNull annotations issue on evo mailing list
            parameters = satisfyParameters(test, callee, list, null, position, context.deeper(), true, false, true);

            st = new PrivateMethodStatement(test, rf.getReflectedClass(), method,
                    callee, parameters, Modifier.isStatic(method.getModifiers()));
        }

        int newLength = test.size();
        position += (newLength - length);

        test.addStatement(st, position);
        return true;
    }

    /**
     * Tries to insert a random call on the environment the UUT interacts with, e.g., the file
     * system or network connections. Callers have to specify the position of the last valid.
     * statement of {@code test} before the insertion. Returns the updated position of the last
     * valid statement after a successful insertion, or a negative value if there was an error.
     *
     * @param test              the test case.
     * @param lastValidPosition the last valid position.
     * @return the position where the insertion happened, or a negative value otherwise
     */
    public int insertRandomCallOnEnvironment(TestCase test, int lastValidPosition) {

        int previousLength = test.size();
        GenerationContext context = new GenerationContext();

        List<GenericAccessibleObject<?>> shuffledOptions = TestCluster.getInstance()
                .getRandomizedCallsToEnvironment();
        if (shuffledOptions == null || shuffledOptions.isEmpty()) {
            return -1;
        }

        // iterate (in random order) over all possible environment methods till we find one that can be inserted
        for (GenericAccessibleObject<?> o : shuffledOptions) {
            try {
                int position;
                if (lastValidPosition <= 0) {
                    position = 0;
                } else {
                    position = Randomness.nextInt(0, lastValidPosition);
                }

                if (o.isConstructor()) {
                    GenericConstructor c = (GenericConstructor) o;
                    addConstructor(test, c, position, context);
                    return position;
                } else if (o.isMethod()) {
                    GenericMethod m = (GenericMethod) o;
                    if (!m.isStatic()) {

                        VariableReference callee = null;
                        Type target = m.getOwnerType();

                        if (!test.hasObject(target, position)) {
                            callee = createObject(test, target, position, context, null);
                            position += test.size() - previousLength;
                            previousLength = test.size();
                        } else {
                            callee = test.getRandomNonNullObject(target, position);
                        }
                        if (!TestUsageChecker.canUse(m.getMethod(), callee.getVariableClass())
                                || !MethodStatement.isCompatibleCalleeType(m, callee.getType())) {
                            logger.error("Cannot call method {} with callee of type {}", m, callee.getClassName());
                            throw new ConstructionFailedException("Cannot apply method to this callee");
                        }

                        addMethodFor(test, callee, m.copyWithNewOwner(callee.getGenericClass()), position, context);
                        return position;
                    } else {
                        addMethod(test, m, position, context);
                        return position;
                    }
                } else {
                    throw new RuntimeException("Unrecognized type for environment: " + o);
                }
            } catch (ConstructionFailedException e) {
                // TODO what to do here?
                AtMostOnceLogger.warn(logger, "Failed environment insertion: " + e);
            }
        }

        // note: due to the constraints, it could well be that no environment method could be added

        return -1;
    }


    /**
     * Inserts a random call for the UUT into the given {@code test} at the specified {@code
     * position}. Returns {@code true} on success, {@code false} otherwise.
     *
     * @param test     the test case in which to insert
     * @param position the position at which to insert
     * @return {@code true} if successful, {@code false} otherwise
     */
    public boolean insertRandomCall(TestCase test, int position) {
        return testMutator.insertRandomCall(test, position);
    }

    /**
     * Within the given {@code test} case, inserts a random call at the specified {@code position}
     * on the object referenced by {@code var}. Returns {@code true} if the operation was successful
     * and {@code false} otherwise.
     *
     * <p>This method is especially useful if someone wants to insert a random call to a variable
     * that is subsequently used as a parameter for the method under test (MUT). The idea is to
     * mutate the parameter so that new program states can be reached in the MUT.
     *
     * @param test     the test case in which to insert
     * @param var      the reference to the object on which to perform the random method call
     * @param position the position at which to insert the call
     * @return {@code true} if successful, {@code false} otherwise
     */
    public boolean insertRandomCallOnObjectAt(TestCase test, VariableReference var, int position) {
        return testMutator.insertRandomCallOnObjectAt(test, var, position);
    }


    /**
     * Inserts one or perhaps multiple random statements into the given {@code test}. Callers
     * have to specify the position of the last valid statement of {@code test} by supplying an
     * appropriate index {@code lastPosition}. After a successful insertion, returns the updated
     * position of the last valid statement (which is always non-negative), or if there was an error
     * the constant {@link org.evosuite.testcase.mutation.InsertionStrategy#INSERTION_ERROR
     * INSERTION_ERROR}.
     *
     * @param test         the test case in which to insert
     * @param lastPosition the position of the last valid statement of {@code test} before insertion
     * @return the position of the last valid statement after insertion, or {@code INSERTION_ERROR}
     *         (see above)
     */
    public int insertRandomStatement(TestCase test, int lastPosition) {
        return testMutator.insertRandomStatement(test, lastPosition);
    }

    /**
     * Satisfies parameters of a call.
     *
     * @param test the test case
     * @param callee the callee
     * @param parameterTypes the parameter types
     * @param parameterList the parameter list
     * @param position the position
     * @param recursionDepth the recursion depth
     * @param allowNull whether to allow null
     * @param excludeCalleeGenerators whether to exclude callee generators
     * @param canReuseExistingVariables whether variables can be reused
     * @return the list of variable references
     * @throws ConstructionFailedException if construction fails
     */
    public List<VariableReference> satisfyParameters(TestCase test, VariableReference callee,
                                                     List<Type> parameterTypes,
                                                     List<Parameter> parameterList, int position, int recursionDepth,
                                                     boolean allowNull,
                                                     boolean excludeCalleeGenerators,
                                                     boolean canReuseExistingVariables)
            throws ConstructionFailedException {
        return satisfyParameters(test, callee, parameterTypes, parameterList, position,
                GenerationContext.fromDepth(recursionDepth), allowNull, excludeCalleeGenerators,
                canReuseExistingVariables);
    }

    /**
     * Satisfies the parameters of a call.
     *
     * @param test the test case
     * @param callee the callee
     * @param parameterTypes the parameter types
     * @param parameterList the parameter list
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the list of variable references
     * @throws ConstructionFailedException if construction fails
     */
    public List<VariableReference> satisfyParameters(TestCase test, VariableReference callee,
                                                     List<Type> parameterTypes,
                                                     List<Parameter> parameterList, int position,
                                                     GenerationContext context,
                                                     VariableResolutionConfig config)
            throws ConstructionFailedException {

        if (callee == null && config.isExcludeCalleeGenerators()) {
            throw new IllegalArgumentException("Exclude generators on null callee");
        }

        List<VariableReference> parameters = new ArrayList<>();
        logger.debug("Trying to satisfy {} parameters at position {}", parameterTypes.size(), position);

        for (int i = 0; i < parameterTypes.size(); i++) {
            Type parameterType = parameterTypes.get(i);
            Parameter parameter = null;
            VariableResolutionConfig.Builder paramConfigBuilder = new VariableResolutionConfig.Builder(config);

            if (parameterList != null) {
                parameter = parameterList.get(i);
            }

            logger.debug("Current parameter type: {}", parameterType);

            if (parameterType instanceof CaptureType) {
                // TODO: This should not really happen in the first place
                throw new ConstructionFailedException("Cannot satisfy capture type");
            }

            GenericClass<?> parameterClass = GenericClassFactory.get(parameterType);
            if (parameterClass.hasTypeVariables()) {
                logger.debug("Parameter has type variables, replacing with wildcard");
                parameterType = parameterClass.getWithWildcardTypes().getType();
            }
            int previousLength = test.size();

            VariableReference var = null;

            if (Properties.HONOUR_DATA_ANNOTATIONS && (parameterList != null)) {

                if (GenericUtils.isAnnotationTypePresent(parameter.getAnnotations(), GenericUtils.NONNULL)) {
                    paramConfigBuilder.withAllowNull(false);
                }
            }

            VariableResolutionConfig paramConfig = paramConfigBuilder.build();

            if (paramConfig.isCanReuseExistingVariables()) {
                logger.debug("Can re-use variables");
                var = createOrReuseVariable(test, parameterType, position, context, paramConfig);
            } else {
                logger.debug("Cannot re-use variables: attempt at creating new one");
                var = createVariable(test, parameterType, position, context, paramConfig);
                if (var == null) {
                    throw new ConstructionFailedException(
                            "Failed to create variable for type " + parameterType + " at position " + position);
                }
                if (!var.isAssignableTo(parameterType)) {
                    String message = var + " cannot be assigned to " + parameterType;
                    throwCannotAssignIfNeeded(message, test, position, parameterType, var);
                    throw new ConstructionFailedException(message);
                }
            }

            assert !(!paramConfig.isAllowNull() && ConstraintHelper.isNull(var, test));

            parameters.add(var);

            int currentLength = test.size();
            position += currentLength - previousLength;
        }
        logger.debug("Satisfied {} parameters", parameterTypes.size());
        return parameters;
    }

    /**
     * Satisfies parameters of a call.
     *
     * @param test the test case
     * @param callee the callee
     * @param parameterTypes the parameter types
     * @param parameterList the parameter list
     * @param position the position
     * @param context the generation context
     * @param allowNull whether to allow null
     * @param excludeCalleeGenerators whether to exclude callee generators
     * @param canReuseExistingVariables whether variables can be reused
     * @return the list of variable references
     * @throws ConstructionFailedException if construction fails
     */
    public List<VariableReference> satisfyParameters(TestCase test, VariableReference callee,
                                                     List<Type> parameterTypes,
                                                     List<Parameter> parameterList, int position,
                                                     GenerationContext context,
                                                     boolean allowNull,
                                                     boolean excludeCalleeGenerators,
                                                     boolean canReuseExistingVariables)
            throws ConstructionFailedException {
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withExcludeVar(callee)
                .withAllowNull(allowNull)
                .withExcludeCalleeGenerators(excludeCalleeGenerators)
                .withCanReuseExistingVariables(canReuseExistingVariables)
                .build();
        return satisfyParameters(test, callee, parameterTypes, parameterList, position, context, config);
    }

    /**
     * Throws an IllegalStateException if a variable cannot be assigned to an expected type.
     *
     * @param message the error message
     * @param test the test case
     * @param position the position
     * @param expectedType the expected type
     * @param actualVar the actual variable
     */
    static void throwCannotAssignIfNeeded(String message, TestCase test, int position, Type expectedType,
                                                  VariableReference actualVar) {
        if (actualVar != null && isClassLiteralAssignableByErasure(expectedType, actualVar.getType())) {
            return;
        }
        if (message != null && message.contains("cannot be assigned")) {
            StringBuilder diagnostic = new StringBuilder(512);
            diagnostic.append(message)
                    .append("\nPosition: ").append(position)
                    .append("\nExpected type: ").append(expectedType)
                    .append("\nActual var: ").append(actualVar)
                    .append("\nActual var type: ").append(actualVar == null ? "null" : actualVar.getType())
                    .append("\nTest:\n").append(test.toCode());
            throw new IllegalStateException(diagnostic.toString());
        }
    }

    /**
     * Checks if a class literal is assignable by erasure.
     *
     * @param expectedType the expected type
     * @param actualType the actual type
     * @return true if it is assignable
     */
    static boolean isClassLiteralAssignableByErasure(Type expectedType, Type actualType) {
        if (!(expectedType instanceof ParameterizedType) || !(actualType instanceof ParameterizedType)) {
            return false;
        }

        ParameterizedType expected = (ParameterizedType) expectedType;
        ParameterizedType actual = (ParameterizedType) actualType;
        if (!(expected.getRawType() instanceof Class) || !(actual.getRawType() instanceof Class)) {
            return false;
        }
        if (!Class.class.equals(expected.getRawType()) || !Class.class.equals(actual.getRawType())) {
            return false;
        }

        Type[] expectedArgs = expected.getActualTypeArguments();
        Type[] actualArgs = actual.getActualTypeArguments();
        if (expectedArgs.length != 1 || actualArgs.length != 1) {
            return false;
        }

        Class<?> expectedErased = GenericClassFactory.get(expectedArgs[0]).getRawClass();
        Class<?> actualErased = GenericClassFactory.get(actualArgs[0]).getRawClass();
        return expectedErased != null && actualErased != null && expectedErased.isAssignableFrom(actualErased);
    }

}
