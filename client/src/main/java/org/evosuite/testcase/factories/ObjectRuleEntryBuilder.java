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
 */
package org.evosuite.testcase.factories;

import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.StatementFactory;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.VariableResolutionConfig;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constructs and validates whole-class EvoObj entry receivers and invocations. */
class ObjectRuleEntryBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ObjectRuleEntryBuilder.class);
    private final Set<Class<?>> failedConcreteReceiverTypes = new LinkedHashSet<>();
    private long failureCacheEpoch = Long.MIN_VALUE;

    /**
     * Inserts a call to the whole-class entry method (the working branch's containing method) into
     * {@code test}, constructing its receiver and parameters via the normal factory path. Returns
     * whether the call is present afterward.
     */
    enum EntryInsertionResult {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    EntryInsertionResult insertEntryMethodCall(
            TestCase test, org.evosuite.testcase.synthesizer.TargetContext context,
            PartialGraph entryGraph) {
        synchronizeFailureCache();
        try {
            Class<?> clazz = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT()
                    .loadClass(context.getClassName());
            if (context.isConstructorEntry()) {
                for (java.lang.reflect.Constructor<?> c : clazz.getDeclaredConstructors()) {
                    if (("<init>" + org.evosuite.utils.MethodUtil.getSignature(c)).equals(context.getMethodSignature())) {
                        org.evosuite.utils.generic.GenericConstructor gConstructor =
                                new org.evosuite.utils.generic.GenericConstructor(c, clazz);
                        TestFactory.getInstance().addConstructor(test, gConstructor, test.size(), 0);
                        return findConstructorCallStatement(test, context.getClassName(),
                                context.getMethodSignature()) != null
                                ? EntryInsertionResult.SUCCESS : EntryInsertionResult.RETRYABLE_FAILURE;
                    }
                }
                return EntryInsertionResult.PERMANENT_FAILURE;
            }
            java.lang.reflect.Method entry = findEntryMethod(clazz, context.getMethodSignature());
            if (entry == null) {
                return EntryInsertionResult.PERMANENT_FAILURE;
            }
            GenericMethod gMethod = new GenericMethod(entry, clazz);
            if (java.lang.reflect.Modifier.isStatic(entry.getModifiers())) {
                insertStableEntryInvocation(test, gMethod, null, entry.getGenericParameterTypes());
            } else {
                Set<String> requiredFields = entryGraph == null
                        ? Collections.emptySet()
                        : entryGraph.getRequiredInstanceFieldDescriptors(clazz.getName());
                VariableReference receiver = constructEntryReceiver(test, clazz, requiredFields);
                if (receiver == null) {
                    return TestCluster.getInstance().hasGenerator(GenericClassFactory.get(clazz))
                            ? EntryInsertionResult.RETRYABLE_FAILURE
                            : EntryInsertionResult.PERMANENT_FAILURE;
                }
                GenericMethod boundEntry = gMethod.copyWithNewOwner(receiver.getGenericClass());
                insertStableEntryInvocation(
                        test, boundEntry, receiver, boundEntry.getParameterTypes());
            }
            return test.findMethodCallStatement(context.getClassName(),
                    context.getMethodSignature()) != null
                    ? EntryInsertionResult.SUCCESS : EntryInsertionResult.RETRYABLE_FAILURE;
        } catch (Throwable t) {
            logger.debug("Whole-class entry-method insertion failed for {}: {}",
                    context.getMethodSignature(), t.toString());
            return EntryInsertionResult.RETRYABLE_FAILURE;
        }
    }

    void insertStableEntryInvocation(TestCase test, GenericMethod method,
            VariableReference receiver, Type[] parameterTypes) throws Exception {
        if (method.hasUnboundTypeParameters()) {
            method = StatementFactory.bindTypeParameters(method,
                    receiver == null ? null : receiver.getGenericClass());
            parameterTypes = method.getParameterTypes();
        }
        List<VariableReference> parameters = new ArrayList<>();
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withAllowNull(false)
                .withCanUseMocks(true)
                .withCanReuseExistingVariables(false)
                .withPreferConcreteGenerators(true)
                .build();
        for (Type parameterType : parameterTypes) {
            GenericClass<?> parameterClass = GenericClassFactory.get(parameterType);
            if (parameterClass.isPrimitive() || parameterClass.isString()
                    || parameterClass.isEnum()) {
                // A stable entry must not recursively call another CUT method merely to obtain a
                // scalar argument. In CIDR this selected CIDR.ipToString(randomString), which

                // usually threw before the selected cidr(String) call and made the entry unusable
                // an artificial repair problem. Direct primitive statements are also the values
                // the normal GA knows how to mutate most effectively.
                PrimitiveStatement<?> primitive = stableEntryScalar(test, parameterClass);
                parameters.add(test.addStatement(primitive, test.size()));
            } else {
                parameters.add(TestFactory.getInstance().createObject(
                        test, parameterType, test.size(), GenerationContext.fromDepth(1), config));
            }
        }
        test.addStatement(new MethodStatement(test, method, receiver, parameters));
    }

    /**
     * Entry arguments are scaffolding, not a random baseline test. Small numeric values are much
     * less likely to make a guard vacuously false or trigger a precondition (negative maximum
     * counts were a recurring example), and remain ordinary mutable primitive slots. Strings and
     * enums retain their normal diversity.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PrimitiveStatement<?> stableEntryScalar(
            TestCase test, GenericClass<?> parameterClass) {
        Class<?> raw = parameterClass.getRawClass();
        if (!raw.isPrimitive() || raw == boolean.class || raw == char.class) {
            return PrimitiveStatement.getRandomStatement(test, parameterClass, test.size());
        }
        PrimitiveStatement primitive = PrimitiveStatement.getPrimitiveStatement(test, parameterClass);
        if (raw == int.class) {
            primitive.setValue(1);
        } else if (raw == long.class) {
            primitive.setValue(1L);
        } else if (raw == short.class) {
            primitive.setValue((short) 1);
        } else if (raw == byte.class) {
            primitive.setValue((byte) 1);
        } else if (raw == float.class) {
            primitive.setValue(1.0f);
        } else if (raw == double.class) {
            primitive.setValue(1.0d);
        }
        return primitive;
    }

    /**
     * Builds an entry receiver by trying every registered generator, preferring static factories.
     * This is deliberately exhaustive: choosing one random generator and failing does not mean a
     * private-constructor class is unconstructable when a factory such as {@code coalesce} exists.
     */
    VariableReference constructEntryReceiver(TestCase test, Class<?> receiverClass) {
        return constructEntryReceiver(test, receiverClass, Collections.emptySet());
    }

    VariableReference constructEntryReceiver(TestCase test, Class<?> receiverClass,
            Set<String> requiredFieldDescriptors) {
        synchronizeFailureCache();
        Type exactReceiverType = stableSynthesisReceiverType(receiverClass);
        GenericClass<?> receiverType = GenericClassFactory.get(exactReceiverType);
        LinkedHashSet<GenericAccessibleObject<?>> discoveredGenerators = new LinkedHashSet<>();
        try {
            discoveredGenerators.addAll(TestCluster.getInstance().getGenerators(receiverType));
        } catch (Throwable ignored) {
            // A conditional generic factory is cached under its erased return bound, not
            // necessarily under the requested subtype. Discover and instantiate those below.
        }
        for (Set<GenericAccessibleObject<?>> registered
                : TestCluster.getInstance().getGeneratorsByType().values()) {
            for (GenericAccessibleObject<?> candidate : registered) {
                if (!isSupportedConditionalCollectionFactory(candidate, receiverClass)) {
                    continue;
                }
                try {
                    discoveredGenerators.add(
                            candidate.getGenericInstantiationFromReturnValue(receiverType));
                } catch (Throwable failure) {
                    logger.trace("Could not instantiate conditional receiver factory {} for {}: {}",
                            candidate, receiverClass.getName(), failure.toString());
                }
            }
        }
        List<GenericAccessibleObject<?>> generators = new ArrayList<>(discoveredGenerators);
        generators.sort((left, right) -> {
            int leftCoverage = requiredConstructorCoverage(left, requiredFieldDescriptors);
            int rightCoverage = requiredConstructorCoverage(right, requiredFieldDescriptors);
            int fieldOrder = Integer.compare(rightCoverage, leftCoverage);
            return fieldOrder != 0 ? fieldOrder
                    : Integer.compare(generatorPriority(left), generatorPriority(right));
        });
        for (GenericAccessibleObject<?> generator : generators) {
            Class<?> generatedReceiverType =
                    concreteGeneratedReceiverType(generator, receiverClass);
            if (generatedReceiverType != null
                    && failedConcreteReceiverTypes.contains(generatedReceiverType)) {
                continue;
            }
            if (isSupportedConditionalCollectionFactory(generator, receiverClass)) {
                int originalSize = test.size();
                try {
                    VariableReference result = constructConditionalGenericReceiver(
                            test, (GenericMethod) generator, receiverClass);
                    if (result != null && result.isAssignableTo(receiverClass)
                            && producesRuntimeReceiver(test, result, receiverClass)) {
                        return result;
                    }
                } catch (Throwable t) {
                    logger.trace("Conditional receiver factory {} failed: {}", generator, t.toString());
                }
                rollbackToSize(test, originalSize);
                continue;
            }
            int originalSize = test.size();
            try {
                VariableReference result;
                if (generator instanceof GenericMethod) {
                    result = TestFactory.getInstance().addMethod(
                            test, (GenericMethod) generator, test.size(), new GenerationContext());
                } else if (generator instanceof GenericConstructor) {
                    GenericConstructor constructor = (GenericConstructor) generator;
                    if (requiredConstructorCoverage(constructor, requiredFieldDescriptors) > 0) {
                        result = constructReceiverWithRequiredCollaborators(
                                test, constructor, requiredFieldDescriptors);
                        if (!producesRuntimeReceiver(test, result, receiverClass)) {
                            rollbackToSize(test, originalSize);
                            result = TestFactory.getInstance().addConstructor(
                                    test, constructor, exactReceiverType, test.size(),
                                    new GenerationContext());
                        }
                    } else {
                        result = TestFactory.getInstance().addConstructor(
                                test, constructor, exactReceiverType, test.size(),
                                new GenerationContext());
                    }
                } else if (generator instanceof GenericField) {
                    result = TestFactory.getInstance().addField(
                            test, (GenericField) generator, test.size(), new GenerationContext());
                } else {
                    continue;
                }
                if (result != null && result.isAssignableTo(receiverClass)) {
                    /*
                     * Constructors used to be trusted solely from their static type. A generated
                     * concrete subtype can nevertheless throw during initialization under the SUT
                     * class loader, leaving every later entry call unreachable. Validate the
                     * prefix just like factory results, remember the failing concrete type, and
                     * continue through the ranked subtype portfolio.
                     */
                    if (producesRuntimeReceiver(test, result, receiverClass)) {
                        if (generatedReceiverType != null) {
                            failedConcreteReceiverTypes.remove(generatedReceiverType);
                        }
                        return result;
                    }
                    if (generatedReceiverType != null) {
                        failedConcreteReceiverTypes.add(generatedReceiverType);
                    }
                }
            } catch (Throwable t) {
                logger.trace("Entry receiver generator {} failed: {}", generator, t.toString());
            }
            rollbackToSize(test, originalSize);
        }
        return null;
    }

    private static Class<?> concreteGeneratedReceiverType(
            GenericAccessibleObject<?> generator, Class<?> requestedType) {
        Class<?> generated;
        try {
            generated = generator instanceof GenericConstructor
                    ? ((GenericConstructor) generator).getConstructor().getDeclaringClass()
                    : generator.getGeneratedClass().getRawClass();
        } catch (Throwable failure) {
            return null;
        }
        if (!requestedType.isAssignableFrom(generated)
                || generated.isInterface()
                || java.lang.reflect.Modifier.isAbstract(generated.getModifiers())) {
            return null;
        }
        return generated;
    }

    boolean isConcreteReceiverTypeNegativelyCached(Class<?> receiverType) {
        synchronizeFailureCache();
        return failedConcreteReceiverTypes.contains(receiverType);
    }

    /**
     * Gives an EvoObj entry receiver a cheap, deterministic instantiation for genuinely
     * unconstrained class type variables. The ordinary search deliberately asks {@link
     * org.evosuite.seeding.CastClassManager} for diverse assignments; doing that while
     * materializing a graph template can turn an irrelevant {@code T extends Object} into a deep
     * Jackson or SUT-specific construction which has no bearing on the selected branch.
     *
     * <p>Constrained variables are deliberately left unresolved so the existing generic-bound
     * machinery and CastClassManager still choose a valid implementation.
     */
    static Type stableSynthesisReceiverType(Class<?> receiverClass) {
        TypeVariable<?>[] variables = receiverClass.getTypeParameters();
        if (variables.length == 0) {
            return receiverClass;
        }
        Type[] arguments = new Type[variables.length];
        boolean changed = false;
        for (int i = 0; i < variables.length; i++) {
            Type[] bounds = variables[i].getBounds();
            if (bounds.length == 1 && bounds[0] == Object.class) {
                arguments[i] = String.class;
                changed = true;
            } else {
                arguments[i] = variables[i];
            }
        }
        if (!changed) {
            return receiverClass;
        }
        Type owner = receiverClass.getDeclaringClass() == null
                ? null : receiverClass.getDeclaringClass();
        return new ParameterizedTypeImpl(receiverClass, arguments, owner);
    }

    VariableReference constructReceiverWithRequiredCollaborators(TestCase test,
            GenericConstructor constructor, Set<String> requiredFieldDescriptors)
            throws Exception {
        List<VariableReference> parameters = new ArrayList<>();
        Type[] parameterTypes = constructor.getConstructor().getGenericParameterTypes();
        Class<?>[] erasedTypes = constructor.getConstructor().getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            String descriptor = org.objectweb.asm.Type.getDescriptor(erasedTypes[i]);
            boolean required = requiredFieldDescriptors.contains(descriptor);
            VariableReference parameter = null;
            if (required && erasedTypes[i].isArray()) {
                // A graph-required constructor array must be the array subsequently populated by
                // synthesis. Going through the normal generator pool can instead obtain it from a
                // SUT factory/getter, pulling a large and failure-prone dependency chain in front
                // of the target call. One element is sufficient initially; array-element
                // synthesis resizes it if the selected access requires a larger index.
                parameter = test.addStatement(new ArrayStatement(test, parameterTypes[i], 1));
            }
            // Required collaborators are useful only when the object rule can control them.  Make
            // that choice deterministic for mock-setup experiments; ordinary generation and
            // non-required constructor parameters retain their existing policy.
            if (parameter == null
                    && required
                    && !TestCluster.getInstance().hasGenerator(parameterTypes[i])
                    && FunctionalMockStatement.canBeFunctionalMocked(parameterTypes[i])) {
                parameter = TestFactory.getInstance().addFunctionalMock(
                        test, parameterTypes[i], test.size(), GenerationContext.fromDepth(2));
            }
            if (parameter == null && !required && !erasedTypes[i].isPrimitive()) {
                // This constructor was selected specifically because it exposes graph-required
                // state. Keep unrelated reference collaborators out of the speculative prefix.
                // constructEntryReceiver executes this sparse prefix and falls back to ordinary
                // argument generation when the constructor does not accept null.
                parameter = test.addStatement(new NullStatement(test, parameterTypes[i]));
            }
            if (parameter == null) {
                VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                        .withAllowNull(false)
                        .withCanUseMocks(true)
                        .withCanReuseExistingVariables(false)
                        .withPreferConcreteGenerators(true)
                        .build();
                parameter = TestFactory.getInstance().createObject(test, parameterTypes[i],
                        test.size(), GenerationContext.fromDepth(1), config);
            }
            parameters.add(parameter);
        }
        return test.addStatement(new ConstructorStatement(test, constructor, parameters));
    }

    private static int requiredConstructorCoverage(GenericAccessibleObject<?> generator,
            Set<String> requiredFieldDescriptors) {
        if (!(generator instanceof GenericConstructor) || requiredFieldDescriptors == null
                || requiredFieldDescriptors.isEmpty()) {
            return 0;
        }
        Set<String> remaining = new LinkedHashSet<>(requiredFieldDescriptors);
        int matched = 0;
        for (Class<?> parameter : ((GenericConstructor) generator).getConstructor().getParameterTypes()) {
            if (remaining.remove(org.objectweb.asm.Type.getDescriptor(parameter))) {
                matched++;
            }
        }
        return matched;
    }

    /**
     * Realizes the common conditional-factory contract {@code T factory(Iterable<E>)} with two
     * independently generated elements. This is sufficient for coalescing/folding factories whose
     * narrow subtype exists only for a non-singleton input, while retaining ordinary generation as
     * the fallback if either element cannot be built.
     */
    private VariableReference constructConditionalGenericReceiver(
            TestCase test, GenericMethod factory, Class<?> receiverClass) throws Exception {
        java.lang.reflect.Method method = factory.getMethod();
        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != 1 || !(parameterTypes[0] instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType iterableType = (ParameterizedType) parameterTypes[0];
        if (!(iterableType.getRawType() instanceof Class<?>)
                || !Iterable.class.isAssignableFrom((Class<?>) iterableType.getRawType())) {
            return null;
        }
        Type[] arguments = iterableType.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        Type elementType = concreteUpperBound(arguments[0]);
        Type setType = new ParameterizedTypeImpl(
                LinkedHashSet.class, new Type[]{elementType}, null);
        GenericConstructor setConstructor = new GenericConstructor(
                LinkedHashSet.class.getConstructor(), setType);
        TestFactory testFactory = TestFactory.getInstance();
        VariableReference set = testFactory.addConstructor(
                test, setConstructor, setType, test.size(), new GenerationContext());

        java.lang.reflect.Method addMethod = java.util.Set.class.getMethod("add", Object.class);
        GenericMethod add = new GenericMethod(addMethod, setType)
                .copyWithNewOwner(set.getGenericClass());
        for (int i = 0; i < 2; i++) {
            VariableReference element = constructConcreteFactoryElement(
                    test, elementType, factory);
            if (element == null) {
                return null;
            }
            test.addStatement(new MethodStatement(
                    test, add, set, Collections.singletonList(element)));
        }

        MethodStatement factoryStatement = new MethodStatement(
                test, factory, null, Collections.singletonList(set));
        VariableReference result = test.addStatement(factoryStatement);
        return result != null && result.isAssignableTo(receiverClass) ? result : null;
    }

    /**
     * Constructs one element for a conditional collection factory from a generator whose declared
     * result is already a concrete subtype. Going through {@link TestFactory#createObject} for the
     * abstract element type can select the conditional factory itself again, or infer a narrow
     * generic return type backed by a mocked {@code Iterable}. Either choice recreates the receiver
     * we are trying to build and never supplies the two concrete elements required by factories
     * such as {@code CompoundByteTransition.coalesce}.
     */
    private VariableReference constructConcreteFactoryElement(
            TestCase test, Type elementType, GenericMethod enclosingFactory) {
        Class<?> elementClass;
        try {
            elementClass = GenericClassFactory.get(elementType).getRawClass();
        } catch (Throwable failure) {
            return null;
        }

        LinkedHashSet<GenericAccessibleObject<?>> candidates = new LinkedHashSet<>();
        try {
            candidates.addAll(TestCluster.getInstance().getGenerators(
                    GenericClassFactory.get(elementType)));
        } catch (Throwable ignored) {
            // The abstract type itself need not have a cache entry. Concrete subtype entries below
            // are sufficient and are more reliable for this use case.
        }
        for (Map.Entry<GenericClass<?>, Set<GenericAccessibleObject<?>>> entry
                : TestCluster.getInstance().getGeneratorsByType().entrySet()) {
            Class<?> generatedClass;
            try {
                generatedClass = entry.getKey().getRawClass();
            } catch (Throwable failure) {
                continue;
            }
            if (elementClass.isAssignableFrom(generatedClass)
                    && !generatedClass.isInterface()
                    && !java.lang.reflect.Modifier.isAbstract(generatedClass.getModifiers())) {
                candidates.addAll(entry.getValue());
            }
        }

        List<GenericAccessibleObject<?>> ordered = new ArrayList<>(candidates);
        ordered.removeIf(candidate -> isSameMethod(candidate, enclosingFactory)
                || !hasConcreteGeneratedSubtype(candidate, elementClass));
        ordered.sort((left, right) -> {
            int priority = Integer.compare(factoryElementPriority(left), factoryElementPriority(right));
            return priority != 0
                    ? priority : Integer.compare(left.getNumParameters(), right.getNumParameters());
        });

        for (GenericAccessibleObject<?> candidate : ordered) {
            int originalSize = test.size();
            try {
                VariableReference result;
                if (candidate instanceof GenericConstructor) {
                    result = TestFactory.getInstance().addConstructor(
                            test, (GenericConstructor) candidate, candidate.getGeneratedType(),
                            test.size(), GenerationContext.fromDepth(1));
                } else if (candidate instanceof GenericMethod) {
                    result = TestFactory.getInstance().addMethod(
                            test, (GenericMethod) candidate, test.size(),
                            GenerationContext.fromDepth(1));
                } else if (candidate instanceof GenericField) {
                    result = TestFactory.getInstance().addField(
                            test, (GenericField) candidate, test.size(),
                            GenerationContext.fromDepth(1));
                } else {
                    continue;
                }
                if (result != null && result.isAssignableTo(elementType)) {
                    return result;
                }
            } catch (Throwable failure) {
                logger.trace("Concrete conditional-factory element generator {} failed: {}",
                        candidate, failure.toString());
            }
            rollbackToSize(test, originalSize);
        }
        return null;
    }

    private static boolean isSameMethod(
            GenericAccessibleObject<?> candidate, GenericMethod method) {
        return candidate instanceof GenericMethod
                && ((GenericMethod) candidate).getMethod().equals(method.getMethod());
    }

    private static boolean hasConcreteGeneratedSubtype(
            GenericAccessibleObject<?> candidate, Class<?> requestedType) {
        try {
            Class<?> generated = candidate.getGeneratedClass().getRawClass();
            return requestedType.isAssignableFrom(generated)
                    && !generated.isInterface()
                    && !java.lang.reflect.Modifier.isAbstract(generated.getModifiers());
        } catch (Throwable failure) {
            return false;
        }
    }

    private static int factoryElementPriority(GenericAccessibleObject<?> generator) {
        if (generator instanceof GenericConstructor) {
            return 0;
        }
        if (generator instanceof GenericMethod && generator.isStatic()) {
            return 1;
        }
        if (generator instanceof GenericField && generator.isStatic()) {
            return 2;
        }
        return 3;
    }

    /**
     * Execute only the receiver prefix and verify the value promised by its static generic type.
     */
    static boolean producesRuntimeReceiver(
            TestCase test, VariableReference receiver, Class<?> receiverClass) {
        if (test == null || receiver == null || receiverClass == null) {
            return false;
        }
        int receiverPosition = receiver.getStPosition();
        if (receiverPosition < 0 || receiverPosition >= test.size()) {
            return false;
        }
        try {
            TestCase prefix = test.clone();
            prefix.chop(receiverPosition + 1);
            ExecutionResult result = TestCaseExecutor.getInstance().execute(prefix);
            if (result.hasTimeout() || result.getFirstPositionOfThrownException() != null
                    || result.getFinalScope() == null) {
                return false;
            }
            VariableReference clonedReceiver =
                    prefix.getStatement(receiverPosition).getReturnValue();
            Object runtimeReceiver = result.getFinalScope().getObject(clonedReceiver);
            return receiverClass.isInstance(runtimeReceiver);
        } catch (Throwable failure) {
            logger.trace("Runtime validation of entry receiver failed: {}", failure.toString());
            return false;
        }
    }

    private static Type concreteUpperBound(Type type) {
        if (type instanceof WildcardType) {
            Type[] bounds = ((WildcardType) type).getUpperBounds();
            return bounds.length == 0 ? Object.class : bounds[0];
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : bounds[0];
        }
        return type;
    }

    /**
     * A factory such as {@code <T extends ByteTransition> T coalesce(Iterable<...>)} can be
     * instantiated statically as any subtype even though its runtime result depends on its input.
     * Directly trusting that inferred subtype creates a receiver that may fail before the entry
     * call. Such factories require explicit construction of a subtype-producing input shape.
     */
    static boolean isUnsafeConditionalGenericFactory(
            GenericAccessibleObject<?> generator, Class<?> receiverClass) {
        if (!(generator instanceof GenericMethod) || !generator.isStatic()) {
            return false;
        }
        java.lang.reflect.Method method = ((GenericMethod) generator).getMethod();
        if (!(method.getGenericReturnType() instanceof TypeVariable<?>)) {
            return false;
        }
        Class<?> erasedReturn = method.getReturnType();
        return !erasedReturn.equals(receiverClass) && erasedReturn.isAssignableFrom(receiverClass);
    }

    /** Whether the conditional factory has the collection shape this factory can realize safely. */
    private static boolean isSupportedConditionalCollectionFactory(
            GenericAccessibleObject<?> generator, Class<?> receiverClass) {
        if (!isUnsafeConditionalGenericFactory(generator, receiverClass)) {
            return false;
        }
        Type[] parameters = ((GenericMethod) generator).getMethod().getGenericParameterTypes();
        if (parameters.length != 1 || !(parameters[0] instanceof ParameterizedType)) {
            return false;
        }
        Type rawType = ((ParameterizedType) parameters[0]).getRawType();
        return rawType instanceof Class<?> && Iterable.class.isAssignableFrom((Class<?>) rawType);
    }

    private static int generatorPriority(GenericAccessibleObject<?> generator) {
        if (generator instanceof GenericMethod && generator.isStatic()) {
            return 0;
        }
        if (generator instanceof GenericConstructor) {
            return 1;
        }
        if (generator instanceof GenericField && generator.isStatic()) {
            return 2;
        }
        return 3;
    }

    /** Attach the selected entry to a receiver that ordinary random generation already produced. */
    boolean insertEntryMethodCallOnExistingReceiver(
            TestCase test, org.evosuite.testcase.synthesizer.TargetContext context) {
        synchronizeFailureCache();
        if (context.isConstructorEntry()) {
            return findConstructorCallStatement(test, context.getClassName(),
                    context.getMethodSignature()) != null;
        }
        if (test.findMethodCallStatement(context.getClassName(), context.getMethodSignature()) != null) {
            return true;
        }
        try {
            Class<?> clazz = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT()
                    .loadClass(context.getClassName());
            java.lang.reflect.Method entry = findEntryMethod(clazz, context.getMethodSignature());
            if (entry == null || java.lang.reflect.Modifier.isStatic(entry.getModifiers())) {
                return false;
            }
            List<VariableReference> receivers = test.getObjects(clazz, test.size());
            for (int i = receivers.size() - 1; i >= 0; i--) {
                VariableReference receiver = receivers.get(i);
                Statement definingStatement = test.getStatement(receiver.getStPosition());
                if (definingStatement instanceof NullStatement) {
                    continue;
                }
                Class<?> concreteType = concreteExistingReceiverType(
                        definingStatement, receiver, clazz);
                if (concreteType != null
                        && failedConcreteReceiverTypes.contains(concreteType)) {
                    continue;
                }
                /*
                 * A receiver's static type is not evidence that its prefix executes. Constructors
                 * can fail just as factories can return null or throw (XMLBeans-backed METS
                 * profiles are a real example). Validate every reused receiver before attaching
                 * an entry call, and remember concrete failures for the rest of this run.
                 */
                if (!producesRuntimeReceiver(test, receiver, clazz)) {
                    if (concreteType != null) {
                        failedConcreteReceiverTypes.add(concreteType);
                    }
                    continue;
                }
                if (concreteType != null) {
                    failedConcreteReceiverTypes.remove(concreteType);
                }
                GenericMethod method = new GenericMethod(entry, clazz)
                        .copyWithNewOwner(receiver.getGenericClass());
                TestFactory.getInstance().addMethodFor(
                        test, receiver, method, test.size(), new GenerationContext());
                return test.findMethodCallStatement(context.getClassName(),
                        context.getMethodSignature()) != null;
            }
        } catch (Throwable t) {
            logger.debug("Could not reuse an existing receiver for {}: {}",
                    context.getMethodSignature(), t.toString());
        }
        return false;
    }

    private static Class<?> concreteExistingReceiverType(
            Statement definingStatement, VariableReference receiver, Class<?> requestedType) {
        Class<?> type;
        if (definingStatement instanceof ConstructorStatement) {
            type = ((ConstructorStatement) definingStatement)
                    .getConstructor().getDeclaringClass();
        } else {
            type = receiver.getVariableClass();
        }
        return requestedType.isAssignableFrom(type)
                && !type.isInterface()
                && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                ? type : null;
    }

    private static java.lang.reflect.Method findEntryMethod(Class<?> clazz, String signature) {
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if ((method.getName() + org.evosuite.utils.MethodUtil.getSignature(method)).equals(signature)) {
                return method;
            }
        }
        return null;
    }

    private static void rollbackToSize(TestCase test, int size) {
        while (test.size() > size) {
            test.remove(test.size() - 1);
        }
    }

    static ConstructorStatement findConstructorCallStatement(TestCase test, String constructorSignature) {
        return findConstructorCallStatement(test, null, constructorSignature);
    }

    static ConstructorStatement findConstructorCallStatement(
            TestCase test, String className, String constructorSignature) {
        if (test == null || constructorSignature == null || constructorSignature.isEmpty()) {
            return null;
        }
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) statement;
                if (constructorStatement.getConstructor().getNameWithDescriptor().equals(constructorSignature)
                        && (className == null || className.equals(constructorStatement.getConstructor()
                                .getDeclaringClass().getName()))) {
                    return constructorStatement;
                }
            }
        }
        return null;
    }

    /** Drop receiver failures retained by a factory when its owning run was reset. */
    private void synchronizeFailureCache() {
        long currentEpoch = org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getResetEpoch();
        if (failureCacheEpoch != currentEpoch) {
            failedConcreteReceiverTypes.clear();
            failureCacheEpoch = currentEpoch;
        }
    }

}
