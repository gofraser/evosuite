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
import org.evosuite.TimeController;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.mutation.RandomInsertion;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.reflection.ReflectionFactory;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for mutating test cases.
 *
 * @author Gordon Fraser
 */
public class TestMutator {

    private static final Logger logger = LoggerFactory.getLogger(TestMutator.class);

    private final TestFactory testFactory;

    public TestMutator(TestFactory testFactory) {
        this.testFactory = testFactory;
    }

    /**
     * Changes a call in a statement.
     *
     * @param test the test case
     * @param statement the statement to change
     * @param call the new call
     * @throws ConstructionFailedException if construction fails
     */
    public void changeCall(TestCase test, Statement statement,
                           GenericAccessibleObject<?> call) throws ConstructionFailedException {
        int position = statement.getReturnValue().getStPosition();

        logger.debug("Changing call {} with {}", test.getStatement(position), call);

        if (call.isMethod()) {
            GenericMethod method = (GenericMethod) call;
            if (method.hasTypeParameters()) {
                try {
                    method = method.getGenericInstantiationFromReturnValue(
                            GenericClassFactory.get(statement.getReturnType()));
                } catch (ConstructionFailedException e) {
                    throw new ConstructionFailedException("Cannot handle generic methods properly");
                }
            }

            VariableReference retval = statement.getReturnValue();
            VariableReference callee = null;
            if (!method.isStatic()) {
                callee = testFactory.getRandomNonNullNonPrimitiveObject(test, method.getOwnerType(), position);
            }

            List<VariableReference> parameters = new ArrayList<>();
            for (Type type : method.getParameterTypes()) {
                Type effectiveType = type;
                GenericClass<?> parameterClass = GenericClassFactory.get(type);
                if (parameterClass.hasTypeVariables()) {
                    effectiveType = parameterClass.getWithWildcardTypes().getType();
                    logger.debug("Parameter has type variables, using wildcard type {} for {}", effectiveType, type);
                }
                effectiveType = TestFactory.normalizeClassLiteralTypeArgumentByErasure(effectiveType);
                parameters.add(test.getRandomObject(effectiveType, position));
            }
            MethodStatement m = new MethodStatement(test, method, callee, parameters, retval);
            test.setStatement(m, position);
            logger.debug("Using method {}", m.getCode());

        } else if (call.isConstructor()) {

            GenericConstructor constructor = (GenericConstructor) call;
            VariableReference retval = statement.getReturnValue();
            List<VariableReference> parameters = new ArrayList<>();
            for (Type type : constructor.getParameterTypes()) {
                Type effectiveType = type;
                GenericClass<?> parameterClass = GenericClassFactory.get(type);
                if (parameterClass.hasTypeVariables()) {
                    effectiveType = parameterClass.getWithWildcardTypes().getType();
                    logger.debug("Parameter has type variables, using wildcard type {} for {}", effectiveType, type);
                }
                effectiveType = TestFactory.normalizeClassLiteralTypeArgumentByErasure(effectiveType);
                parameters.add(test.getRandomObject(effectiveType, position));
            }
            ConstructorStatement c = new ConstructorStatement(test, constructor, retval, parameters);

            test.setStatement(c, position);
            logger.debug("Using constructor {}", c.getCode());

        } else if (call.isField()) {
            GenericField field = (GenericField) call;
            VariableReference retval = statement.getReturnValue();
            VariableReference source = null;
            if (!field.isStatic()) {
                source = testFactory.getRandomNonNullNonPrimitiveObject(test, field.getOwnerType(), position);
            }

            try {
                FieldStatement f = new FieldStatement(test, field, source, retval);
                test.setStatement(f, position);
                logger.debug("Using field {}", f.getCode());
            } catch (Throwable e) {
                logger.error("Error: {} , Field: {} , Test: {}", e, field, test);
                throw new Error(e);
            }
        }
    }

