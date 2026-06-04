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

import java.io.File;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resolving and creating variables during test case generation.
 *
 * @author Gordon Fraser
 */
public class VariableResolver {

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
            testFactory.throwCannotAssignIfNeeded(message, test, position, normalizedParameterType, ref);
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

        // When resolving a dependency (depth > 0) for an interface or abstract class,
        // use a baseline mock probability even when P_FUNCTIONAL_MOCKING is 0, and bypass
        // the FUNCTIONAL_MOCKING_PERCENT time gate. Concrete generators for types like
        // java.sql.Connection often fail at runtime, and mocking provides a reliable fallback.
        boolean isDeepInterfaceOrAbstract = context.getDepth() > 0
                && (clazz.getRawClass().isInterface() || clazz.isAbstract());
        double effectiveMockProb = Properties.P_FUNCTIONAL_MOCKING;
        boolean bypassTimeGate = false;
        if (isDeepInterfaceOrAbstract && Properties.MOCK_IF_NO_GENERATOR) {
            effectiveMockProb = Math.max(effectiveMockProb, 0.5);
            bypassTimeGate = true;
        }

        boolean pastTimeGate = bypassTimeGate
                || TimeController.getInstance().getPhasePercentage() >= Properties.FUNCTIONAL_MOCKING_PERCENT;
        boolean canUseMocks = config.isCanUseMocks();
        double roll = Randomness.nextDouble();
        boolean rollPassed = roll < effectiveMockProb;
        boolean canMock = rollPassed ? FunctionalMockStatement.canBeFunctionalMocked(type) : false;
        if (canUseMocks && pastTimeGate && rollPassed && canMock) {
            ret = testFactory.addFunctionalMock(test, type, position, context.deeper());
        } else {
            VariableReference generatorRefToExclude = config.isExcludeCalleeGenerators()
                    ? config.getExcludeVar() : null;
            GenericAccessibleObject<?> o = TestCluster.getInstance().getRandomGenerator(
                    clazz, context.getVisited(), test, position, generatorRefToExclude, context.getDepth());
            GenerationContext nextContext = context.withVisited(o);

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
