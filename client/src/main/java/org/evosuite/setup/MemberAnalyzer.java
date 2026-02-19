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

package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.instrumentation.testability.BooleanTestabilityTransformation;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Analyzes the members (constructors, methods, fields) of a class and registers
 * them with the {@link TestCluster} as generators, modifiers, and test calls.
 *
 * <p>
 * This class consolidates the duplicated member analysis logic that was
 * previously in {@code TestClusterGenerator.initializeTargetMethods} (for
 * target classes) and {@code TestClusterGenerator.addDependencyClass} (for
 * dependency classes). The {@link AnalysisMode} enum encodes the behavioural
 * differences between these two contexts.
 * </p>
 */
public class MemberAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(MemberAnalyzer.class);

    /**
     * Determines whether a class is being analyzed as the SUT target
     * or as a dependency.
     */
    public enum AnalysisMode {
        /** SUT classes: adds test calls, relaxed generator filtering. */
        TARGET,
        /** Dependency classes: no test calls, strict generator filtering. */
        DEPENDENCY
    }

    private final TestCluster cluster;
    private final Set<GenericAccessibleObject<?>> dependencyCache;
    private final Set<DependencyPair> dependencies;
    private final Set<GenericClass<?>> analyzedAbstractClasses;
    private final Set<Class<?>> analyzedClasses;
    private final InheritanceTree inheritanceTree;

    /**
     * Create a new member analyzer.
     *
     * @param cluster the TestCluster to populate
     * @param dependencyCache shared cache to avoid re-analyzing the same member
     * @param dependencies shared dependency queue for recursive resolution
     * @param analyzedAbstractClasses shared set of already-analyzed abstract classes
     * @param analyzedClasses shared set of already-analyzed classes
     * @param inheritanceTree the inheritance tree
     */
    public MemberAnalyzer(TestCluster cluster,
                          Set<GenericAccessibleObject<?>> dependencyCache,
                          Set<DependencyPair> dependencies,
                          Set<GenericClass<?>> analyzedAbstractClasses,
                          Set<Class<?>> analyzedClasses,
                          InheritanceTree inheritanceTree) {
        this.cluster = cluster;
        this.dependencyCache = dependencyCache;
        this.dependencies = dependencies;
        this.analyzedAbstractClasses = analyzedAbstractClasses;
        this.analyzedClasses = analyzedClasses;
        this.inheritanceTree = inheritanceTree;
    }

    /**
     * Analyze all constructors, methods, and fields of the given class and
     * register them with the TestCluster.
     *
     * @param clazz the class to analyze
     * @param mode TARGET for SUT classes, DEPENDENCY for dependency classes
     * @param recursionLevel current recursion depth (used for dependency tracking)
     * @return true if the class was successfully analyzed
     */
    public boolean analyze(GenericClass<?> clazz, AnalysisMode mode, int recursionLevel) {
        Class<?> rawClass = clazz.getRawClass();

        if (!TestUsageChecker.canUse(rawClass)) {
            logger.info("*** Cannot use class: " + clazz.getClassName());
            return false;
        }

        if (mode == AnalysisMode.DEPENDENCY) {
            try {
                analyzeConstructors(clazz, rawClass, mode, recursionLevel);
                analyzeMethods(clazz, rawClass, mode, recursionLevel);
                analyzeFields(clazz, rawClass, mode, recursionLevel);
                logger.info("Finished analyzing " + clazz.getTypeName()
                        + " at recursion level " + recursionLevel);
                cluster.getAnalyzedClasses().add(rawClass);
            } catch (Throwable t) {
                logger.error("Problem for " + Properties.TARGET_CLASS
                        + ". Failed to add dependencies for class "
                        + clazz.getClassName() + ": " + t + "\n"
                        + java.util.Arrays.asList(t.getStackTrace()));
                return false;
            }
        } else {
            analyzeConstructors(clazz, rawClass, mode, recursionLevel);
            analyzeMethods(clazz, rawClass, mode, recursionLevel);
            analyzeFields(clazz, rawClass, mode, recursionLevel);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Constructor analysis
    // -----------------------------------------------------------------------

    private void analyzeConstructors(GenericClass<?> clazz, Class<?> rawClass,
                                     AnalysisMode mode, int recursionLevel) {
        for (Constructor<?> constructor : TestClusterUtils.getConstructors(rawClass)) {
            logger.info("Checking target constructor " + constructor);
            String name = "<init>"
                    + org.objectweb.asm.Type.getConstructorDescriptor(constructor);

            if (Properties.TT) {
                String orig = name;
                name = BooleanTestabilityTransformation.getOriginalNameDesc(
                        clazz.getClassName(), "<init>",
                        org.objectweb.asm.Type.getConstructorDescriptor(constructor));
                if (!orig.equals(name)) {
                    logger.info("TT name: " + orig + " -> " + name);
                }
            }

            if (TestUsageChecker.canUse(constructor)) {
                if (mode == AnalysisMode.DEPENDENCY) {
                    analyzeUsableConstructorDependency(clazz, constructor, recursionLevel);
                } else {
                    analyzeUsableConstructorTarget(clazz, rawClass, constructor, recursionLevel);
                }
            } else {
                logger.debug("Constructor cannot be used: " + constructor);
            }
        }
    }

    private void analyzeUsableConstructorTarget(GenericClass<?> clazz, Class<?> rawClass,
                                                Constructor<?> constructor, int recursionLevel) {
        GenericConstructor genericConstructor = new GenericConstructor(constructor, rawClass);
        if (constructor.getDeclaringClass().equals(rawClass)) {
            cluster.addTestCall(genericConstructor);
        }
        // TODO: Add types!
        cluster.addGenerator(GenericClassFactory.get(rawClass),
                genericConstructor);
        addDependencies(genericConstructor, recursionLevel);
        logger.debug("Keeping track of "
                + constructor.getDeclaringClass().getName() + "."
                + constructor.getName()
                + org.objectweb.asm.Type.getConstructorDescriptor(constructor));
    }

    private void analyzeUsableConstructorDependency(GenericClass<?> clazz,
                                                    Constructor<?> constructor,
                                                    int recursionLevel) {
        GenericConstructor genericConstructor = new GenericConstructor(constructor, clazz);
        try {
            cluster.addGenerator(clazz, genericConstructor);
            addDependencies(genericConstructor, recursionLevel + 1);
            if (logger.isDebugEnabled()) {
                logger.debug("Keeping track of "
                        + constructor.getDeclaringClass().getName() + "."
                        + constructor.getName()
                        + org.objectweb.asm.Type.getConstructorDescriptor(constructor));
            }
        } catch (Throwable t) {
            logger.info("Error adding constructor {}: {}",
                    constructor.getName(), t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Method analysis
    // -----------------------------------------------------------------------

    private void analyzeMethods(GenericClass<?> clazz, Class<?> rawClass,
                                AnalysisMode mode, int recursionLevel) {
        for (Method method : TestClusterUtils.getMethods(rawClass)) {
            logger.info("Checking target method " + method);
            String name = method.getName()
                    + org.objectweb.asm.Type.getMethodDescriptor(method);

            if (Properties.TT) {
                String orig = name;
                name = BooleanTestabilityTransformation.getOriginalNameDesc(
                        clazz.getClassName(), method.getName(),
                        org.objectweb.asm.Type.getMethodDescriptor(method));
                if (!orig.equals(name)) {
                    logger.info("TT name: " + orig + " -> " + name);
                }
            }

            if (mode == AnalysisMode.TARGET) {
                analyzeMethodTarget(clazz, rawClass, method, recursionLevel);
            } else {
                analyzeMethodDependency(clazz, rawClass, method, recursionLevel);
            }
        }
    }

    private void analyzeMethodTarget(GenericClass<?> clazz, Class<?> rawClass,
                                     Method method, int recursionLevel) {
        if (TestUsageChecker.canUse(method, rawClass)) {
            logger.debug("Adding method " + rawClass.getName() + "."
                    + method.getName()
                    + org.objectweb.asm.Type.getMethodDescriptor(method));

            if (rawClass.isInterface()
                    && Modifier.isAbstract(method.getModifiers())) {
                logger.debug("Not adding interface method {}", method);
                return;
            }

            GenericMethod genericMethod = new GenericMethod(method, rawClass);
            if (method.getDeclaringClass().equals(rawClass)) {
                cluster.addTestCall(genericMethod);
            }

            if (!CheapPurityAnalyzer.getInstance().isPure(method)) {
                cluster.addModifier(GenericClassFactory.get(rawClass),
                        genericMethod);
            }
            addDependencies(genericMethod, recursionLevel);
            GenericClass<?> retClass = GenericClassFactory.get(
                    method.getReturnType());

            // For the CUT, we may want to use primitives and Object
            // return types as generators
            if (!retClass.isVoid()) {
                cluster.addGenerator(retClass, genericMethod);
            }
        } else {
            logger.debug("Method cannot be used: " + method);

            // If we do reflection on private methods, we still need to
            // consider dependencies
            if (Properties.P_REFLECTION_ON_PRIVATE > 0
                    && method.getDeclaringClass().equals(rawClass)) {
                GenericMethod genericMethod = new GenericMethod(method,
                        rawClass);
                addDependencies(genericMethod, recursionLevel);
            }
        }
    }

    private void analyzeMethodDependency(GenericClass<?> clazz, Class<?> rawClass,
                                         Method method, int recursionLevel) {
        if (TestUsageChecker.canUse(method, rawClass)
                && !method.getName().equals("hashCode")) {
            logger.debug("Adding method " + clazz.getClassName() + "."
                    + method.getName()
                    + org.objectweb.asm.Type.getMethodDescriptor(method));

            GenericMethod genericMethod = new GenericMethod(method, clazz);
            try {
                addDependencies(genericMethod, recursionLevel + 1);
                if (!Properties.PURE_INSPECTORS) {
                    cluster.addModifier(GenericClassFactory.get(clazz),
                            genericMethod);
                } else {
                    if (!CheapPurityAnalyzer.getInstance().isPure(method)) {
                        cluster.addModifier(GenericClassFactory.get(clazz),
                                genericMethod);
                    }
                }

                GenericClass<?> retClass = GenericClassFactory.get(
                        method.getReturnType());

                // Only use as generator if its not any of the types with
                // special treatment
                if (!retClass.isPrimitive() && !retClass.isVoid()
                        && !retClass.isObject() && !retClass.isString()) {
                    cluster.addGenerator(retClass, genericMethod);
                }
            } catch (Throwable t) {
                logger.info("Error adding method " + method.getName()
                        + ": " + t.getMessage());
            }
        } else {
            logger.debug("Method cannot be used: " + method);
        }
    }

    // -----------------------------------------------------------------------
    // Field analysis
    // -----------------------------------------------------------------------

    private void analyzeFields(GenericClass<?> clazz, Class<?> rawClass,
                               AnalysisMode mode, int recursionLevel) {
        for (Field field : TestClusterUtils.getFields(rawClass)) {
            if (mode == AnalysisMode.TARGET) {
                analyzeFieldTarget(clazz, rawClass, field, recursionLevel);
            } else {
                analyzeFieldDependency(clazz, rawClass, field, recursionLevel);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void analyzeFieldTarget(GenericClass<?> clazz, Class<?> rawClass,
                                    Field field, int recursionLevel) {
        logger.info("Checking target field " + field);

        if (TestUsageChecker.canUse(field, rawClass)) {
            GenericField genericField = new GenericField(field, rawClass);

            addDependencies(genericField, recursionLevel);
            cluster.addGenerator(
                    GenericClassFactory.get(field.getGenericType()),
                    genericField);
            logger.debug("Adding field " + field);
            final boolean finalField = TestClusterUtils.isFinalField(field);
            if (!finalField) {
                logger.debug("Is not final");
                cluster.addModifier(GenericClassFactory.get(rawClass),
                        genericField);
            } else {
                logger.debug("Is final");
                if (Modifier.isStatic(field.getModifiers())
                        && !field.getType().isPrimitive()) {
                    logger.debug("Is static non-primitive");
                    /*
                     * With this we are trying to cover such cases:
                     *
                     * public static final DurationField INSTANCE = new
                     * MillisDurationField();
                     *
                     * private MillisDurationField() { super(); }
                     */
                    try {
                        Object o = field.get(null);
                        if (o == null) {
                            logger.info("Field is not yet initialized: "
                                    + field);
                        } else {
                            Class<?> actualClass = o.getClass();
                            logger.debug("Actual class is " + actualClass);
                            if (!actualClass.isAssignableFrom(
                                    genericField.getRawGeneratedType())
                                    && genericField.getRawGeneratedType()
                                            .isAssignableFrom(actualClass)) {
                                GenericField superClassField =
                                        new GenericField(field, rawClass);
                                cluster.addGenerator(
                                        GenericClassFactory.get(actualClass),
                                        superClassField);
                            }
                        }
                    } catch (IllegalAccessException e) {
                        logger.error(e.getMessage());
                    }
                }
            }
        } else {
            logger.debug("Can't use field " + field);
            // If reflection on private is used, we still need to make sure
            // dependencies are handled
            if (Properties.P_REFLECTION_ON_PRIVATE > 0) {
                if (Modifier.isPrivate(field.getModifiers())
                        && !field.isSynthetic()
                        && !field.getName().equals("serialVersionUID")
                        // primitives cannot be changed
                        && !(field.getType().isPrimitive())
                        // changing final strings also doesn't make much sense
                        && !(Modifier.isFinal(field.getModifiers())
                                && field.getType().equals(String.class))
                        // static fields lead to just too many problems...
                        && !Modifier.isStatic(field.getModifiers())) {
                    GenericField genericField = new GenericField(field,
                            rawClass);
                    addDependencies(genericField, recursionLevel);
                }
            }
        }
    }

    private void analyzeFieldDependency(GenericClass<?> clazz, Class<?> rawClass,
                                        Field field, int recursionLevel) {
        logger.debug("Checking field " + field);
        if (TestUsageChecker.canUse(field, rawClass)) {
            logger.debug("Adding field " + field + " for class " + clazz);
            try {
                GenericField genericField = new GenericField(field, clazz);
                GenericClass<?> retClass = GenericClassFactory.get(
                        field.getType());
                // Only use as generator if its not any of the types with
                // special treatment
                if (!retClass.isPrimitive() && !retClass.isObject()
                        && !retClass.isString()) {
                    cluster.addGenerator(
                            GenericClassFactory.get(field.getGenericType()),
                            genericField);
                }
                final boolean finalField = TestClusterUtils.isFinalField(field);
                if (!finalField) {
                    cluster.addModifier(clazz, genericField);
                    addDependencies(genericField, recursionLevel + 1);
                }
            } catch (Throwable t) {
                logger.info("Error adding field " + field.getName()
                        + ": " + t.getMessage());
            }
        } else {
            logger.debug("Field cannot be used: " + field);
        }
    }

    // -----------------------------------------------------------------------
    // Dependency tracking (moved from TestClusterGenerator)
    // -----------------------------------------------------------------------

    /**
     * Add dependencies for a constructor's parameter types.
     *
     * @param constructor the constructor to analyze
     * @param recursionLevel the current recursion level
     */
    void addDependencies(GenericConstructor constructor, int recursionLevel) {
        if (recursionLevel > Properties.CLUSTER_RECURSION) {
            logger.debug("Maximum recursion level reached, not adding "
                    + "dependencies of {}", constructor);
            return;
        }

        if (dependencyCache.contains(constructor)) {
            return;
        }

        logger.debug("Analyzing dependencies of " + constructor);
        dependencyCache.add(constructor);

        for (java.lang.reflect.Type parameterClass
                : constructor.getRawParameterTypes()) {
            logger.debug("Adding dependency " + parameterClass);
            addDependency(GenericClassFactory.get(parameterClass),
                    recursionLevel);
        }
    }

    /**
     * Add dependencies for a method's parameter types and (optionally)
     * return type.
     *
     * @param method the method to analyze
     * @param recursionLevel the current recursion level
     */
    void addDependencies(GenericMethod method, int recursionLevel) {
        if (recursionLevel > Properties.CLUSTER_RECURSION) {
            logger.debug("Maximum recursion level reached, not adding "
                    + "dependencies of {}", method);
            return;
        }

        if (dependencyCache.contains(method)) {
            return;
        }

        logger.debug("Analyzing dependencies of " + method);
        dependencyCache.add(method);

        for (java.lang.reflect.Type parameter : method.getRawParameterTypes()) {
            logger.debug("Current parameter " + parameter);
            GenericClass<?> parameterClass = GenericClassFactory.get(parameter);
            if (parameterClass.isPrimitive() || parameterClass.isString()) {
                continue;
            }

            logger.debug("Adding dependency " + parameterClass.getClassName());
            addDependency(parameterClass, recursionLevel);
        }

        // If mocking is enabled, also return values are dependencies
        // as we might attempt to mock the method
        //
        // Only look at the return values of direct dependencies as the
        // number of dependencies otherwise might explode
        if (Properties.P_FUNCTIONAL_MOCKING > 0 && recursionLevel == 1) {
            GenericClass<?> returnClass = method.getGeneratedClass();
            if (!returnClass.isPrimitive() && !returnClass.isString()) {
                addDependency(returnClass, recursionLevel);
            }
        }
    }

    /**
     * Add dependencies for a field's type.
     *
     * @param field the field to analyze
     * @param recursionLevel the current recursion level
     */
    void addDependencies(GenericField field, int recursionLevel) {
        if (recursionLevel > Properties.CLUSTER_RECURSION) {
            logger.debug("Maximum recursion level reached, not adding "
                    + "dependencies of {}", field);
            return;
        }

        if (dependencyCache.contains(field)) {
            return;
        }

        if (field.getField().getType().isPrimitive()
                || field.getField().getType().equals(String.class)) {
            return;
        }

        logger.debug("Analyzing dependencies of " + field);
        dependencyCache.add(field);

        logger.debug("Adding dependency " + field.getName());
        addDependency(GenericClassFactory.get(field.getGenericFieldType()),
                recursionLevel);
    }

    /**
     * Add a single class as a dependency, performing mock replacement and
     * concrete class resolution.
     *
     * @param clazz the class to add as a dependency
     * @param recursionLevel the current recursion level
     */
    void addDependency(GenericClass<?> clazz, int recursionLevel) {

        clazz = clazz.getRawGenericClass();

        if (analyzedClasses.contains(clazz.getRawClass())) {
            return;
        }

        if (clazz.isPrimitive()) {
            return;
        }

        if (clazz.isString()) {
            return;
        }

        if (clazz.getRawClass().equals(Enum.class)) {
            return;
        }

        if (clazz.isArray()) {
            addDependency(GenericClassFactory.get(clazz.getComponentType()),
                    recursionLevel);
            return;
        }

        if (!TestUsageChecker.canUse(clazz.getRawClass())) {
            return;
        }

        Class<?> mock = MockList.getMockClass(
                clazz.getRawClass().getCanonicalName());
        if (mock != null) {
            /*
             * If we are mocking this class, then such class should not
             * be used in the generated JUnit test cases, but rather its
             * mock.
             */
            logger.debug("Adding mock {} instead of {}", mock, clazz);
            clazz = GenericClassFactory.get(mock);
        } else {
            if (!TestClusterUtils.checkIfCanUse(clazz.getClassName())) {
                return;
            }
        }

        for (DependencyPair pair : dependencies) {
            if (pair.getDependencyClass().equals(clazz)) {
                return;
            }
        }
        if (analyzedAbstractClasses.contains(clazz)) {
            return;
        }

        logger.debug("Getting concrete classes for " + clazz.getClassName());
        ConstantPoolManager.getInstance().addNonSUTConstant(
                Type.getType(clazz.getRawClass()));
        List<Class<?>> actualClasses = new ArrayList<>(
                ConcreteClassAnalyzer.getInstance().getConcreteClasses(
                        clazz.getRawClass(), inheritanceTree));
        logger.debug("Concrete classes for " + clazz.getClassName()
                + ": " + actualClasses.size());

        analyzedAbstractClasses.add(clazz);
        for (Class<?> targetClass : actualClasses) {
            logger.debug("Adding concrete class: " + targetClass);
            dependencies.add(new DependencyPair(recursionLevel, targetClass));
        }
    }
}
