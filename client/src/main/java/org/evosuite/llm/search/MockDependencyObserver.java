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
package org.evosuite.llm.search;

import org.evosuite.TestGenerationContext;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Goal-driven observer for {@code MOCK_NEEDED_DEPENDENCY} cards.
 *
 * <p>Detection is observational and indirection-agnostic: for each uncovered
 * goal method it reads the parameter types from the method descriptor and flags
 * those for which the population never materialized any assignable value (no
 * concrete instance, no functional mock). That holds whether the cause is a
 * direct interface parameter or a concrete parameter whose own construction
 * transitively requires an unconstructable interface/abstract collaborator.
 *
 * <p>A depth-bounded reflective walk of constructor parameter types is used
 * only to <em>name</em> the deepest blocking collaborator for the evidence; it
 * never gates whether a card fires for a direct interface/abstract parameter.
 */
final class MockDependencyObserver {

    private static final int MAX_CHAIN_DEPTH = 2;

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();

    Map<String, MockDependencySignal> observe(Map<String, List<TestFitnessFunction>> goalsByMethod,
                                              List<TestChromosome> snapshot,
                                              ExtractorTelemetry telemetry) {
        Map<String, MockDependencySignal> signals = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return signals;
        }
        Set<Class<?>> materialized = collectMaterializedClasses(snapshot);
        ClassLoader classLoader = resolveClassLoader();

        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            List<TestFitnessFunction> goals = entry.getValue();
            if (goals == null || goals.isEmpty()) {
                continue;
            }
            TestFitnessFunction representative = goals.get(0);
            String descriptor = extractDescriptor(representative);
            if (descriptor.isEmpty()) {
                continue;
            }
            Type[] argumentTypes;
            try {
                argumentTypes = Type.getArgumentTypes(descriptor);
            } catch (RuntimeException e) {
                continue;
            }
            String methodLabel = goalDescriptionMapper.describeMethodOperation(representative).getDisplayLabel();
            for (Type argumentType : argumentTypes) {
                if (argumentType == null || argumentType.getSort() != Type.OBJECT) {
                    continue;
                }
                Class<?> parameterClass = resolveClass(argumentType.getClassName(), classLoader);
                if (parameterClass == null || isLowValueDependencyType(parameterClass)) {
                    continue;
                }
                if (isMaterialized(materialized, parameterClass)) {
                    // A concrete instance or functional mock of this collaborator was supplied.
                    increment(telemetry, ExtractorCandidateMetric.MOCK_NEEDED_DEPENDENCY_SUPPRESSED_MATERIALIZED);
                    continue;
                }
                if (isInterfaceOrAbstract(parameterClass)) {
                    recordSignal(signals, parameterClass.getName(), methodLabel, goals,
                            true, "", parameterClass, telemetry);
                    continue;
                }
                // Concrete parameter that was never materialized: only treat as a
                // mock-needed barrier if a bounded walk attributes it to an
                // unconstructable interface/abstract collaborator.
                Class<?> leaf = findUnconstructableLeaf(parameterClass, MAX_CHAIN_DEPTH,
                        new LinkedHashSet<>());
                if (leaf != null && !isMaterialized(materialized, leaf)) {
                    recordSignal(signals, leaf.getName(), methodLabel, goals,
                            false, parameterClass.getName(), leaf, telemetry);
                }
            }
        }
        return signals;
    }

    private void recordSignal(Map<String, MockDependencySignal> signals,
                              String dependencyTypeName,
                              String methodLabel,
                              List<TestFitnessFunction> goals,
                              boolean directParameter,
                              String viaConcreteType,
                              Class<?> dependencyClass,
                              ExtractorTelemetry telemetry) {
        MockDependencySignal signal = signals.get(dependencyTypeName);
        boolean firstSighting = signal == null;
        if (firstSighting) {
            signal = new MockDependencySignal(dependencyTypeName);
            signal.noGenerator = !hasGenerator(dependencyClass);
            signals.put(dependencyTypeName, signal);
            increment(telemetry, ExtractorCandidateMetric.MOCK_NEEDED_DEPENDENCY_CANDIDATES);
        }
        signal.directParameter |= directParameter;
        if (methodLabel != null && !methodLabel.isEmpty()) {
            signal.requiringMethodLabels.add(methodLabel);
        }
        if (!directParameter && viaConcreteType != null && !viaConcreteType.isEmpty()) {
            signal.viaConcreteTypes.add(viaConcreteType);
        }
        signal.addRelatedGoals(goals);
    }

    /**
     * Bounded reflective search for the deepest interface/abstract type that
     * blocks construction of {@code clazz}. Returns {@code null} when {@code clazz}
     * is plausibly constructible within the depth bound.
     */
    private Class<?> findUnconstructableLeaf(Class<?> clazz, int depth, Set<Class<?>> visiting) {
        if (clazz == null || depth < 0 || !visiting.add(clazz)) {
            return null;
        }
        try {
            List<Constructor<?>> constructors = candidateConstructors(clazz);
            if (constructors.isEmpty()) {
                // No accessible constructor: an uninstantiability concern handled
                // by other cards, not a missing-collaborator one.
                return null;
            }
            Class<?> blockingLeaf = null;
            for (Constructor<?> constructor : constructors) {
                Class<?> leafForConstructor = null;
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    if (isTriviallyConstructible(parameterType)) {
                        continue;
                    }
                    if (isInterfaceOrAbstract(parameterType)) {
                        if (!hasGenerator(parameterType)) {
                            leafForConstructor = parameterType;
                            break;
                        }
                        continue;
                    }
                    Class<?> deeper = findUnconstructableLeaf(parameterType, depth - 1, visiting);
                    if (deeper != null) {
                        leafForConstructor = deeper;
                        break;
                    }
                }
                if (leafForConstructor == null) {
                    // This constructor is satisfiable, so clazz is constructible.
                    return null;
                }
                if (blockingLeaf == null) {
                    blockingLeaf = leafForConstructor;
                }
            }
            return blockingLeaf;
        } catch (RuntimeException | LinkageError e) {
            return null;
        } finally {
            visiting.remove(clazz);
        }
    }

    private Set<Class<?>> collectMaterializedClasses(List<TestChromosome> snapshot) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (snapshot == null) {
            return classes;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            TestCase test = chromosome.getTestCase();
            int size = ExtractorObservationSupport.safeTestSize(test);
            for (int i = 0; i < size; i++) {
                Statement statement = ExtractorObservationSupport.statementAt(test, i);
                if (statement == null) {
                    continue;
                }
                try {
                    Class<?> returnClass = statement.getReturnClass();
                    if (returnClass != null) {
                        classes.add(returnClass);
                    }
                } catch (RuntimeException e) {
                    // Ignore statements whose return type cannot be resolved.
                }
            }
        }
        return classes;
    }

    private boolean isMaterialized(Set<Class<?>> materialized, Class<?> dependency) {
        if (materialized.isEmpty() || dependency == null) {
            return false;
        }
        for (Class<?> candidate : materialized) {
            if (candidate != null && dependency.isAssignableFrom(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGenerator(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        try {
            return TestCluster.getInstance().hasGenerator(GenericClassFactory.get(clazz));
        } catch (RuntimeException | LinkageError e) {
            // No usable cluster available (e.g. in isolated unit tests): treat as
            // "no generator known" so the observational signal still applies.
            return false;
        }
    }

    private ClassLoader resolveClassLoader() {
        try {
            ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            if (loader != null) {
                return loader;
            }
        } catch (RuntimeException | LinkageError e) {
            // Fall through to this class's loader.
        }
        return MockDependencyObserver.class.getClassLoader();
    }

    private Class<?> resolveClass(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable primary) {
            try {
                return Class.forName(className, false, MockDependencyObserver.class.getClassLoader());
            } catch (Throwable secondary) {
                return null;
            }
        }
    }

    private String extractDescriptor(TestFitnessFunction goal) {
        if (goal == null) {
            return "";
        }
        String rawMethod = goal.getTargetMethod();
        if (rawMethod == null) {
            return "";
        }
        int open = rawMethod.indexOf('(');
        int close = rawMethod.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return "";
        }
        return rawMethod.substring(open, close + 1);
    }

    private boolean isInterfaceOrAbstract(Class<?> clazz) {
        return clazz != null
                && !clazz.isArray()
                && !clazz.isPrimitive()
                && (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()));
    }

    private boolean isTriviallyConstructible(Class<?> clazz) {
        if (clazz == null || isLowValueDependencyType(clazz)) {
            return true;
        }
        if (isInterfaceOrAbstract(clazz)) {
            return false;
        }
        for (Constructor<?> constructor : candidateConstructors(clazz)) {
            if (constructor.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Constructors EvoSuite could plausibly use to build a value: declared
     * constructors that are not private, ordered fewest-parameters first.
     */
    private List<Constructor<?>> candidateConstructors(Class<?> clazz) {
        List<Constructor<?>> ordered = new ArrayList<>();
        if (clazz == null) {
            return ordered;
        }
        try {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                if (!Modifier.isPrivate(constructor.getModifiers())) {
                    ordered.add(constructor);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return ordered;
        }
        ordered.sort((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()));
        return ordered;
    }

    private boolean isLowValueDependencyType(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray() || clazz.isEnum()) {
            return true;
        }
        if (clazz == Object.class || clazz == String.class || clazz == CharSequence.class
                || clazz == Class.class || Number.class == clazz) {
            return true;
        }
        String name = clazz.getName();
        return name.equals("java.lang.Boolean")
                || name.equals("java.lang.Character")
                || name.equals("java.lang.Byte")
                || name.equals("java.lang.Short")
                || name.equals("java.lang.Integer")
                || name.equals("java.lang.Long")
                || name.equals("java.lang.Float")
                || name.equals("java.lang.Double");
    }

    private void increment(ExtractorTelemetry telemetry, ExtractorCandidateMetric metric) {
        if (telemetry != null) {
            telemetry.increment(metric);
        }
    }
}