    /**
     * Changes a random call in a statement.
     *
     * @param test the test case
     * @param statement the statement to change
     * @return true if the call was changed
     */
    public boolean changeRandomCall(TestCase test, Statement statement) {
        logger.debug("Changing statement {}", statement.getCode());

        List<VariableReference> objects = test.getObjects(statement.getReturnValue().getStPosition());
        objects.remove(statement.getReturnValue());

        Iterator<VariableReference> iter = objects.iterator();
        while (iter.hasNext()) {
            VariableReference ref = iter.next();
            // do not use FM as possible callees
            if (test.getStatement(ref.getStPosition()) instanceof FunctionalMockStatement) {
                iter.remove();
                continue;
            }
        }

        // TODO: replacing void calls with other void calls might not be the best idea
        List<GenericAccessibleObject<?>> calls = getPossibleCalls(statement.getReturnType(), objects);

        GenericAccessibleObject<?> ao = statement.getAccessibleObject();
        if (ao != null && ao.getNumParameters() > 0) {
            calls.remove(ao);
        }

        logger.debug("Got {} possible calls for {} objects", calls.size(), objects.size());

        // calls.clear();
        if (calls.isEmpty()) {
            logger.debug("No replacement calls");
            return false;
        }

        GenericAccessibleObject<?> call = Randomness.choice(calls);
        try {
            changeCall(test, statement, call);
            return true;
        } catch (ConstructionFailedException e) {
            String message = e.getMessage();
            if (message != null && message.contains("cannot be assigned")) {
                StringBuilder diagnostic = new StringBuilder(512);
                diagnostic.append("Change failed for statement ")
                        .append(statement.getCode())
                        .append(" -> ")
                        .append(call)
                        .append(": ")
                        .append(message)
                        .append("\nTest:\n")
                        .append(test.toCode());
                throw new IllegalStateException(diagnostic.toString(), e);
            }
            // Ignore
            logger.info("Change failed for statement {} -> {}: {} {}",
                    statement.getCode(), call, e.getMessage(), test.toCode());
        }
        return false;
    }

    /**
     * Deletes a statement.
     *
     * @param test the test case
     * @param position the position
     * @return true if deleted
     */
    public boolean deleteStatement(TestCase test, int position) {

        logger.debug("Deleting target statement - {}", position);

        Set<Integer> toDelete = new LinkedHashSet<>();
        recursiveDeleteInclusion(test, toDelete, position);

        List<Integer> pos = new ArrayList<>(toDelete);
        pos.sort(Collections.reverseOrder());

        for (int i : pos) {
            logger.debug("Deleting statement: {}", i);
            test.remove(i);
        }

        return true;
    }

    private void recursiveDeleteInclusion(TestCase test, Set<Integer> toDelete, int position) {

        if (toDelete.contains(position)) {
            return; //end of recursion
        }

        toDelete.add(position);

        Set<Integer> references = getReferencePositions(test, position);

        /*
            it can happen that we can delete the target statements but, when we look at
            the other statements using it, then we could not delete them :(
            in those cases, we have to recursively look at all their dependencies.
         */

        for (int i : references) {
            recursiveDeleteInclusion(test, toDelete, i);
        }
    }

    private Set<Integer> getReferencePositions(TestCase test, int position) {
        Set<VariableReference> references = new LinkedHashSet<>();
        Set<Integer> positions = new LinkedHashSet<>();
        references.add(test.getReturnValue(position));

        for (int i = position; i < test.size(); i++) {
            Set<VariableReference> temp = new LinkedHashSet<>();
            for (VariableReference v : references) {
                if (test.getStatement(i).references(v)) {
                    temp.add(test.getStatement(i).getReturnValue());
                    positions.add(i);
                }
            }
            references.addAll(temp);
        }
        return positions;
    }

