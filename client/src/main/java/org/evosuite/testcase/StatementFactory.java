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

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

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
        FunctionalMockStatement fms = new FunctionalMockStatement(test, type, GenericClassFactory.get(type));
        return test.addStatement(fms, position);
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
        FunctionalMockForAbstractClassStatement fms = new FunctionalMockForAbstractClassStatement(test,
                type,
                GenericClassFactory.get(type));
        return test.addStatement(fms, position);
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
            
            if (isListCapacityConstructor(constructor)) {
                validateOrAdjustListCapacityParameter(test, position, parameters);
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

    private boolean isListCapacityConstructor(GenericConstructor constructor) {
        Class<?> rawType = constructor.getRawGeneratedType();
        if (rawType == null || !List.class.isAssignableFrom(rawType)) {
            return false;
        }
        Class<?>[] rawParams = constructor.getConstructor().getParameterTypes();
        return rawParams.length == 1 && rawParams[0].equals(int.class);
    }

    private void validateOrAdjustListCapacityParameter(TestCase test,
                                                              int insertionPosition,
                                                              List<VariableReference> parameters)
            throws ConstructionFailedException {
        if (parameters == null || parameters.isEmpty()) {
            throw new ConstructionFailedException("Missing list capacity parameter");
        }
        VariableReference param = parameters.get(0);
        Statement st = test.getStatement(param.getStPosition());
        if (!(st instanceof org.evosuite.testcase.statements.numeric.IntPrimitiveStatement)) {
            return;
        }
        org.evosuite.testcase.statements.numeric.IntPrimitiveStatement intStatement =
                (org.evosuite.testcase.statements.numeric.IntPrimitiveStatement) st;
        int value = intStatement.getValue();
        int maxCapacity = Math.max(0, Properties.MAX_ARRAY);
        if (value < 0 || value > maxCapacity) {
            if (param.getStPosition() < insertionPosition) {
                throw new ConstructionFailedException("List capacity too large: " + value);
            }
            int bounded = Randomness.nextInt(maxCapacity + 1);
            intStatement.setValue(bounded);
        }
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

        Type expectedFieldType = testFactory.normalizeClassLiteralTypeArgumentByErasure(field.getFieldType());
        VariableReference var = testFactory.createOrReuseVariable(test, expectedFieldType,
                position, context, callee, true, false, false);
        int newLength = test.size();
        position += (newLength - length);

        FieldReference f = new FieldReference(test, field, callee);
        if (f.equals(var)) {
            throw new ConstructionFailedException("Self assignment");
        }
        if (!var.isAssignableTo(f.getType())) {
            String message = var + " cannot be assigned to " + f.getType();
            testFactory.throwCannotAssignIfNeeded(message, test, position, f.getType(), var);
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
        if (position <= callee.getStPosition()) {
            throw new ConstructionFailedException("Cannot insert call on object before the object is defined");
        }

        int length = test.size();
        Type expectedFieldType = testFactory.normalizeClassLiteralTypeArgumentByErasure(field.getFieldType());
        VariableReference value = testFactory.createOrReuseVariable(test, expectedFieldType,
                position, context, callee, true, false, true);

        int newLength = test.size();
        position += (newLength - length);
        if (!value.isAssignableTo(field.getFieldType())) {
            String message = value + " cannot be assigned to " + field.getFieldType();
            testFactory.throwCannotAssignIfNeeded(message, test, position, field.getFieldType(), value);
            throw new ConstructionFailedException(message);
        }

        Statement st = new AssignmentStatement(test, new FieldReference(test, field, callee), value);
        VariableReference ret = test.addStatement(st, position);
        ret.setDistance(callee.getDistance() + 1);

        assert (test.isValid());

        return ret;
    }
}
