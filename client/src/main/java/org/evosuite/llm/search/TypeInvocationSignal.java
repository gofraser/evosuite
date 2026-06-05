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

import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks all blocked goal methods on a single type plus whether any direct
 * (non-acquisition) invocation was observed. Drives the "indirect invocation
 * gap" sub-card of {@link ProblemCardType#INDIRECT_REACHABILITY_BARRIER}.
 */
final class TypeInvocationSignal {
    final String typeName;
    final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
    final Set<String> methodKeys = new LinkedHashSet<>();
    final Map<String, String> methodLabelsByKey = new LinkedHashMap<>();
    int directAttempts;

    TypeInvocationSignal(String typeName) {
        this.typeName = typeName == null ? "" : typeName;
    }

    void recordMethod(String methodKey,
                      String methodLabel,
                      Collection<TestFitnessFunction> goals,
                      AttemptStats stats) {
        if (methodKey != null && !methodKey.isEmpty()) {
            methodKeys.add(methodKey);
        }
        if (methodKey != null && !methodKey.isEmpty() && methodLabel != null && !methodLabel.isEmpty()) {
            methodLabelsByKey.put(methodKey, methodLabel);
        }
        if (goals != null) {
            for (TestFitnessFunction goal : goals) {
                if (goal != null && !relatedGoals.contains(goal)) {
                    relatedGoals.add(goal);
                }
            }
        }
        if (stats != null) {
            directAttempts += Math.max(0, stats.attempts);
        }
    }

    String describeBlockedMethodLabels(int maxLabels) {
        if (maxLabels <= 0 || methodKeys.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String methodKey : methodKeys) {
            String label = methodLabelsByKey.get(methodKey);
            labels.add(label == null || label.isEmpty() ? methodKey : label);
            if (labels.size() >= maxLabels) {
                break;
            }
        }
        return String.join(", ", labels);
    }
}
