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
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.environment.EnvironmentStatements;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.reflect.TypeUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resolving and creating variables during test case generation.
 *
 * @author Gordon Fraser
 */
public class VariableResolver {

    /**
     * Mock probability given to a deep interface or abstract dependency that is not a
     * functional interface, independent of the generators known for it.
     */
    private static final double DEEP_MOCK_BOOST = 0.5;

    /**
     * The same, once the search has already decided to build the enclosing object
     * concretely; see the comment in {@code createObject}.
     */
    private static final double CHAIN_MOCK_BOOST = 0.25;

    private static final Logger logger = LoggerFactory.getLogger(VariableResolver.class);

    private final TestFactory testFactory;

    public VariableResolver(TestFactory testFactory) {
        this.testFactory = testFactory;
    }

    /**
     * Resolves a variable of given type, either by reuse or creation.
     */
    public VariableReference resolveVariable(TestCase test, Type parameterType,
                                             int position, GenerationContext context,
                                             VariableResolutionConfig config)
            throws ConstructionFailedException {
        Type normalizedParameterType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(parameterType);
        VariableReference ref = resolveVariableInternal(test, parameterType, position, context, config);
        if (!ref.isAssignableTo(normalizedParameterType)) {
            String message = ref + " cannot be assigned to " + normalizedParameterType;
            testFactory.logCannotAssignDiagnostic(
                    message, test, position, normalizedParameterType, ref);
            throw new ConstructionFailedException(message);
        }
        return ref;
    }

    /**
     * Resolves a variable internally.
     *
     * @param test the test case
     * @param parameterType the parameter type
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference resolveVariableInternal(TestCase test, Type parameterType,
                                                      int position, GenerationContext context,
                                                      VariableResolutionConfig config)
            throws ConstructionFailedException {

        /*
         * SEED_TYPES deliberately treats Object as a request to explore observed casts. That is
         * useful during ordinary search, but exactly the wrong policy for an unconstrained
         * graph-to-code argument: it turns one opaque value into an arbitrary classpath graph
         * before createObject() can apply the synthesis scalar rule.
         */
        if (TestGenerationContext.getInstance().getObjectRuleRuntime().isSynthesisConstruction()
                && GenericClassFactory.get(parameterType).getRawClass() == Object.class) {
            return createObject(test, Object.class, position, context,
                    new VariableResolutionConfig.Builder(config)
                            .withCanReuseExistingVariables(false)
                            .build());
        }

        if (Properties.SEED_TYPES && parameterType.equals(Object.class)) {
            return resolveObjectVariable(test, position, context, config);
        }

        double reuse = Randomness.nextDouble();

        List<VariableReference> objects = getCandidatesForReuse(test, parameterType, position, config);

        GenericClass<?> clazz = GenericClassFactory.get(parameterType);
        boolean isPrimitiveOrSimilar = clazz.isPrimitive() || clazz.isWrapperType() || clazz.isEnum()
                || clazz.isClass() || clazz.isString();

        if (isPrimitiveOrSimilar && !objects.isEmpty() && reuse <= Properties.PRIMITIVE_REUSE_PROBABILITY) {
            logger.trace(" Looking for existing object of type {}", parameterType);
            return Randomness.choice(objects);

        } else if (!isPrimitiveOrSimilar && !objects.isEmpty() && (reuse <= Properties.OBJECT_REUSE_PROBABILITY)) {

            if (logger.isDebugEnabled()) {
                logger.trace(" Choosing from {} existing objects: {}", objects.size(),
                        Arrays.toString(objects.toArray()));
            }
            return Randomness.choice(objects);
        }

        // if chosen to not re-use existing variable, try create a new one
        VariableResolutionConfig createConfig = new VariableResolutionConfig.Builder(config)
                .withCanReuseExistingVariables(true)
                .build();
        VariableReference created = createVariable(test, parameterType, position, context, createConfig);
        if (created != null) {
            return created;
        }

        // could not create, so go back in trying to re-use an existing variable
        if (objects.isEmpty()) {
            if (config.isAllowNull()) {
                return testFactory.createNull(test, parameterType, position, context);
            } else {
                throw new ConstructionFailedException("No objects and generators for type " + parameterType);
            }
        }

