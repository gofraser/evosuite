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
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CardBuildSupport {

    private static final GoalDescriptionMapper GOAL_DESCRIPTION_MAPPER = new GoalDescriptionMapper();

    private CardBuildSupport() {
        // utility class
    }

    static String methodKey(TestFitnessFunction goal) {
        if (goal == null) {
            return null;
        }
        String method = GOAL_DESCRIPTION_MAPPER.describeMethodOperation(goal).getExecutionKey();
        if (method.isEmpty()) {
            return null;
        }
        return method;
    }

    static Map<String, List<TestFitnessFunction>> groupGoalsByType(
            Map<String, List<TestFitnessFunction>> goalsByMethod) {
        Map<String, List<TestFitnessFunction>> goalsByType = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return goalsByType;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            int idx = methodKey.lastIndexOf('.');
            if (idx <= 0) {
                continue;
            }
            String typeName = methodKey.substring(0, idx);
            List<TestFitnessFunction> bucket = goalsByType.computeIfAbsent(typeName,
                    ignored -> new ArrayList<>());
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                bucket.addAll(entry.getValue());
            }
        }
        return goalsByType;
    }

    static void mergeGoals(List<TestFitnessFunction> destination,
                           List<TestFitnessFunction> goals) {
        if (destination == null || goals == null || goals.isEmpty()) {
            return;
        }
        for (TestFitnessFunction goal : goals) {
            if (goal != null && !destination.contains(goal)) {
                destination.add(goal);
            }
        }
    }

    static List<TestFitnessFunction> deduplicateGoals(List<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestFitnessFunction> deduplicated = new ArrayList<>();
        mergeGoals(deduplicated, goals);
        return deduplicated;
    }

    static MethodPromptContext describeMethodContext(String methodKey, List<TestFitnessFunction> relatedGoals) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        String typeName = "";
        if (relatedGoals != null) {
            for (TestFitnessFunction goal : relatedGoals) {
                GoalDescriptionMapper.OperationTarget target = GOAL_DESCRIPTION_MAPPER.describeMethodOperation(goal);
                if (!target.getDisplayLabel().isEmpty()) {
                    labels.add(target.getDisplayLabel());
                }
                if (typeName.isEmpty()) {
                    typeName = target.getClassName();
                }
            }
        }
        if (typeName.isEmpty() && methodKey != null) {
            int idx = methodKey.lastIndexOf('.');
            if (idx > 0) {
                typeName = methodKey.substring(0, idx);
            }
        }
        String displayLabel = labels.size() == 1 ? labels.iterator().next()
                : (methodKey == null ? "" : methodKey);
        return new MethodPromptContext(methodKey, displayLabel, typeName, new ArrayList<>(labels));
    }

    static void addOverloadEvidence(List<String> evidence, MethodPromptContext methodContext) {
        if (evidence == null || methodContext == null || methodContext.overloadLabels.size() <= 1) {
            return;
        }
        evidence.add("Grouped uncovered overloads: " + String.join(", ", methodContext.overloadLabels) + ".");
    }

    static double normalizeCount(double count, double divisor) {
        if (Double.isNaN(count) || Double.isInfinite(count) || divisor <= 0.0) {
            return 0.0;
        }
        if (count <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, count / divisor);
    }

    static double barrierConfidence(int attempts) {
        if (attempts <= 0) {
            return 0.0;
        }
        return 1.0 - Math.pow(0.5, Math.max(0, attempts - 1));
    }

    static double leverageAwareImpact(Collection<TestFitnessFunction> relatedGoals, double goalDivisor) {
        double impact = normalizeCount(relatedGoals == null ? 0 : relatedGoals.size(), goalDivisor);
        int distinctMethods = distinctGoalMethods(relatedGoals);
        int distinctTypes = distinctGoalTypes(relatedGoals);
        impact += 0.20 * normalizeCount(Math.max(0, distinctMethods - 1), 3.0);
        impact += 0.15 * normalizeCount(Math.max(0, distinctTypes - 1), 2.0);
        return Math.min(1.0, impact);
    }

    static int distinctGoalMethods(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return 0;
        }
        Set<String> methods = new LinkedHashSet<>();
        for (TestFitnessFunction goal : goals) {
            String key = methodKey(goal);
            if (key != null && !key.isEmpty()) {
                methods.add(key);
            }
        }
        return methods.size();
    }

    static int distinctGoalTypes(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return 0;
        }
        Set<String> types = new LinkedHashSet<>();
        for (TestFitnessFunction goal : goals) {
            GoalDescriptionMapper.OperationTarget target = GOAL_DESCRIPTION_MAPPER.describeMethodOperation(goal);
            if (target.getClassName() != null && !target.getClassName().isEmpty()) {
                types.add(target.getClassName());
            }
        }
        return types.size();
    }

    static String baseMethodNameFromExecutionKey(String executionKey) {
        if (executionKey == null || executionKey.isEmpty()) {
            return "";
        }
        int dot = executionKey.lastIndexOf('.');
        if (dot < 0 || dot >= executionKey.length() - 1) {
            return executionKey;
        }
        return executionKey.substring(dot + 1);
    }

    static boolean isSyntheticCompilerMethod(String baseMethodName) {
        if (baseMethodName == null || baseMethodName.isEmpty()) {
            return false;
        }
        return baseMethodName.startsWith("access$")
                || baseMethodName.startsWith("lambda$")
                || baseMethodName.startsWith("$deserializeLambda$")
                || baseMethodName.startsWith("$SWITCH_TABLE$");
    }

    static String rootCauseKeyForType(String typeName, String fallback) {
        if (typeName != null && !typeName.isEmpty()) {
            return typeName;
        }
        return fallback == null ? "" : fallback;
    }

    static String dominantException(Map<String, Integer> counts) {
        return ProblemCardLabels.dominantLabel(counts);
    }

    static double failureRate(int failures, int successes) {
        return ProblemCardLabels.failureRate(failures, successes);
    }

    static void addBootstrapDependencyEvidence(List<String> evidence,
                                               String blockedType,
                                               String entryPointLabel) {
        if (evidence == null || blockedType == null || blockedType.isEmpty()
                || entryPointLabel == null || entryPointLabel.isEmpty()) {
            return;
        }
        if (!entryPointLabel.startsWith(blockedType + ".") && !entryPointLabel.startsWith(blockedType + "(")) {
            evidence.add("Observed bootstrap failures came from dependency/helper entry points before reaching "
                    + blockedType + ".");
        }
    }

    static boolean isLikelyIndirectHelperType(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }
        int idx = typeName.lastIndexOf('$');
        if (idx < 0 || idx >= typeName.length() - 1) {
            return false;
        }
        String suffix = typeName.substring(idx + 1);
        // Anonymous / local classes: $1, $1$Helper, ...
        if (suffix.matches("\\d+.*")) {
            return true;
        }
        // Lambda metaclass synthetics: Outer$$Lambda$NNN
        if (suffix.startsWith("Lambda$") || suffix.startsWith("$Lambda$")) {
            return true;
        }
        // Named nested helpers that are conventionally reached only through an outer
        // entrypoint. Kept to a small allow-list so public nested API types stay direct.
        String lowered = suffix.toLowerCase(java.util.Locale.ROOT);
        return lowered.equals("builder")
                || lowered.endsWith("builder")
                || lowered.equals("helper")
                || lowered.endsWith("helper")
                || lowered.equals("holder")
                || lowered.endsWith("holder")
                || lowered.equals("impl")
                || lowered.endsWith("impl")
                || lowered.equals("internal")
                || lowered.endsWith("internal")
                || lowered.equals("support");
    }

    static String outerTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        int idx = typeName.indexOf('$');
        if (idx <= 0) {
            return typeName;
        }
        return typeName.substring(0, idx);
    }
}
