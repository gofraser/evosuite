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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for creating statements during test case generation.
 *
 * @author Gordon Fraser
 */
public class StatementFactory {

    private static final Logger logger = LoggerFactory.getLogger(StatementFactory.class);

    private final TestFactory testFactory;

    public StatementFactory(TestFactory testFactory) {
        this.testFactory = testFactory;
    }

    /**
     * Adds a functional mock to the test case.
     *
     * @param test the test case
     * @param type the type to mock
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the mock
     * @throws ConstructionFailedException if construction fails
     * @throws IllegalArgumentException if arguments are invalid
     */
    public VariableReference addFunctionalMock(TestCase test, Type type,
                                               int position, GenerationContext context)
            throws ConstructionFailedException, IllegalArgumentException {

        Inputs.checkNull(test, type);

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        //TODO this needs to be fixed once we handle Generics in mocks
        try {
            FunctionalMockStatement fms = new FunctionalMockStatement(test, type, GenericClassFactory.get(type));
            return test.addStatement(fms, position);
        } catch (IllegalArgumentException e) {
            throw new ConstructionFailedException("Cannot create functional mock for "
                    + GenericClassFactory.get(type).getRawClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Adds a functional mock for an abstract class.
     *
     * @param test the test case
     * @param type the type to mock
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the mock
     * @throws ConstructionFailedException if construction fails
     * @throws IllegalArgumentException if arguments are invalid
     */
    public VariableReference addFunctionalMockForAbstractClass(TestCase test,
                                                               Type type,
                                                               int position,
                                                               GenerationContext context)
            throws ConstructionFailedException, IllegalArgumentException {

        Inputs.checkNull(test, type);

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        //TODO this needs to be fixed once we handle Generics in mocks
        try {
            FunctionalMockForAbstractClassStatement fms = new FunctionalMockForAbstractClassStatement(test,
                    type,
                    GenericClassFactory.get(type));
            return test.addStatement(fms, position);
        } catch (IllegalArgumentException e) {
            throw new ConstructionFailedException("Cannot create abstract-class functional mock for "
                    + GenericClassFactory.get(type).getRawClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Adds a constructor call.
     *
     * @param test the test case
     * @param constructor the constructor
     * @param exactType the exact type
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the created object
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addConstructor(TestCase test,
                                            GenericConstructor constructor, Type exactType, int position,
                                            GenerationContext context) throws ConstructionFailedException {

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        int length = test.size();

        try {
            List<VariableReference> parameters = testFactory.satisfyParameters(test,
                    null,
                    Arrays.asList(constructor.getParameterTypes()),
                    Arrays.asList(constructor.getConstructor().getParameters()),
                    position,
                    context.deeper(),
                    VariableResolutionConfig.defaultConfig());
            
            if (isCapacitySensitiveConstructor(constructor)) {
                validateOrAdjustCapacityParameters(test, constructor, position, parameters);
            }
            int newLength = test.size();
            position += (newLength - length);

            Statement st = new ConstructorStatement(test, constructor, parameters);
            return test.addStatement(st, position);
        } catch (Exception e) {
            throw new ConstructionFailedException("Failed to add constructor for "
                    + constructor.getRawGeneratedType().getName()
                    + " due to " + e.getClass().getCanonicalName() + ": " + e.getMessage());
        }
    }

    /**
     * JDK classes whose constructors allocate memory proportional to an int argument,
     * but are not covered by Collection/Map checks or bytecode-level instrumentation
     * (since JDK classes are loaded by the bootstrap classloader).
     *
     * <p>This static list covers known offenders. It is supplemented at runtime by
     * {@link #addAllocationSensitiveClass(String)} when a constructor causes
     * OutOfMemoryError during test execution.</p>
     */
    private static final Set<String> ALLOCATION_SENSITIVE_CLASSES = new HashSet<>(Arrays.asList(
            // Swing — allocate O(rows) or O(rows*cols) internal objects
            "javax.swing.table.DefaultTableModel",
            "javax.swing.JTable",
            // Swing text — allocate internal char[] proportional to int arg
            "javax.swing.text.StringContent",
            "javax.swing.text.GapContent",
            // AWT image — allocate pixel/sample arrays proportional to dimensions
            "java.awt.image.BufferedImage",
            "java.awt.image.DataBufferByte",
            "java.awt.image.DataBufferInt",
            "java.awt.image.DataBufferShort",
            "java.awt.image.DataBufferFloat",
            "java.awt.image.DataBufferDouble",
            // I/O — allocate internal byte[]/char[] buffers
            "java.io.ByteArrayOutputStream",
            "java.io.CharArrayWriter",
            // I/O — allocate internal buffers proportional to int arg
            "java.io.PipedInputStream",
            "java.io.PipedOutputStream",
            "java.io.PipedReader",
            "java.io.PipedWriter",
            "java.io.PushbackInputStream",
            "java.io.BufferedInputStream",
            "java.io.BufferedOutputStream",
            "java.io.BufferedReader",
            "java.io.BufferedWriter",
            "java.io.LineNumberReader",
            // Writers — allocate internal char[] proportional to int arg
            "java.io.StringWriter",
            // String builders — already guarded in SUT bytecode by
            // ArrayAllocationLimitMethodAdapter, but not when called via
            // reflection from ConstructorStatement
            "java.lang.StringBuilder",
            "java.lang.StringBuffer",
            // BigInteger — the probable-prime constructor BigInteger(int, int, Random)
            // runs Lucas-Lehmer primality testing that is extremely CPU-intensive
            // for large bitLength values. Even moderate values (>10000 bits) can
            // stall the executor thread.
            "java.math.BigInteger",
            // NIO buffers — allocate(int) creates a backing array of that size
            "java.nio.ByteBuffer",
            "java.nio.CharBuffer",
            "java.nio.ShortBuffer",
            "java.nio.IntBuffer",
            "java.nio.LongBuffer",
            "java.nio.FloatBuffer",
            "java.nio.DoubleBuffer"
    ));

    /**
     * Classes discovered at runtime to cause OOM when constructed with large int args.
     * Populated by {@link #addAllocationSensitiveClass(String)} when a constructor
     * throws OutOfMemoryError during test execution, so that future test generation
     * avoids repeating the same mistake.
     */
    private static final Set<String> dynamicAllocationSensitiveClasses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Registers a class as allocation-sensitive at runtime, after observing that
     * its constructor caused an OutOfMemoryError. Future constructor calls for this
     * class will have their int parameters capped.
     *
     * @param className the fully qualified class name (dots)
     */
    public static void addAllocationSensitiveClass(String className) {
        if (className != null && dynamicAllocationSensitiveClasses.add(className)) {
            LoggerFactory.getLogger(StatementFactory.class)
                    .warn("Registered {} as allocation-sensitive after OOM in constructor", className);
        }
    }

    /**
     * Methods discovered at runtime to cause OOM. Maps a method key
     * (className.methodName) to the maximum allowed positive int arg value.
     * The threshold starts at half the smallest arg value that caused OOM,
     * and halves again on each subsequent OOM for the same method.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer>
            allocationSensitiveMethods = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Registers a method as allocation-sensitive after it caused an OOM.
     * The threshold is set to half the smallest positive int arg that was
     * passed when the OOM occurred. If the method is already registered,
     * the threshold is halved again.
     *
     * <p>If the method has no positive int arguments, no dynamic threshold
     * can be learned and the registration is skipped.</p>
     *
     * @param className  fully qualified class name
     * @param methodName method name
     * @param args       the arguments that caused the OOM
     */
    public static void addAllocationSensitiveMethod(String className, String methodName,
                                                     Object[] args) {
        String key = className + "." + methodName;
        // Find the smallest positive int arg that was passed
        int minPositiveArg = Integer.MAX_VALUE;
        for (Object arg : args) {
            if (arg instanceof Integer) {
                int val = (Integer) arg;
                if (val > 0 && val < minPositiveArg) {
                    minPositiveArg = val;
                }
            }
        }
        if (minPositiveArg == Integer.MAX_VALUE) {
            LoggerFactory.getLogger(StatementFactory.class)
                    .warn("OOM in method {} but no positive int args were present; "
                            + "skipping dynamic threshold registration", key);
            return;
        }
        int newThreshold = Math.max(1, minPositiveArg / 2);

        allocationSensitiveMethods.merge(key, newThreshold, (existing, proposed) -> {
            // Halve the existing threshold on each subsequent OOM
            return Math.max(1, existing / 2);
        });
        LoggerFactory.getLogger(StatementFactory.class)
                .warn("Registered method {} as allocation-sensitive (threshold={})",
                        key, allocationSensitiveMethods.get(key));
    }

    /**
     * Returns the int-arg threshold for a method that has been registered as
     * allocation-sensitive, or -1 if the method is not registered.
     */
    public static int getAllocationSensitiveMethodThreshold(String className, String methodName) {
        String key = className + "." + methodName;
        Integer threshold = allocationSensitiveMethods.get(key);
        return threshold != null ? threshold : -1;
    }

    /**
     * Returns true if the given class is known (statically or dynamically) to allocate
     * memory proportional to an int constructor argument. Used by ConstructorStatement
     * at execution time to cap mutated values.
     */
    public static boolean isAllocationSensitive(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz)) {
            return true;
        }
        // Walk the class hierarchy so subclasses of whitelisted classes are also caught
        // (e.g. LineNumberReader extends BufferedReader)
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (ALLOCATION_SENSITIVE_CLASSES.contains(name)
                    || dynamicAllocationSensitiveClasses.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a constructor can allocate memory proportional to an int argument.
     * Covers Collection/Map constructors (capacity param) and known allocation-sensitive
     * JDK classes like DefaultTableModel.
     */
    private boolean isCapacitySensitiveConstructor(GenericConstructor constructor) {
        Class<?>[] rawParams = constructor.getConstructor().getParameterTypes();
        boolean hasIntParam = false;
        for (Class<?> p : rawParams) {
            if (p.equals(int.class)) {
                hasIntParam = true;
                break;
            }
        }
        if (!hasIntParam) {
            return false;
        }

        Class<?> rawType = constructor.getRawGeneratedType();
        if (rawType == null) {
            return false;
        }

        if (Collection.class.isAssignableFrom(rawType) || Map.class.isAssignableFrom(rawType)) {
            return true;
        }

        String name = rawType.getName();
        return ALLOCATION_SENSITIVE_CLASSES.contains(name)
                || dynamicAllocationSensitiveClasses.contains(name);
    }

    /**
     * Validates int constructor parameters against the capacity limit.
     * Checks both individual values and the product of all positive int args,
     * since classes like DefaultTableModel(int rows, int cols) allocate
     * O(rows × cols) memory.
     *
     * <p>At generation time we can safely adjust the IntPrimitiveStatement
     * values since the statement hasn't been finalized yet.</p>
     */
    private void validateOrAdjustCapacityParameters(TestCase test,
                                                    GenericConstructor constructor,
                                                    int insertionPosition,
                                                    List<VariableReference> parameters)
            throws ConstructionFailedException {
        if (parameters == null || parameters.isEmpty()) {
            throw new ConstructionFailedException("Missing parameters");
        }
        Class<?>[] rawParams = constructor.getConstructor().getParameterTypes();
        int maxCapacity = getCapacityLimit(constructor);

        // First pass: cap individual values
        for (int i = 0; i < rawParams.length && i < parameters.size(); i++) {
            if (!rawParams[i].equals(int.class)) {
                continue;
            }
            VariableReference param = parameters.get(i);
            Statement st = test.getStatement(param.getStPosition());
            if (!(st instanceof org.evosuite.testcase.statements.numeric.IntPrimitiveStatement)) {
                continue;
            }

            org.evosuite.testcase.statements.numeric.IntPrimitiveStatement intSt =
                    (org.evosuite.testcase.statements.numeric.IntPrimitiveStatement) st;
            int value = intSt.getValue();
            if (value < 0 || value > maxCapacity) {
                if (param.getStPosition() < insertionPosition) {
                    throw new ConstructionFailedException(
                            "Capacity-sensitive constructor arg too large: " + value);
                }
                intSt.setValue(Randomness.nextInt(maxCapacity + 1));
            }
        }

        // Second pass: check product of positive int args
        long product = 1;
        boolean hasPositiveInt = false;
        for (int i = 0; i < rawParams.length && i < parameters.size(); i++) {
            if (!rawParams[i].equals(int.class)) {
                continue;
            }
            VariableReference param = parameters.get(i);
            Statement st = test.getStatement(param.getStPosition());
            if (!(st instanceof org.evosuite.testcase.statements.numeric.IntPrimitiveStatement)) {
                continue;
            }
            int value = ((org.evosuite.testcase.statements.numeric.IntPrimitiveStatement) st).getValue();
            if (value > 0) {
                product *= value;
                hasPositiveInt = true;
            }
        }
        if (hasPositiveInt && product > maxCapacity) {
            // Scale down: set each positive int arg to roughly the nth root of maxCapacity
            int intArgCount = 0;
            for (Class<?> p : rawParams) {
                if (p.equals(int.class)) {
                    intArgCount++;
                }
            }
            int perArgLimit = (int) Math.pow(maxCapacity, 1.0 / intArgCount);
            for (int i = 0; i < rawParams.length && i < parameters.size(); i++) {
                if (!rawParams[i].equals(int.class)) {
                    continue;
                }
                VariableReference param = parameters.get(i);
                Statement st = test.getStatement(param.getStPosition());
                if (!(st instanceof org.evosuite.testcase.statements.numeric.IntPrimitiveStatement)) {
                    continue;
                }
                org.evosuite.testcase.statements.numeric.IntPrimitiveStatement intSt =
                        (org.evosuite.testcase.statements.numeric.IntPrimitiveStatement) st;
                if (intSt.getValue() > perArgLimit) {
                    if (param.getStPosition() < insertionPosition) {
                        throw new ConstructionFailedException(
                                "Capacity-sensitive constructor arg product too large: " + product);
                    }
                    intSt.setValue(Randomness.nextInt(perArgLimit + 1));
                }
            }
        }
    }

    private int getCapacityLimit(GenericConstructor constructor) {
        Class<?> rawType = constructor.getRawGeneratedType();
        if (rawType != null && Map.class.isAssignableFrom(rawType)) {
            return Math.max(0, Properties.MAP_CAPACITY_LIMIT);
        }
        return Math.max(0, Properties.COLLECTION_CAPACITY_LIMIT);
    }

    /**
     * Adds a method call.
     *
     * @param test the test case
     * @param method the method
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the return value
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addMethod(TestCase test, GenericMethod method, int position,
                                       GenerationContext context) throws ConstructionFailedException {

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        int length = test.size();
        VariableReference callee = null;
        List<VariableReference> parameters = null;

        try {
            if (!method.isStatic()) {
                callee = testFactory.createOrReuseVariable(test, method.getOwnerType(), position,
                        context, VariableResolutionConfig.defaultConfig());
                
                position += test.size() - length;
                length = test.size();

                if (!TestUsageChecker.canUse(method.getMethod(), callee.getVariableClass())) {
                    throw new ConstructionFailedException("Cannot apply method to this callee");
                }
                if (!MethodStatement.isCompatibleCalleeType(method, callee.getType())) {
                    throw new ConstructionFailedException("Cannot apply method to this callee");
                }
            }

            parameters = testFactory.satisfyParameters(test, callee,
                    Arrays.asList(method.getParameterTypes()),
                    Arrays.asList(method.getMethod().getParameters()),
                    position, context.deeper(), true, false, true);

        } catch (ConstructionFailedException e) {
            throw e;
        }

        int newLength = test.size();
        position += (newLength - length);

        Statement st = new MethodStatement(test, method, callee, parameters);
        VariableReference ret = test.addStatement(st, position);
        if (callee != null) {
            ret.setDistance(callee.getDistance() + 1);
        }
        return ret;
    }

    /**
     * Adds a method call for a specific callee.
     *
     * @param test the test case
     * @param callee the callee
     * @param method the method
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the return value
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addMethodFor(TestCase test, VariableReference callee,
                                          GenericMethod method, int position, GenerationContext context)
            throws ConstructionFailedException {

        if (position <= callee.getStPosition()) {
            throw new ConstructionFailedException("Cannot insert call on object before the object is defined");
        }

        int length = test.size();

        List<VariableReference> parameters = testFactory.satisfyParameters(
                test, callee,
                Arrays.asList(method.getParameterTypes()),
                Arrays.asList(method.getMethod().getParameters()), position, context.deeper(), true, false, true);

        int newLength = test.size();
        position += (newLength - length);

        Statement st = new MethodStatement(test, method, callee, parameters);
        VariableReference ret = test.addStatement(st, position);
        ret.setDistance(callee.getDistance() + 1);

        return ret;
    }

    /**
     * Adds a field access.
     *
     * @param test the test case
     * @param field the field
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the field
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addField(TestCase test, GenericField field, int position,
                                      GenerationContext context) throws ConstructionFailedException {

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        VariableReference callee = null;
        int length = test.size();

        if (!field.isStatic()) {
            callee = testFactory.createOrReuseVariable(test, field.getOwnerType(), position,
                    context, VariableResolutionConfig.defaultConfig());
            position += test.size() - length;

            if (!TestUsageChecker.canUse(field.getField(), callee.getVariableClass())) {
                throw new ConstructionFailedException("Cannot apply field to this callee");
            }

            if (!field.getOwnerClass().equals(callee.getGenericClass())) {
                try {
                    if (!TestUsageChecker.canUse(callee.getVariableClass().getField(field.getName()))) {
                        throw new ConstructionFailedException("Cannot access field in subclass");
                    }
                } catch (NoSuchFieldException fe) {
                    throw new ConstructionFailedException("Cannot access field in subclass");
                }
            }
        }

        Statement st = new FieldStatement(test, field, callee);
        return test.addStatement(st, position);
    }

    /**
     * Adds a field assignment.
     *
     * @param test the test case
     * @param field the field
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the result
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addFieldAssignment(TestCase test, GenericField field,
                                                int position, GenerationContext context)
            throws ConstructionFailedException {
        if (TestClusterUtils.isFinalField(field.getField())) {
            throw new ConstructionFailedException("Cannot assign to final field " + field.getName());
        }

        if (context.getDepth() > Properties.MAX_RECURSION) {
            throw new ConstructionFailedException("Max recursion depth reached");
        }

        int length = test.size();
        VariableReference callee = null;
        if (!field.isStatic()) {
            callee = testFactory.createOrReuseVariable(test, field.getOwnerType(), position,
                    context, VariableResolutionConfig.defaultConfig());
            position += test.size() - length;
            length = test.size();
            if (!TestUsageChecker.canUse(field.getField(), callee.getVariableClass())) {
                throw new ConstructionFailedException("Cannot apply field to this callee");
            }
        }

        Type expectedFieldType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(field.getFieldType());
        VariableReference var = testFactory.createOrReuseVariable(test, expectedFieldType,
                position, context, callee, true, false, false);
        int newLength = test.size();
        position += (newLength - length);

        FieldReference f = new FieldReference(test, field, callee);
        if (f.equals(var)) {
            throw new ConstructionFailedException("Self assignment");
        }
        Type expectedAssignmentType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(f.getType());
        if (!var.isAssignableTo(expectedAssignmentType)) {
            String message = var + " cannot be assigned to " + expectedAssignmentType;
            testFactory.throwCannotAssignIfNeeded(message, test, position, expectedAssignmentType, var);
            throw new ConstructionFailedException(message);
        }

        Statement st = new AssignmentStatement(test, f, var);
        VariableReference ret = test.addStatement(st, position);
        assert (test.isValid());
        return ret;
    }

    /**
     * Adds a field assignment for a specific callee.
     *
     * @param test the test case
     * @param callee the callee
     * @param field the field
     * @param position the position
     * @param context the generation context
     * @return the variable reference to the result
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference addFieldFor(TestCase test, VariableReference callee,
                                         GenericField field, int position, GenerationContext context)
            throws ConstructionFailedException {
        if (TestClusterUtils.isFinalField(field.getField())) {
            throw new ConstructionFailedException("Cannot assign to final field " + field.getName());
        }

        if (position <= callee.getStPosition()) {
            throw new ConstructionFailedException("Cannot insert call on object before the object is defined");
        }

        int length = test.size();
        Type expectedFieldType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(field.getFieldType());
        VariableReference value = testFactory.createOrReuseVariable(test, expectedFieldType,
                position, context, callee, true, false, true);

        int newLength = test.size();
        position += (newLength - length);
        Type expectedAssignmentType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(field.getFieldType());
        if (!value.isAssignableTo(expectedAssignmentType)) {
            String message = value + " cannot be assigned to " + expectedAssignmentType;
            testFactory.throwCannotAssignIfNeeded(message, test, position, expectedAssignmentType, value);
            throw new ConstructionFailedException(message);
        }

        Statement st = new AssignmentStatement(test, new FieldReference(test, field, callee), value);
        VariableReference ret = test.addStatement(st, position);
        ret.setDistance(callee.getDistance() + 1);

        assert (test.isValid());

        return ret;
    }
}