        if (logger.isDebugEnabled()) {
            logger.trace(" Choosing from {} existing objects: {}", objects.size(),
                    Arrays.toString(objects.toArray()));
        }
        VariableReference reference = Randomness.choice(objects);
        assert config.isCanUseMocks()
                || !(test.getStatement(reference.getStPosition()) instanceof FunctionalMockStatement);
        logger.trace(" Using existing object of type {}: {}", parameterType, reference);
        return reference;
    }

    private List<VariableReference> getCandidatesForReuse(TestCase test, Type parameterType, int position,
                                                          VariableResolutionConfig config) {

        // look at all vars defined before pos
        List<VariableReference> objects = test.getObjects(parameterType, position);

        VariableReference exclude = config.getExcludeVar();
        boolean allowNull = config.isAllowNull();
        boolean canUseMocks = config.isCanUseMocks();

        // if an exclude var was specified, then remove it
        if (exclude != null) {
            objects.remove(exclude);
            if (exclude.getAdditionalVariableReference() != null) {
                objects.remove(exclude.getAdditionalVariableReference());
            }

            objects.removeIf(v -> exclude.equals(v.getAdditionalVariableReference()));
        }

        Set<VariableReference> toRemove = new HashSet<>();

        for (VariableReference ref : objects) {
            Statement statement = test.getStatement(ref.getStPosition());

            // no mock should be used more than once
            if (statement instanceof FunctionalMockStatement) {
                if (!canUseMocks) {
                    toRemove.add(ref);
                    continue;
                }
                // check if current mock var is used anywhere: if so, then we cannot choose it
                for (int i = ref.getStPosition() + 1; i < test.size(); i++) {
                    if (test.getStatement(i).getVariableReferences().contains(ref)) {
                        toRemove.add(ref);
                        break;
                    }
                }
            }

            // check for null
            if (!allowNull && ConstraintHelper.isNull(ref, test)) {
                toRemove.add(ref);
            }
        }

        // further remove all other vars that have the deleted ones as additionals
        objects.removeIf(ref -> toRemove.contains(ref) || toRemove.contains(ref.getAdditionalVariableReference()));

        // avoid using characters as values for numeric types arguments
        String parCls = parameterType.getTypeName();
        if (Integer.TYPE.getTypeName().equals(parCls) || Long.TYPE.getTypeName().equals(parCls)
                || Float.TYPE.getTypeName().equals(parCls) || Double.TYPE.getTypeName().equals(parCls)) {
            objects.removeIf(ref -> Character.TYPE.getTypeName().equals(ref.getType().getTypeName()));
        }

        // final safety filter: ensure type compatibility is consistent
        objects.removeIf(ref -> !ref.isAssignableTo(parameterType));

        return objects;
    }

    /**
     * Resolves an object variable.
     *
     * @param test the test case
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference resolveObjectVariable(TestCase test, int position,
                                                    GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {
        final boolean reuse = Randomness.nextDouble() <= Properties.PRIMITIVE_REUSE_PROBABILITY;
        if (reuse) { // Only reuse objects if they are related to a target call
            List<VariableReference> candidates = getCandidatesForReuse(test, Object.class, position, config);
            filterVariablesByCastClasses(candidates);
            logger.trace("Choosing object from: {}", candidates);
            if (!candidates.isEmpty()) {
                return Randomness.choice(candidates);
            }
        }
        logger.trace("Attempting object generation");

        return attemptObjectGeneration(test, position, context, config.isAllowNull());
    }

    /**
     * Creates a variable.
     *
     * @param test the test case
     * @param parameterType the parameter type
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference createVariable(TestCase test, Type parameterType,
                                             int position, GenerationContext context,
                                             VariableResolutionConfig config)
            throws ConstructionFailedException {

        final Type expectedType = testFactory.normalizeTypeVariablesToWildcardsIfNeeded(parameterType);
        GenericClass<?> clazz = GenericClassFactory.get(parameterType);

        if (clazz.hasWildcardOrTypeVariables()) {
            logger.trace("Getting generic instantiation of {}", clazz);
            if (config.getExcludeVar() != null) {
                clazz = clazz.getGenericInstantiation(config.getExcludeVar().getGenericClass().getTypeVariableMap());
            } else {
                clazz = clazz.getGenericInstantiation();
            }
            parameterType = clazz.getType();
        }

        if (clazz.isEnum() || clazz.isPrimitive() || clazz.isWrapperType() || clazz.isObject()
                || clazz.isClass() || EnvironmentStatements.isEnvironmentData(clazz.getRawClass())
                || clazz.isString() || clazz.isArray() || TestCluster.getInstance().hasGenerator(parameterType)
                || Properties.P_FUNCTIONAL_MOCKING > 0 || Properties.MOCK_IF_NO_GENERATOR) {

            logger.trace(" Generating new object of type {}", parameterType);

            VariableReference reference = attemptGeneration(test, parameterType, position, context, config);

            if (!reference.isAssignableTo(expectedType)
                    && !testFactory.isClassLiteralAssignableByErasure(expectedType, reference.getType())) {
                throw new ConstructionFailedException("Generated variable " + reference + " of type "
                        + reference.getType() + " is not assignable to expected type " + expectedType);
            }

            if (!config.isAllowNull() && ConstraintHelper.isNull(reference, test)) {
                // Some primitive statements (e.g. EnumPrimitiveStatement for an enum
                // whose class failed to load, or ClassPrimitiveStatement when Class.forName
                // throws) carry a null value. Reject them here so the mutation/insertion
                // callers can recover via the standard ConstructionFailedException path,
                // instead of an AssertionError tearing down the entire generation.
                throw new ConstructionFailedException(
                        "Generated variable " + reference + " is null but null is not allowed for type "
                                + parameterType);
            }
            assert config.isCanUseMocks()
                    || !(test.getStatement(reference.getStPosition()) instanceof FunctionalMockStatement);

            return reference;
        }

        return null;
    }

    /**
     * Attempts to generate a variable.
     *
     * @param test the test case
     * @param type the type
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference attemptGeneration(TestCase test, Type type, int position,
                                               GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {

        GenericClass<?> clazz = GenericClassFactory.get(type);

        if (clazz.isEnum()) {
            if (!TestUsageChecker.canUse(clazz.getRawClass())) {
                throw new ConstructionFailedException("Cannot generate unaccessible enum " + clazz);
            }
            return testFactory.createPrimitive(test, clazz, position, context);
        } else if (clazz.isPrimitive() || clazz.isClass()
                || EnvironmentStatements.isEnvironmentData(clazz.getRawClass())) {
            return testFactory.createPrimitive(test, clazz, position, context);
        } else if (clazz.isString()) {
            if (config.isAllowNull() && Randomness.nextDouble() <= Properties.NULL_PROBABILITY) {
                return testFactory.createNull(test, type, position, context);
            } else {
                return testFactory.createPrimitive(test, clazz, position, context);
            }
        } else if (clazz.isArray()) {
            if (config.isAllowNull() && Randomness.nextDouble() <= Properties.NULL_PROBABILITY) {
                return testFactory.createNull(test, type, position, context);
            } else {
                return testFactory.createArray(test, clazz, position, context);
            }
        } else {
            if (config.isAllowNull() && Randomness.nextDouble() <= Properties.NULL_PROBABILITY) {
                return testFactory.createNull(test, type, position, context);
            }

            ObjectPoolManager objectPool = ObjectPoolManager.getInstance();
            if (Randomness.nextDouble() <= Properties.P_OBJECT_POOL && objectPool.hasSequence(clazz)) {
                TestCase sequence = objectPool.getRandomSequence(clazz);
                if (sequence != null) {
                    int originalSize = test.size();
                    try {
                        VariableReference targetObject = sequence.getLastObject(type);
                        int returnPos = position + targetObject.getStPosition();
                        for (int i = 0; i < sequence.size(); i++) {
                            test.addStatement(sequence.getStatement(i).copy(test, position), position + i);
                        }
                        int usageCount = objectPool.incrementSequenceUsageCount();
                        ClientServices.track(RuntimeVariable.Object_Pool_Sequence_Used, usageCount);
                        return test.getStatement(returnPos).getReturnValue();
                    } catch (Exception e) {
                        // Roll back any partially inserted statements
                        while (test.size() > originalSize) {
                            test.remove(test.size() - 1);
                        }
                        logger.trace("Object pool sequence insertion failed, falling through: {}", e.getMessage());
                    }
                }
            }

            return createObject(test, type, position, context, config);
        }
    }

    /**
     * Creates an object.
     *
     * @param test the test case
     * @param type the type
     * @param position the position
     * @param context the generation context
     * @param config the variable resolution configuration
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    public VariableReference createObject(TestCase test, Type type, int position,
                                          GenerationContext context, VariableResolutionConfig config)
            throws ConstructionFailedException {
        if (config == null) {
            config = VariableResolutionConfig.defaultConfig();
        }
        GenericClass<?> clazz = GenericClassFactory.get(type);
        VariableReference ret = null;

        /*
         * Object-rule mutators frequently contain semantically unconstrained Object parameters:
         * map keys, rule names, and opaque user values. Their runtime type is irrelevant to the
         * field mutation represented by the OCG. Asking the normal generator machinery for
         * Object lets CastClassManager choose any class on the analyzed classpath and can grow
         * large, unrelated graphs (ObjectMapper/type-resolver/recycler graphs were a concrete
         * example for GenericMachine). A String is non-null, cheap, mutable by the GA, and valid
         * for every truly unbounded Object slot.
         *
         * Keep this policy strictly inside fresh graph-to-code materialization. Entry creation,
         * normal random generation, mutation, setup repair, and cached-template execution retain
         * their existing behavior.
         */
        if (TestGenerationContext.getInstance().getObjectRuleRuntime().isSynthesisConstruction()
                && clazz.getRawClass() == Object.class) {
            StringPrimitiveStatement scalar = new StringPrimitiveStatement(test, "value");
            scalar.setObjectRuleSlot(true);
            ret = test.addStatement(scalar, position);
            ret.setDistance(context.getDepth() + 1);
            return ret;
        }

        // When resolving a dependency (depth > 0) for an interface or abstract class,
        // use a baseline mock probability even when P_FUNCTIONAL_MOCKING is 0, and bypass
        // the FUNCTIONAL_MOCKING_PERCENT time gate. Concrete generators for types like
        // java.sql.Connection often fail at runtime, and mocking provides a reliable fallback.
        boolean isDeepFunctionalInterface = context.getDepth() > 0
                && isFunctionalInterface(clazz.getRawClass());
        // Do not gate this on whether the cluster has a generator for the type. It is
        // tempting to, because "mock_if_no_generator" reads like a promise to mock only
        // when there is nothing else to call, and gating it does help the case where a
        // usable concrete implementation is being passed over in favour of a mock
        // (102_squirrel-sql's Scanner, whose abstract Buffer parameter has working
        // package-private FBuffer/SBuffer implementations, gains ~0.36 branch coverage).
        // But P_FUNCTIONAL_MOCKING is 0.0 by default, so the boost below is the only thing
        // that ever makes a deep interface/abstract dependency mockable at all: gating it
        // does not reduce mocking for these types, it switches mocking off entirely and
        // leaves the search with whatever concrete generator happens to exist, however
        // badly it works. Plenty of them work badly - SharedORB.getInstance() throws
        // ExportException outside an RMI call, so 66_openjms's JmsAdminConnectionImpl
        // cannot be constructed at all without a mock ORB. Measured over SF110 (23,267
        // classes, matched seeds, 120s budget), gating cost >0.20 branch coverage on 1213
        // classes and gained it on at most 73, for -0.026 mean.
        //
        // The 0.5 boost is tossed independently at every depth, though, and that is its own
        // problem for a class like 104_vuze's GenericMessageDecoder: reaching its decode
        // branches requires Transport.read() to actually deliver bytes, which only happens
        // along a concrete five-link chain (TCPTransportHelper -> TransportHelperFilter... ->
        // LightweightTCPTransport). Each interface/abstract link on that chain independently
        // survives the coin toss with probability 0.5, so the whole chain survives with
        // probability ~0.5^k and almost never assembles. Once the search has committed to
        // building a given deep interface/abstract dependency concretely (the coin came up
        // "build a real one"), that decision should carry down into the dependencies of the
        // chosen generator too: GenerationContext.concreteChain records that commitment and,
        // below, lowers the boost to CHAIN_MOCK_BOOST for the rest of the chain instead of
        // re-rolling 0.5 at every level. It is lowered, not removed: with 0 inside a chain
        // GenericMessageDecoder went from 0.63 to 1.0 but freemind's ServerCommunication
        // dropped from 0.86-0.91 to 0.64-0.68 on the same seeds, because a concrete link
        // then drags every dependency below it into real construction, hopeless ones
        // included; at 0.25 GenericMessageDecoder still reaches 1.0 on three seeds and
        // ServerCommunication stays at 0.73-0.86. A "mock" outcome is unaffected: mocking a
        // link still stops the chain there, exactly as before. Functional interfaces keep their 1.0 boost even inside a
        // concrete chain (isDeepPlainInterfaceOrAbstract excludes them below) because they are
        // almost never independently constructible (Comparator, Runnable, ...) - collapsing that
        // boost to 0 would just trade "chain never assembles" for "functional dependency never
        // resolves", which is a worse failure mode than an occasional over-eager mock.
        boolean isDeepInterfaceOrAbstract = context.getDepth() > 0
                && (clazz.getRawClass().isInterface() || clazz.isAbstract());
        boolean isDeepPlainInterfaceOrAbstract = isDeepInterfaceOrAbstract && !isDeepFunctionalInterface;
        // A mock of this type is inert - every method the search could otherwise stub is
        // either void, equals/hashCode/toString, or final in the original bytecode (see
        // FunctionalMockStatement.hasUsefulMockSurface) - so a mock can never let the search
        // steer this dependency. Only skip the mock boost for it, though, when a real
        // generator is actually available: an inert mock is still better than
        // ConstructionFailedException when it is the only way to get an instance at all.
        boolean mockWouldBeInert = isDeepPlainInterfaceOrAbstract
                && !FunctionalMockStatement.hasUsefulMockSurface(clazz.getRawClass())
                && TestCluster.getInstance().hasGenerator(type);
        double effectiveMockProb = Properties.P_FUNCTIONAL_MOCKING;
        boolean bypassTimeGate = false;
        if (isDeepFunctionalInterface && Properties.MOCK_IF_NO_GENERATOR) {
            effectiveMockProb = 1.0;
            bypassTimeGate = true;
        } else if (isDeepPlainInterfaceOrAbstract && Properties.MOCK_IF_NO_GENERATOR && !mockWouldBeInert) {
            effectiveMockProb = Math.max(effectiveMockProb,
                    context.isConcreteChain() ? CHAIN_MOCK_BOOST : DEEP_MOCK_BOOST);
            bypassTimeGate = true;
        }

        boolean pastTimeGate = bypassTimeGate
                || TimeController.getInstance().getPhasePercentage() >= Properties.FUNCTIONAL_MOCKING_PERCENT;
        boolean canUseMocks = config.isCanUseMocks();
        // Entry-call construction happens before graph synthesis and therefore does not flow
        // through TestFactory.changeNullStatement(). Apply the same narrow preference here while
        // EvoObj is active, otherwise environment-bearing constructor arguments (notably Path)
        // are already mocks by the time synthesis begins and no matching virtual setup is possible.
        boolean preferConcreteGenerators =
                config.isPreferConcreteGenerators()
                        || TestGenerationContext.getInstance()
                                .getObjectRuleRuntime()
                                .isEntryConstruction();
        double roll = Randomness.nextDouble();
        boolean rollPassed = roll < effectiveMockProb;
        boolean canMock = rollPassed ? FunctionalMockStatement.canBeFunctionalMocked(type) : false;
        if (preferConcreteGenerators) {
            ret = tryCreatePreferredConcreteValue(test, clazz, position, context);
        }
        if (ret == null && canUseMocks && pastTimeGate && rollPassed && canMock) {
            ret = testFactory.addFunctionalMock(test, type, position, context.deeper());
        } else if (ret == null) {
            VariableReference generatorRefToExclude = config.isExcludeCalleeGenerators()
                    ? config.getExcludeVar() : null;
            GenericAccessibleObject<?> o = TestCluster.getInstance().getRandomGenerator(
                    clazz, context.getVisited(), test, position, generatorRefToExclude, context.getDepth());
            GenerationContext nextContext = context.withVisited(o);
            // The mock coin for this deep interface/abstract type came up "build a real one"
            // (o != null and we did not take the mock branch above): commit the rest of this
            // chain - o's own parameters - to concrete construction too. See the effectiveMockProb
            // comment above for why per-dependency coin tosses defeat multi-link chains.
            if (isDeepPlainInterfaceOrAbstract && o != null) {
                nextContext = nextContext.withConcreteChain();
            }

            if (o == null) {
                if (config.isCanReuseExistingVariables()) {
                    for (int i = position - 1; i >= 0; i--) {
                        VariableReference var = test.getReturnValue(i);
                        if ((config.isAllowNull() || !ConstraintHelper.isNull(var, test))
                                && var.isAssignableTo(type)
                                && !(test.getStatement(i) instanceof FunctionalMockStatement)) {
                            if (clazz.getRawClass().isAssignableFrom(var.getGenericClass().getRawClass())) {
                                return var;
                            }
                        }
                    }
                }

                if (config.isCanUseMocks()
                        && (Properties.MOCK_IF_NO_GENERATOR || Properties.P_FUNCTIONAL_MOCKING > 0)) {
                    if (FunctionalMockStatement.canBeFunctionalMocked(type)) {
                        ret = testFactory.addFunctionalMock(test, type, position, context.deeper());
                    } else if (clazz.isAbstract() && FunctionalMockStatement.canBeFunctionalMockedIncludingSUT(type)) {
                        ret = testFactory.addFunctionalMockForAbstractClass(test, type, position, context.deeper());
                    }
                }

                if (ret == null) {
                    if (!TestCluster.getInstance().hasGenerator(type)) {
                        TestClusterGenerator clusterGenerator = ensureClusterGenerator();
                        if (clusterGenerator != null) {
                            Class<?> mock = MockList.getMockClass(clazz.getRawClass().getCanonicalName());
                            clusterGenerator.addNewDependencies(
                                    Collections.singletonList(mock != null ? mock : clazz.getRawClass()));
                            if (TestCluster.getInstance().hasGenerator(type)) {
                                return createObject(test, type, position, context.deeper(), config);
                            }
                        } else {
                            logger.warn("TestClusterGenerator is null while trying to add dependencies for {}",
                                    clazz.getRawClass().getName());
                        }
                    }
                    throw new ConstructionFailedException("Have no generator for " + type);
                }
            } else if (o.isField()) {
                ret = testFactory.addField(test, (GenericField) o, position, nextContext.deeper());
            } else if (o.isMethod()) {
                ret = testFactory.addMethod(test, (GenericMethod) o, position, nextContext.deeper());
            } else if (o.isConstructor()) {
                ret = testFactory.addConstructor(test, (GenericConstructor) o, type, position, nextContext.deeper());
            } else {
                throw new ConstructionFailedException("No generator found for type " + type);
            }
        }
        ret.setDistance(context.getDepth() + 1);
        return ret;
    }

    private static boolean isFunctionalInterface(Class<?> rawClass) {
        if (rawClass == null || !rawClass.isInterface()) {
            return false;
        }
        try {
            if (rawClass.isAnnotationPresent(FunctionalInterface.class)) {
                return true;
            }

            int abstractMethods = 0;
            for (Method method : rawClass.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                try {
                    Object.class.getMethod(method.getName(), method.getParameterTypes());
                    continue;
                } catch (NoSuchMethodException ignored) {
                    // Not an Object method.
                }
                abstractMethods++;
                if (abstractMethods > 1) {
                    return false;
                }
            }
            return abstractMethods == 1;
        } catch (LinkageError e) {
            return false;
        }
    }

    private static boolean isPreferredConcreteFactoryType(Class<?> type) {
        if (type == null) {
            return false;
        }
        String name = type.getName();
        return "java.nio.file.Path".equals(name)
                || "java.nio.charset.Charset".equals(name)
                || "java.net.URI".equals(name)
                || "java.io.File".equals(name);
    }

    /**
     * Constructs the small set of value/environment types for which a stable public factory is more
     * useful than a behaviorless functional mock. Values are object-rule slots so the normal GA can
     * still mutate them. Any partial insertion is rolled back before falling through to the
     * ordinary generator/mock policy.
     */
    private VariableReference tryCreatePreferredConcreteValue(
            TestCase test, GenericClass<?> requestedType, int position, GenerationContext context) {
        Class<?> type = requestedType.getRawClass();
        int originalSize = test.size();
        int insertionStart = position;
        try {
            VariableReference schemaValue = tryCreateFactoryBackedInterface(
                    test, requestedType, position);
            if (schemaValue != null) {
                return schemaValue;
            }
            VariableReference characterStream = tryCreateAntlrCharacterStream(
                    test, requestedType, position);
            if (characterStream != null) {
                return characterStream;
            }
            if (java.io.InputStream.class.equals(type)) {
                ArrayStatement bytes = new ArrayStatement(test, byte[].class, 0);
                VariableReference bytesReference = test.addStatement(bytes, position++);
                java.lang.reflect.Constructor<java.io.ByteArrayInputStream> constructor =
                        java.io.ByteArrayInputStream.class.getConstructor(byte[].class);
                return test.addStatement(new ConstructorStatement(
                        test,
                        new GenericConstructor(constructor, java.io.ByteArrayInputStream.class),
                        Collections.singletonList(bytesReference)), position);
            }
            if (java.io.Reader.class.equals(type)) {
                StringPrimitiveStatement text = new StringPrimitiveStatement(test, "");
                text.setObjectRuleSlot(true);
                VariableReference textReference = test.addStatement(text, position++);
                java.lang.reflect.Constructor<java.io.StringReader> constructor =
                        java.io.StringReader.class.getConstructor(String.class);
                return test.addStatement(new ConstructorStatement(
                        test,
                        new GenericConstructor(constructor, java.io.StringReader.class),
                        Collections.singletonList(textReference)), position);
            }

            String typeName = type.getName();
            String value = "java.nio.file.Path".equals(typeName) ? "config.yml"
                    : ("java.nio.charset.Charset".equals(typeName) ? "UTF-8" : "");
            StringPrimitiveStatement text = new StringPrimitiveStatement(test, value);
            text.setObjectRuleSlot(true);
            VariableReference textReference = test.addStatement(text, position++);

            if ("java.nio.file.Path".equals(typeName)) {
                ArrayStatement remainder = new ArrayStatement(test, String[].class, 0);
                VariableReference remainderReference = test.addStatement(remainder, position++);
                java.lang.reflect.Method factory = java.nio.file.Paths.class.getMethod(
                        "get", String.class, String[].class);
                return test.addStatement(new MethodStatement(
                        test,
                        new GenericMethod(factory, java.nio.file.Paths.class),
                        null,
                        Arrays.asList(textReference, remainderReference)), position);
            }
            if ("java.nio.charset.Charset".equals(typeName)) {
                java.lang.reflect.Method factory = java.nio.charset.Charset.class.getMethod(
                        "forName", String.class);
                return test.addStatement(new MethodStatement(
                        test,
                        new GenericMethod(factory, java.nio.charset.Charset.class),
                        null,
                        Collections.singletonList(textReference)), position);
            }
            if ("java.net.URI".equals(typeName)) {
                java.lang.reflect.Method factory = java.net.URI.class.getMethod("create", String.class);
                return test.addStatement(new MethodStatement(
                        test,
                        new GenericMethod(factory, java.net.URI.class),
                        null,
                        Collections.singletonList(textReference)), position);
            }
            if ("java.io.File".equals(typeName)) {
                java.lang.reflect.Constructor<File> constructor = File.class.getConstructor(String.class);
                return test.addStatement(new ConstructorStatement(
                        test,
                        new GenericConstructor(constructor, File.class),
                        Collections.singletonList(textReference)), position);
            }
        } catch (Throwable failure) {
            logger.debug("Could not create preferred concrete value for {}", type.getName(), failure);
        }

        rollbackInsertedStatements(test, originalSize, insertionStart);
        // The specialized factories above advance their local insertion cursor. Once their
        // speculative statements have been removed, all subsequent alternatives must start at
        // the original cursor again.
        position = insertionStart;

        VariableReference publicConstant = tryCreatePublicConstant(
                test, requestedType, position, context);
        if (publicConstant != null) {
            return publicConstant;
        }

        VariableReference standardContainer = tryCreateStandardContainer(
                test, requestedType, position, context);
        if (standardContainer != null) {
            return standardContainer;
        }

        // General object-rule fallback: deterministic, low-complexity generators are much less
        // likely to throw before the target call than a uniformly random constructor/factory.
        // Prefer constants, zero-argument constructors/factories, then increasing arity. Trying a
        // small ordered portfolio also handles interfaces such as FullHttpResponse and List whose
        // useful implementations are registered as generators of the interface type.
        List<GenericAccessibleObject<?>> generators;
        try {
            generators = new ArrayList<>(TestCluster.getInstance().getGenerators(requestedType));
        } catch (Throwable failure) {
            return null;
        }
        generators.removeIf(generator -> !isStableConcreteGenerator(generator)
                || context.isVisited(generator));
        generators.sort(Comparator
                .comparingInt(VariableResolver::stableGeneratorPriority)
                .thenComparing(Object::toString));

        int attempts = 0;
        for (GenericAccessibleObject<?> generator : generators) {
            if (attempts++ >= 4) {
                break;
            }
            int attemptSize = test.size();
            try {
                GenerationContext nextContext = context.withVisited(generator);
                VariableReference candidate;
                if (generator instanceof GenericField) {
                    candidate = testFactory.addField(
                            test, (GenericField) generator, position, nextContext);
                } else if (generator instanceof GenericConstructor) {
                    candidate = testFactory.addConstructor(test, (GenericConstructor) generator,
                            requestedType.getType(), position, nextContext);
                } else if (generator instanceof GenericMethod) {
                    candidate = testFactory.addMethod(
                            test, (GenericMethod) generator, position, nextContext);
                } else {
                    continue;
                }
                if (candidate != null && candidate.isAssignableTo(requestedType.getType())) {
                    return candidate;
                }
            } catch (Throwable failure) {
                logger.trace("Stable concrete generator {} failed for {}: {}",
                        generator, type.getName(), failure.toString());
            }
            rollbackInsertedStatements(test, attemptSize, insertionStart);
        }
        return null;
    }

    /**
     * XMLBeans and similar generated APIs expose an interface plus a nested {@code Factory} whose
     * zero-argument {@code newInstance()} is the supported constructor. Instantiating their public
     * implementation class directly requires an internal schema token and yields an object that
     * throws before the target call.
     */
    private VariableReference tryCreateFactoryBackedInterface(
            TestCase test, GenericClass<?> requestedType, int position) {
        Class<?> requestedClass = requestedType.getRawClass();
        if (!requestedClass.isInterface()) {
            return null;
        }
        for (Class<?> nested : requestedClass.getDeclaredClasses()) {
            if (!"Factory".equals(nested.getSimpleName())) {
                continue;
            }
            try {
                java.lang.reflect.Method factory = nested.getMethod("newInstance");
                if (!Modifier.isStatic(factory.getModifiers())
                        || factory.getParameterCount() != 0
                        || !requestedClass.isAssignableFrom(factory.getReturnType())) {
                    continue;
                }
                VariableReference document = test.addStatement(new MethodStatement(
                        test, new GenericMethod(factory, nested), null,
                        Collections.emptyList()), position);
                initializeGeneratedDocumentRoot(test, requestedClass, document, position + 1);
                return document;
            } catch (ReflectiveOperationException ignored) {
                // This nested Factory follows another convention; try the remaining alternatives.
            }
        }
        return null;
    }

    /**
     * A generated {@code *Document.Factory.newInstance()} creates the document container, not its
     * root element. When the interface exposes one unambiguous zero-argument {@code addNewX()},
     * initialize that root before handing the document to a SUT constructor.
     */
    private void initializeGeneratedDocumentRoot(TestCase test, Class<?> documentType,
            VariableReference document, int position) {
        if (!documentType.getSimpleName().endsWith("Document")) {
            return;
        }
        List<java.lang.reflect.Method> rootBuilders = new ArrayList<>();
        for (java.lang.reflect.Method method : documentType.getMethods()) {
            if (method.getName().startsWith("addNew")
                    && method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() != Void.TYPE) {
                rootBuilders.add(method);
            }
        }
        if (rootBuilders.size() != 1) {
            return;
        }
        java.lang.reflect.Method builder = rootBuilders.get(0);
        test.addStatement(new MethodStatement(
                test, new GenericMethod(builder, builder.getDeclaringClass()),
                document, Collections.emptyList()), position);
    }

    /**
     * ANTLR token recognizers are controlled by the text behind {@code CharStream}. Prefer the
     * mutable String-backed implementation so graph templates contain a GA-mutable input slot,
     * rather than an empty byte stream followed by inert lookahead calls.
     */
    private VariableReference tryCreateAntlrCharacterStream(
            TestCase test, GenericClass<?> requestedType, int position) {
        Class<?> requestedClass = requestedType.getRawClass();
        if (!"org.antlr.runtime.CharStream".equals(requestedClass.getName())) {
            return null;
        }
        try {
            Class<?> implementation = TestGenerationContext.getInstance()
                    .getClassLoaderForSUT()
                    .loadClass("org.antlr.runtime.ANTLRStringStream");
            if (!requestedClass.isAssignableFrom(implementation)) {
                return null;
            }
            java.lang.reflect.Constructor<?> constructor =
                    implementation.getConstructor(String.class);
            // A one-character stream reaches ordinary lexer predicates immediately; the marked
            // slot remains freely mutable for whitespace, punctuation, keywords, and longer
            // tokens. An empty stream makes nearly every generated token rule fail at EOF.
            StringPrimitiveStatement text = new StringPrimitiveStatement(test, "a");
            text.setObjectRuleSlot(true);
            VariableReference textReference = test.addStatement(text, position++);
            return test.addStatement(new ConstructorStatement(
                    test, new GenericConstructor(constructor, implementation),
                    Collections.singletonList(textReference)), position);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private VariableReference tryCreatePublicConstant(TestCase test, GenericClass<?> requestedType,
            int position, GenerationContext context) {
        List<Field> fields = new ArrayList<>(Arrays.asList(requestedType.getRawClass().getFields()));
        fields.sort(Comparator.comparing(Field::getName));
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !requestedType.getRawClass().isAssignableFrom(field.getType())) {
                continue;
            }
            int originalSize = test.size();
            try {
                GenericField generator = new GenericField(field, field.getDeclaringClass());
                // Do not send a known constant back through the normal generator machinery.
                // addField() may recursively choose a different way of producing the declaring
                // type (eg, constructing HttpVersion from random text instead of reading
                // HttpVersion.HTTP_1_1), which defeats the stable-entry policy and can throw
                // before the target invocation. A public static field needs no receiver and is
                // already a complete, executable generator.
                VariableReference result = test.addStatement(
                        new FieldStatement(test, generator, null), position);
                if (result != null && result.isAssignableTo(requestedType.getType())) {
                    return result;
                }
            } catch (Throwable failure) {
                logger.trace("Public constant {} failed for stable entry construction: {}",
                        field, failure.toString());
            }
            rollbackInsertedStatements(test, originalSize, position);
        }
        return null;
    }

    private VariableReference tryCreateStandardContainer(TestCase test,
            GenericClass<?> requestedType, int position, GenerationContext context) {
        Class<?> requestedClass = requestedType.getRawClass();
        Class<?> implementation = null;
        if (Map.class.isAssignableFrom(requestedClass)) {
            implementation = LinkedHashMap.class;
        } else if (Set.class.isAssignableFrom(requestedClass)) {
            implementation = LinkedHashSet.class;
        } else if (Deque.class.isAssignableFrom(requestedClass)
                || Queue.class.isAssignableFrom(requestedClass)) {
            implementation = ArrayDeque.class;
        } else if (Collection.class.isAssignableFrom(requestedClass)) {
            implementation = ArrayList.class;
        }
        if (implementation == null || !requestedClass.isAssignableFrom(implementation)) {
            return null;
        }
        int originalSize = test.size();
        try {
            // Bind the implementation's type variables to the requested arguments up front
            // (LinkedHashSet<E> -> LinkedHashSet<String> for Set<String>). A no-arg constructor
            // gets no owner binding in addConstructor, and an unbound LinkedHashSet<E> passes
            // the isAssignableTo check below for any element type.
            Type ownerType = implementation;
            if (requestedType.getType() instanceof ParameterizedType
                    && implementation.getTypeParameters().length
                        == ((ParameterizedType) requestedType.getType()).getActualTypeArguments().length) {
                ownerType = TypeUtils.parameterize(implementation,
                        ((ParameterizedType) requestedType.getType()).getActualTypeArguments());
            }
            GenericConstructor constructor = new GenericConstructor(
                    implementation.getConstructor(), ownerType);
            VariableReference result = testFactory.addConstructor(
                    test, constructor, ownerType, position, context);
            if (result != null && result.isAssignableTo(requestedType.getType())) {
                return result;
            }
        } catch (Throwable failure) {
            logger.trace("Standard container {} failed for {}: {}",
                    implementation.getName(), requestedClass.getName(), failure.toString());
        }
        rollbackInsertedStatements(test, originalSize, position);
        return null;
    }

    private static boolean isStableConcreteGenerator(GenericAccessibleObject<?> generator) {
        if (generator instanceof GenericField) {
            return generator.isStatic();
        }
        if (generator instanceof GenericConstructor) {
            return generator.getNumParameters() <= 4;
        }
        if (generator instanceof GenericMethod) {
            GenericMethod method = (GenericMethod) generator;
            return generator.isStatic() && generator.getNumParameters() <= 4
                    && !(method.getMethod().getGenericReturnType()
                    instanceof java.lang.reflect.TypeVariable<?>);
        }
        return false;
    }

    private static int stableGeneratorPriority(GenericAccessibleObject<?> generator) {
        if (generator instanceof GenericField) {
            return 0;
        }
        if (generator instanceof GenericConstructor) {
            return 10 + generator.getNumParameters();
        }
        return 20 + generator.getNumParameters();
    }

    private static void rollbackInsertedStatements(
            TestCase test, int originalSize, int insertionStart) {
        while (test.size() > originalSize) {
            int removalPosition = insertionStart >= 0 && insertionStart < test.size()
                    ? insertionStart : test.size() - 1;
            test.remove(removalPosition);
        }
    }

    private TestClusterGenerator ensureClusterGenerator() {
        TestGenerationContext ctx = TestGenerationContext.getInstance();
        TestClusterGenerator generator = ctx.getTestClusterGenerator();
        if (generator != null) {
            return generator;
        }

        synchronized (ctx) {
            generator = ctx.getTestClusterGenerator();
            if (generator != null) {
                return generator;
            }

            if (!Properties.TARGET_CLASS.isEmpty()) {
                String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
                if (cp != null && !cp.isEmpty()) {
                    List<String> classPath = Arrays.asList(cp.split(File.pathSeparator));
                    try {
                        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classPath);
                        generator = ctx.getTestClusterGenerator();
                        if (generator != null) {
                            logger.info("Lazily initialized TestClusterGenerator by analyzing target {}",
                                    Properties.TARGET_CLASS);
                            return generator;
                        }
                    } catch (ClassNotFoundException | RuntimeException e) {
                        logger.warn("Failed to lazily initialize TestClusterGenerator for {}: {}",
                                Properties.TARGET_CLASS, e.getMessage());
                        try {
                            DependencyAnalysis.initInheritanceTree(classPath);
                            generator = ctx.getTestClusterGenerator();
                            if (generator != null) {
                                logger.info("Lazily initialized TestClusterGenerator from inheritance tree for {}",
                                        Properties.TARGET_CLASS);
                                return generator;
                            }
                        } catch (RuntimeException treeInitError) {
                            logger.warn("Failed to initialize inheritance tree for {}: {}",
                                    Properties.TARGET_CLASS, treeInitError.getMessage());
                        }
                    }
                }
            }

            if (DependencyAnalysis.getInheritanceTree() != null) {
                generator = new TestClusterGenerator(DependencyAnalysis.getInheritanceTree());
                ctx.setTestClusterGenerator(generator);
                logger.info("Lazily initialized TestClusterGenerator from cached inheritance tree");
                return generator;
            }
        }
        return null;
    }

    /**
     * Attempts to generate an object.
     *
     * @param test the test case
     * @param position the position
     * @param context the generation context
     * @param allowNull whether null is allowed
     * @return the variable reference
     * @throws ConstructionFailedException if construction fails
     */
    protected VariableReference attemptObjectGeneration(TestCase test, int position,
                                                        GenerationContext context, boolean allowNull)
            throws ConstructionFailedException {

        if (allowNull && Randomness.nextDouble() <= Properties.NULL_PROBABILITY) {
            return testFactory.createNull(test, Object.class, position, context);
        }

        // Snapshot first to avoid ConcurrentModificationException when cast classes
        // are extended while this method is filtering candidates.
        Set<GenericClass<?>> castClassesSnapshot = new HashSet<>(CastClassManager.getInstance().getCastClasses());
        List<GenericClass<?>> classes = castClassesSnapshot.stream()
                .filter(c -> TestCluster.getInstance().hasGenerator(c) || c.isString())
                .collect(Collectors.toList());
        classes.add(GenericClassFactory.get(Object.class));

        GenericClass<?> choice = Randomness.choice(classes);
        if (choice.isString()) {
            return resolveVariable(test, String.class, position, context,
                    new VariableResolutionConfig.Builder().withAllowNull(allowNull).withCanUseMocks(false).build());
        }

        GenericAccessibleObject<?> o = TestCluster.getInstance().getRandomGenerator(choice);
        if (o == null) {
            throw new ConstructionFailedException("Generator is null");
        } else if (o.isField()) {
            VariableReference ret = testFactory.addField(test, (GenericField) o, position, context.deeper());
            ret.setDistance(context.getDepth() + 1);
            return ret;
        } else if (o.isMethod()) {
            VariableReference ret = testFactory.addMethod(test, (GenericMethod) o, position, context.deeper());
            ret.setDistance(context.getDepth() + 1);
            return ret;
        } else if (o.isConstructor()) {
            VariableReference ret = testFactory.addConstructor(test, (GenericConstructor) o,
                    position, context.deeper());
            ret.setDistance(context.getDepth() + 1);
            return ret;
        } else {
            throw new ConstructionFailedException("No generator found for Object.class");
        }
    }

    static void filterVariablesByCastClasses(Collection<VariableReference> variables) {
        // Remove invalid classes if this is an Object.class reference
        Set<GenericClass<?>> castClasses = CastClassManager.getInstance().getCastClasses();
        Iterator<VariableReference> replacement = variables.iterator();
        while (replacement.hasNext()) {
            VariableReference r = replacement.next();
            boolean isAssignable = false;
            for (GenericClass<?> clazz : castClasses) {
                if (r.isPrimitive()) {
                    continue;
                }
                if (clazz.isAssignableFrom(r.getVariableClass())) {
                    isAssignable = true;
                    break;
                }
            }
            if (!isAssignable && !r.getVariableClass().equals(Object.class)) {
                replacement.remove();
            }
        }
    }
}
