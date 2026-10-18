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
package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;

/**
 * Populates containers with the smallest state a query needs. The synthesizer asks a container
 * to hold an entry when the target reads its size, iterates it or tests membership; the entry is
 * built without invoking the recursive generic object generator, which previously produced large
 * SQL/RMI/JDK object trees and frequent pre-target exceptions. Which observation calls for which
 * entry stays with the variable wrappers.
 */
final class ContainerSetup {

    /** The literal that stands for a map key. */
    static final String KEY = "key";

    /** The literal that stands for a map value, a collection element or a string argument. */
    static final String VALUE = "value";

    private ContainerSetup() {
    }

    /**
     * The instance methods of {@code type} that a generated test can call, take between {@code
     * minArity} and {@code maxArity} parameters and whose name {@code name} accepts: the public
     * methods including inherited ones, and the methods {@code type} declares itself that are
     * visible from the package the tests are generated into. Fewest parameters first, then by name
     * and signature, so that the choice among them does not depend on reflection order.
     */
    static List<Method> findMutators(
            Class<?> type, Predicate<String> name, int minArity, int maxArity) {
        Set<Method> candidates = new LinkedHashSet<>(Arrays.asList(type.getMethods()));
        candidates.addAll(Arrays.asList(type.getDeclaredMethods()));
        List<Method> mutators = new ArrayList<>();
        for (Method candidate : candidates) {
            if (Modifier.isStatic(candidate.getModifiers())
                    || candidate.getParameterCount() < minArity
                    || candidate.getParameterCount() > maxArity
                    || !name.test(candidate.getName())
                    || !TestUsageChecker.isSynthesisCallable(candidate)) {
                continue;
            }
            mutators.add(candidate);
        }
        mutators.sort(
                Comparator.comparingInt(Method::getParameterCount)
                        .thenComparing(Method::getName)
                        .thenComparing(Method::toGenericString));
        return mutators;
    }

    /** The position just before the target call, or the end of a test that has none. */
    static int insertionPosition(TestCase test) {
        EntityWithParametersStatement targetCall = test.findTargetExecutableStatement();
        return targetCall == null ? test.size() : targetCall.getPosition();
    }

    /**
     * Places a replayed observation after every graph value it consumes. Graph nodes are resolved
     * incrementally, so a field used as an argument may have been inserted after the receiver. The
     * old receiver-adjacent placement followed by parameter replacement created a forward reference
     * (for example {@code type.equals(UNKNOW)} before the {@code UNKNOW} field read). A crossover
     * cut between those statements then left the method callee or argument permanently orphaned.
     * Prefer the position immediately before the target call, while also handling callers without a
     * marked target and any unusual dependency that is already later in the test.
     */
    static int insertionPosition(
            TestCase test, VariableReference receiver, Iterable<VariableReference> parameters) {
        int afterDependencies = 0;
        if (receiver != null) {
            afterDependencies = receiver.getStPosition() + 1;
        }
        if (parameters != null) {
            for (VariableReference parameter : parameters) {
                if (parameter != null) {
                    afterDependencies = Math.max(afterDependencies, parameter.getStPosition() + 1);
                }
            }
        }
        return Math.min(test.size(), Math.max(afterDependencies, insertionPosition(test)));
    }

    /** A concrete class that can stand in for an abstract collection or map contract. */
    static Class<?> concreteContainerClass(Class<?> requestedClass) {
        if (requestedClass == null
                || (!requestedClass.isInterface()
                        && !Modifier.isAbstract(requestedClass.getModifiers()))) {
            return requestedClass;
        }
        if (java.util.Map.class.isAssignableFrom(requestedClass)) {
            return java.util.LinkedHashMap.class;
        }
        if (java.util.Set.class.isAssignableFrom(requestedClass)) {
            return java.util.LinkedHashSet.class;
        }
        if (java.util.Collection.class.isAssignableFrom(requestedClass)
                || requestedClass == Iterable.class) {
            return java.util.ArrayList.class;
        }
        return requestedClass;
    }

    static VariableReference stringLiteral(TestCase test, String value, int position) {
        return test.addStatement(new StringPrimitiveStatement(test, value), position);
    }

    /**
     * Build the smallest non-empty container without invoking the recursive generic object
     * generator. Query evidence such as size/entrySet/iterator only requires cardinality; asking
     * TestFactory for arbitrary Object values previously produced large SQL/RMI/JDK object trees
     * and frequent pre-target exceptions.
     */
    static VariableReference addEntry(
            TestCase test, VariableReference container, GenericMethod mutator, int position)
            throws Exception {
        List<VariableReference> arguments = new ArrayList<>();
        if (java.util.Map.class.isAssignableFrom(container.getVariableClass())) {
            arguments.add(stringLiteral(test, KEY, position++));
            arguments.add(containerValue(test, container, true, position++));
        } else {
            arguments.add(containerValue(test, container, false, position++));
        }
        return test.addStatement(
                new MethodStatement(
                        test, mutator.copyWithNewOwner(container.getGenericClass()), container,
                        arguments),
                position);
    }

    static VariableReference containerValue(
            TestCase test, VariableReference container, boolean mapValue, int position)
            throws Exception {
        List<GenericClass<?>> parameters = container.getGenericClass().getParameterClasses();
        int genericIndex = mapValue ? 1 : 0;
        GenericClass<?> requestedType =
                genericIndex < parameters.size()
                        ? parameters.get(genericIndex)
                        : GenericClassFactory.get(Object.class);
        return valueOrNull(test, requestedType, true, position);
    }

