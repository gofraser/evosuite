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

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.environment.AccessedEnvironment;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observes external-environment reads (file system, network, system properties)
 * for uncovered goal methods, using EvoSuite's per-test runtime-mock
 * instrumentation.
 *
 * <p>The signal is per-test and reliable at extraction time: file/network access
 * comes from {@link TestCase#getAccessedEnvironment()} and read system properties
 * from {@link ExecutionResult#getReadProperties()}. A barrier is attributed to a
 * goal method when a test that <em>reached</em> the method (its covered methods
 * include the goal method) also touched the environment while the goal stayed
 * uncovered — i.e. the environment was never seeded with the values needed to
 * drive the goal.
 *
 * <p>Time/random access is intentionally out of scope: those are tracked only by
 * process-global statics that cannot be attributed to a specific test/goal here.
 */
final class EnvironmentBarrierObserver {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();

    Map<String, EnvironmentBarrierSignal> observe(Map<String, List<TestFitnessFunction>> goalsByMethod,
                                                  List<TestChromosome> snapshot,
                                                  ExtractorTelemetry telemetry) {
        Map<String, EnvironmentBarrierSignal> signals = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            TestCase test = chromosome.getTestCase();
            if (test == null) {
                continue;
            }

            Set<String> files = new LinkedHashSet<>();
            Set<String> remoteResources = new LinkedHashSet<>();
            boolean networkAccessed = false;
            AccessedEnvironment environment = safeAccessedEnvironment(test);
            if (environment != null) {
                try {
                    addAll(files, environment.getViewOfAccessedFiles());
                    addAll(remoteResources, environment.getViewOfRemoteURLs());
                    networkAccessed = environment.isNetworkAccessed();
                } catch (RuntimeException e) {
                    // Treat an unreadable environment snapshot as no access.
                }
            }
            Set<String> properties = new LinkedHashSet<>();
            addAll(properties, safeReadProperties(result));

            if (files.isEmpty() && properties.isEmpty() && remoteResources.isEmpty() && !networkAccessed) {
                continue;
            }

            Set<String> reachedMethodKeys = reachedGoalMethods(result, goalsByMethod.keySet());
            for (String methodKey : reachedMethodKeys) {
                List<TestFitnessFunction> goals = goalsByMethod.get(methodKey);
                if (goals == null || goals.isEmpty()) {
                    continue;
                }
                EnvironmentBarrierSignal signal = signals.get(methodKey);
                if (signal == null) {
                    signal = new EnvironmentBarrierSignal(methodKey);
                    signal.methodLabel = goalDescriptionMapper.describeMethodOperation(goals.get(0)).getDisplayLabel();
                    signals.put(methodKey, signal);
                    if (telemetry != null) {
                        telemetry.increment(ExtractorCandidateMetric.ENVIRONMENT_BARRIER_CANDIDATES);
                    }
                }
                signal.observingTests++;
                signal.accessedFiles.addAll(files);
                signal.readProperties.addAll(properties);
                signal.remoteResources.addAll(remoteResources);
                signal.networkAccessed |= networkAccessed;
                signal.addRelatedGoals(goals);
            }
        }
        return signals;
    }

    private Set<String> reachedGoalMethods(ExecutionResult result, Set<String> goalMethodKeys) {
        Set<String> reached = new LinkedHashSet<>();
        ExecutionTrace trace = result.getTrace();
        if (trace == null) {
            return reached;
        }
        Set<String> coveredMethods = trace.getCoveredMethods();
        if (coveredMethods == null || coveredMethods.isEmpty()) {
            return reached;
        }
        for (String coveredMethod : coveredMethods) {
            String key = ExtractorObservationSupport.coveredMethodKey(coveredMethod);
            if (!key.isEmpty() && goalMethodKeys.contains(key)) {
                reached.add(key);
            }
        }
        return reached;
    }

    private AccessedEnvironment safeAccessedEnvironment(TestCase test) {
        try {
            return test.getAccessedEnvironment();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Set<String> safeReadProperties(ExecutionResult result) {
        try {
            return result.getReadProperties();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void addAll(Set<String> destination, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                destination.add(value);
            }
        }
    }
}