    /**
     * Deletes a statement gracefully.
     *
     * @param test the test case
     * @param position the position
     * @return true if deleted
     * @throws ConstructionFailedException if construction fails
     */
    public boolean deleteStatementGracefully(TestCase test, int position)
            throws ConstructionFailedException {
        VariableReference var = test.getReturnValue(position);

        if (var instanceof ArrayIndex) {
            return deleteStatement(test, position);
        }

        boolean changed = false;

        boolean replacingPrimitive = test.getStatement(position) instanceof PrimitiveStatement;

        // Get possible replacements
        List<VariableReference> alternatives = test.getObjects(var.getType(), position);

        int maxIndex = 0;
        if (var instanceof ArrayReference) {
            maxIndex = ((ArrayReference) var).getMaximumIndex();
        }

        // Remove invalid classes if this is an Object.class reference
        if (test.getStatement(position) instanceof MethodStatement) {
            MethodStatement ms = (MethodStatement) test.getStatement(position);
            if (ms.getReturnType().equals(Object.class)) {
                //                filterVariablesByClass(alternatives, var.getVariableClass());
                filterVariablesByClass(alternatives, Object.class);
            }
        } else if (test.getStatement(position) instanceof ConstructorStatement) {
            ConstructorStatement cs = (ConstructorStatement) test.getStatement(position);
            if (cs.getReturnType().equals(Object.class)) {
                filterVariablesByClass(alternatives, Object.class);
            }
        }

        // Remove self, and all field or array references to self
        alternatives.remove(var);
        Iterator<VariableReference> replacement = alternatives.iterator();
        while (replacement.hasNext()) {
            VariableReference r = replacement.next();
            if (test.getStatement(r.getStPosition()) instanceof FunctionalMockStatement) {
                // we should ensure that a FM should never be a callee
                replacement.remove();
            } else if (var.equals(r.getAdditionalVariableReference())) {
                replacement.remove();
            } else if (var.isFieldReference()) {
                FieldReference fref = (FieldReference) var;
                if (fref.getField().isFinal()) {
                    replacement.remove();
                }
            } else if (r instanceof ArrayReference) {
                if (maxIndex >= ((ArrayReference) r).getArrayLength()) {
                    replacement.remove();
                }
            } else if (!replacingPrimitive) {
                if (test.getStatement(r.getStPosition()) instanceof PrimitiveStatement) {
                    replacement.remove();
                }
            }
        }

        if (!alternatives.isEmpty()) {
            // Change all references to return value at position to something else
            for (int i = position + 1; i < test.size(); i++) {
                Statement s = test.getStatement(i);
                if (s.references(var)) {
                    if (s.isAssignmentStatement()) {
                        AssignmentStatement assignment = (AssignmentStatement) s;
                        if (assignment.getValue() == var) {
                            VariableReference replacementVar = Randomness.choice(alternatives);
                            if (assignment.getReturnValue().isAssignableFrom(replacementVar)) {
                                s.replace(var, replacementVar);
                                changed = true;
                            }
                        } else if (assignment.getReturnValue() == var) {
                            VariableReference replacementVar = Randomness.choice(alternatives);
                            if (replacementVar.isAssignableFrom(assignment.getValue())) {
                                s.replace(var, replacementVar);
                                changed = true;
                            }
                        }
                    } else {
                        List<VariableReference> candidates = alternatives;
                        if (s instanceof MethodStatement) {
                            MethodStatement ms = (MethodStatement) s;
                            if (ms.getCallee() == var) {
                                candidates = alternatives.stream()
                                        .filter(r -> r.isAssignableTo(ms.getMethod().getMethod().getDeclaringClass()))
                                        .collect(Collectors.toList());
                            }
                        }
                        if (!candidates.isEmpty()) {
                            s.replace(var, Randomness.choice(candidates));
                            changed = true;
                        }
                    }
                }
            }
        }

        if (var instanceof ArrayReference) {
            alternatives = test.getObjects(var.getComponentType(), position);
            // Remove self, and all field or array references to self
            alternatives.remove(var);
            replacement = alternatives.iterator();
            while (replacement.hasNext()) {
                VariableReference r = replacement.next();
                if (var.equals(r.getAdditionalVariableReference())) {
                    replacement.remove();
                } else if (r instanceof ArrayReference) {
                    if (maxIndex >= ((ArrayReference) r).getArrayLength()) {
                        replacement.remove();
                    }
                }
            }
            if (!alternatives.isEmpty()) {
                // Change all references to return value at position to something else
                for (int i = position; i < test.size(); i++) {
                    Statement s = test.getStatement(i);
                    for (VariableReference var2 : s.getVariableReferences()) {
                        if (var2 instanceof ArrayIndex) {
                            ArrayIndex ai = (ArrayIndex) var2;
                            if (ai.getArray().equals(var)) {
                                s.replace(var2, Randomness.choice(alternatives));
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Remove everything else
        boolean deleted = deleteStatement(test, position);
        return deleted || changed;
    }

    private static void filterVariablesByClass(Collection<VariableReference> variables, Class<?> clazz) {
        // Remove invalid classes if this is an Object.class reference
        variables.removeIf(r -> !r.getVariableClass().equals(clazz));
    }

    /**
     * Inserts a random call.
     *
     * @param test the test case
     * @param position the position
     * @return true if inserted
     */
    public boolean insertRandomCall(TestCase test, int position) {
        String name = "";
        GenerationContext context = new GenerationContext();
        StatementInserter inserter = new StatementInserter(test, position);
        logger.debug("Inserting random call at position {}", position);
        try {
            ReflectionFactory rf = testFactory.getReflectionFactory();

            if (rf.hasPrivateFieldsOrMethods()
                    && TimeController.getInstance().getPhasePercentage() >= Properties.REFLECTION_START_PERCENT
                    && (Randomness.nextDouble() < Properties.P_REFLECTION_ON_PRIVATE
                    || TestCluster.getInstance().getNumTestCalls() == 0)) {
                logger.debug("Going to insert random reflection call");
                return testFactory.insertRandomReflectionCall(test, position, context);
            }
            GenericAccessibleObject<?> o = TestCluster.getInstance().getRandomTestCall(test);
            if (o == null) {
                logger.warn("Have no target methods to test");
                return false;
            } else if (o.isConstructor()) {

                GenericConstructor c = (GenericConstructor) o;
                logger.debug("Adding constructor call {}", c.getName());
                name = c.getName();
                testFactory.addConstructor(test, c, position, context);
            } else if (o.isMethod()) {
                GenericMethod m = (GenericMethod) o;
                logger.debug("Adding method call {}", m.getName());
                name = m.getName();

                if (!m.isStatic()) {
                    logger.debug("Getting callee of type {}", m.getOwnerClass().getTypeName());
                    VariableReference callee = null;
                    Type target = m.getOwnerType();

                    if (!test.hasObject(target, position)) {
                        // no FM for SUT
                        callee = testFactory.createObject(test, target, position, context, null, false, false, true);
                    } else {
                        callee = test.getRandomNonNullObject(target, position);
                    }
                    logger.debug("Got callee of type {}", callee.getGenericClass().getTypeName());
                    if (!TestUsageChecker.canUse(m.getMethod(), callee.getVariableClass())
                            || !MethodStatement.isCompatibleCalleeType(m, callee.getType())) {
                        logger.debug("Cannot call method {} with callee of type {}", m, callee.getClassName());
                        throw new ConstructionFailedException("Cannot apply method to this callee");
                    }

                    testFactory.addMethodFor(test, callee, m.copyWithNewOwner(callee.getGenericClass()),
                            position, context);
                } else {
                    // We only use this for static methods to avoid using wrong constructors (?)
                    testFactory.addMethod(test, m, position, context);
                }
            } else if (o.isField()) {
                GenericField f = (GenericField) o;
                name = f.getName();
                logger.debug("Adding field {}", f.getName());
                if (Randomness.nextBoolean()) {
                    testFactory.addFieldAssignment(test, f, position, context);
                } else {
                    testFactory.addField(test, f, position, context);
                }
            } else {
                logger.error("Got type other than method or constructor!");
                return false;
            }

            return true;
        } catch (ConstructionFailedException e) {
            logger.debug("Inserting statement {} has failed. Rolling back statements: {}", name, e);
            inserter.rollback();
            return false;
        }
    }

    /**
     * Inserts a random call on a specific object.
     *
     * @param test the test case
     * @param var the variable reference
     * @param position the position
     * @return true if inserted
     */
    public boolean insertRandomCallOnObjectAt(TestCase test, VariableReference var, int position) {

        // Select a random variable
        logger.debug("Chosen object: {}", var.getName());

        if (var instanceof ArrayReference) {
            logger.debug("Chosen object is array ");

            ArrayReference array = (ArrayReference) var;
            if (array.getArrayLength() > 0) {
                for (int i = 0; i < array.getArrayLength(); i++) {
                    logger.debug("Assigning array index {}", i);
                    int oldLen = test.size();
                    try {
                        testFactory.assignArray(test, array, i, position);
                        position += test.size() - oldLen;
                    } catch (ConstructionFailedException e) {
                        logger.debug("Failed to assign array index {}", i);
                    }
                }
                return true;
            }
        } else if (var.getGenericClass().hasWildcardOrTypeVariables()) {
            logger.debug("Cannot add calls on unknown type");
        } else {
            logger.debug("Getting calls for object {}", var);
            try {
                ReflectionFactory rf = testFactory.getReflectionFactory();

                if (rf.hasPrivateFieldsOrMethods()
                        && TimeController.getInstance().getPhasePercentage() >= Properties.REFLECTION_START_PERCENT
                        && Randomness.nextDouble() < Properties.P_REFLECTION_ON_PRIVATE) {
                    return testFactory.insertRandomReflectionCallOnObject(test, var, position, new GenerationContext());
                }

                GenericAccessibleObject<?> call = TestCluster.getInstance().getRandomCallFor(var.getGenericClass(),
                        test, position);
                logger.debug("Chosen call {}", call);
                return testFactory.addCallFor(test, var, call, position, new GenerationContext());
            } catch (ConstructionFailedException e) {
                logger.debug("Found no modifier: {}", e.getMessage());
            }
        }

        return false;
    }

    public int insertRandomStatement(TestCase test, int lastPosition) {
        RandomInsertion rs = new RandomInsertion();
        return rs.insertStatement(test, lastPosition);
    }

    private static boolean dependenciesSatisfied(Set<Type> dependencies,
                                                 List<VariableReference> objects) {
        for (Type type : dependencies) {
            boolean found = false;
            for (VariableReference var : objects) {
                if (var.isAssignableTo(type)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static Set<Type> getDependencies(GenericConstructor constructor) {
        return new LinkedHashSet<>(Arrays.asList(constructor.getParameterTypes()));
    }

    private static Set<Type> getDependencies(GenericField field) {
        Set<Type> dependencies = new LinkedHashSet<>();
        if (!field.isStatic()) {
            dependencies.add(field.getOwnerType());
        }

        return dependencies;
    }

    private static Set<Type> getDependencies(GenericMethod method) {
        Set<Type> dependencies = new LinkedHashSet<>();
        if (!method.isStatic()) {
            dependencies.add(method.getOwnerType());
        }
        dependencies.addAll(Arrays.asList(method.getParameterTypes()));

        return dependencies;
    }

    List<GenericAccessibleObject<?>> getPossibleCalls(Type returnType,
                                                              List<VariableReference> objects) {
        List<GenericAccessibleObject<?>> calls = new ArrayList<>();
        Set<GenericAccessibleObject<?>> allCalls;

        try {
            allCalls = TestCluster.getInstance().getGenerators(GenericClassFactory.get(
                    returnType));
        } catch (ConstructionFailedException e) {
            return calls;
        }

        for (GenericAccessibleObject<?> call : allCalls) {
            Set<Type> dependencies = null;
            if (call.isMethod()) {
                GenericMethod method = (GenericMethod) call;
                if (method.hasTypeParameters()) {
                    try {
                        call = method.getGenericInstantiationFromReturnValue(GenericClassFactory.get(returnType));
                    } catch (ConstructionFailedException e) {
                        continue;
                    }
                }
                // Sanity check: owner type must be compatible with declaring class
                GenericMethod instantiated = (GenericMethod) call;
                GenericClass<?> ownerClass = GenericClassFactory.get(instantiated.getOwnerType());
                Class<?> declaringClass = instantiated.getMethod().getDeclaringClass();
                if (!ownerClass.isAssignableTo(declaringClass)) {
                    continue;
                }
                if (!((GenericMethod) call).getReturnType().equals(returnType)) {
                    continue;
                }
                dependencies = getDependencies((GenericMethod) call);
            } else if (call.isConstructor()) {
                dependencies = getDependencies((GenericConstructor) call);
            } else if (call.isField()) {
                if (!((GenericField) call).getFieldType().equals(returnType)) {
                    continue;
                }
                dependencies = getDependencies((GenericField) call);
            } else {
                assert (false);
            }
            if (dependenciesSatisfied(dependencies, objects)) {
                calls.add(call);
            }
        }

        // TODO: What if primitive?

        return calls;
    }
}