    /**
     * A value of the given type that needs no search, or a typed null when there is none. A typed
     * null still establishes cardinality and is safer than recursively generating an arbitrary
     * graph.
     */
    private static VariableReference valueOrNull(
            TestCase test, GenericClass<?> type, boolean withElement, int position)
            throws Exception {
        VariableReference value = value(test, type, withElement, position);
        return value != null
                ? value
                : test.addStatement(new NullStatement(test, type.getRawClass()), position);
    }

    /**
     * A value of the given type that needs no search: the {@code "value"} literal for strings and
     * objects, an empty collection or map, an instance created by a no-argument constructor, or
     * the first static no-argument factory by signature. Null when the type offers none of these.
     *
     * @param withElement whether a collection also receives one element of its type argument
     */
    static VariableReference value(
            TestCase test, GenericClass<?> type, boolean withElement, int position)
            throws Exception {
        Class<?> raw = type.getRawClass();
        if (raw == null || raw == Object.class || raw == String.class) {
            return stringLiteral(test, VALUE, position);
        }
        boolean collection = java.util.Collection.class.isAssignableFrom(raw) || raw == Iterable.class;
        if (collection || java.util.Map.class.isAssignableFrom(raw)) {
            VariableReference container = newInstance(test, concreteContainerClass(raw), position);
            List<GenericClass<?>> elementTypes = type.getParameterClasses();
            if (container != null && collection && withElement && !elementTypes.isEmpty()) {
                VariableReference element = value(test, elementTypes.get(0), true, position + 1);
                if (element != null) {
                    Method add = java.util.Collection.class.getMethod("add", Object.class);
                    test.addStatement(
                            new MethodStatement(
                                    test,
                                    new GenericMethod(add, container.getGenericClass()),
                                    container,
                                    Collections.singletonList(element)),
                            element.getStPosition() + 1);
                }
            }
            return container;
        }
        VariableReference instance = newInstance(test, raw, position);
        return instance != null ? instance : staticFactoryResult(test, raw, position);
    }

    /** An instance of {@code type} created by a no-argument constructor a test can call. */
    private static VariableReference newInstance(TestCase test, Class<?> type, int position) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0
                    && TestUsageChecker.isSynthesisCallable(constructor)) {
                return test.addStatement(
                        new ConstructorStatement(
                                test,
                                new GenericConstructor(constructor, type),
                                Collections.emptyList()),
                        position);
            }
        }
        return null;
    }

    /** The result of the first public static no-argument method of {@code type} that returns it. */
    private static VariableReference staticFactoryResult(
            TestCase test, Class<?> type, int position) {
        List<Method> factories = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && type.isAssignableFrom(method.getReturnType())) {
                factories.add(method);
            }
        }
        factories.sort((left, right) -> left.toGenericString().compareTo(right.toGenericString()));
        if (factories.isEmpty()) {
            return null;
        }
        Method factory = factories.get(0);
        return test.addStatement(
                new MethodStatement(
                        test,
                        new GenericMethod(factory, factory.getDeclaringClass()),
                        null,
                        Collections.emptyList()),
                position);
    }

    /** Make the key of an existing {@code put} a string literal inserted just before the call. */
    static VariableReference ensureStringMapKey(TestCase test, VariableReference mutationResult)
            throws ConstructionFailedException {
        MethodStatement mutation =
                (MethodStatement) test.getStatement(mutationResult.getStPosition());
        if (mutation.getParameterReferences().isEmpty()) {
            return null;
        }
        VariableReference oldKey = mutation.getParameterReferences().get(0);
        if (oldKey != null && oldKey.getVariableClass() == String.class) {
            return oldKey;
        }
        // Strings are primitive statements in EvoSuite and are intentionally absent from the
        // TestCluster generator registry. Going through createObject() therefore fails with
        // "Have no generator for class java.lang.String" and leaves a semantically useless
        // arbitrary Object key behind. Insert a stable literal immediately before put instead.
        VariableReference stringKey = stringLiteral(test, KEY, mutation.getPosition());
        mutation.replace(oldKey, stringKey);
        return stringKey;
    }

    /** An argument for a mutator parameter, or null when none can be built without search. */
    static VariableReference mutationArgument(
            TestCase test, VariableReference receiver, Class<?> parameter, int position)
            throws Exception {
        if (parameter == String.class) {
            return stringLiteral(test, VALUE, position);
        }
        if (parameter.isPrimitive() || parameter.isEnum()) {
            PrimitiveStatement<?> primitive =
                    PrimitiveStatement.getPrimitiveStatement(
                            test, GenericClassFactory.get(parameter));
            if (parameter == byte.class) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                PrimitiveStatement raw = primitive;
                raw.setValue((byte) 1);
            }
            return test.addStatement(primitive, position);
        }
        Class<?> concrete =
                parameter.isAssignableFrom(receiver.getVariableClass())
                        ? receiver.getVariableClass()
                        : parameter;
        return newInstance(test, concrete, position);
    }

    /**
     * The value stored under an observed map key, of the type the target casts the lookup to when
     * that is known.
     */
    static VariableReference observedMapValue(TestCase test, Class<?> inferred, int position)
            throws Exception {
        return inferred == null
                ? stringLiteral(test, VALUE, position)
                : valueOrNull(test, GenericClassFactory.get(inferred), false, position);
    }
}
