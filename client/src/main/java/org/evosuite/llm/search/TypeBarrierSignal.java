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
import org.evosuite.testcase.TestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-type acquisition / setup signal aggregated from a population snapshot.
 * Tracks successful and failing constructors, factories, and setup-method
 * calls so the type-family card builders can reason about whether a type is
 * "stuck at construction" versus "stuck during setup".
 */
final class TypeBarrierSignal {
    final String typeName;
    final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
    int constructorAttempts;
    int constructorSuccesses;
    int progressedConstructorSuccesses;
    int factoryAttempts;
    int factorySuccesses;
    int progressedFactorySuccesses;
    int setupAttempts;
    final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
    final Map<String, Integer> successfulConstructorLabelCounts = new LinkedHashMap<>();
    final Map<String, Integer> successfulFactoryLabelCounts = new LinkedHashMap<>();
    final Map<String, Integer> successfulSetupMethodCounts = new HashMap<>();
    final Set<String> failingSetupMethods = new LinkedHashSet<>();
    final Map<String, Integer> failingSetupMethodCounts = new LinkedHashMap<>();
    final Map<String, String> failingSetupLabelsByKey = new LinkedHashMap<>();
    final Map<String, String> successfulMethodLabelsByKey = new LinkedHashMap<>();
    final Map<String, Integer> failingSetupLabelCounts = new LinkedHashMap<>();
    final Map<String, Integer> successfulMethodLabelCounts = new LinkedHashMap<>();
    final Map<String, Integer> failingConstructorLabelCounts = new LinkedHashMap<>();
    final Map<String, Integer> failingFactoryLabelCounts = new LinkedHashMap<>();
    TestCase reusablePrefixTest;

    TypeBarrierSignal(String typeName) {
        this.typeName = typeName;
    }

    void addRelatedGoals(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return;
        }
        for (TestFitnessFunction goal : goals) {
            if (goal != null && !relatedGoals.contains(goal)) {
                relatedGoals.add(goal);
            }
        }
    }

    void recordSuccessfulConstructor(String label, boolean progressedBeyondCreation) {
        constructorSuccesses++;
        ProblemCardLabels.incrementLabel(successfulConstructorLabelCounts, label);
        if (progressedBeyondCreation) {
            progressedConstructorSuccesses++;
        }
    }

    void considerReusablePrefixTest(TestCase test) {
        if (test == null || test.size() <= 0) {
            return;
        }
        if (reusablePrefixTest == null || test.size() < reusablePrefixTest.size()) {
            reusablePrefixTest = test;
        }
    }

    void recordFailingConstructor(String label) {
        constructorAttempts++;
        ProblemCardLabels.incrementLabel(failingConstructorLabelCounts, label);
    }

    void recordSuccessfulFactory(String label, boolean progressedBeyondCreation) {
        factorySuccesses++;
        ProblemCardLabels.incrementLabel(successfulFactoryLabelCounts, label);
        if (progressedBeyondCreation) {
            progressedFactorySuccesses++;
        }
    }

    void recordFailingFactory(String label) {
        factoryAttempts++;
        ProblemCardLabels.incrementLabel(failingFactoryLabelCounts, label);
    }

    void recordSuccessfulSetupMethod(String methodKey, String displayLabel) {
        if (methodKey == null || methodKey.isEmpty()) {
            return;
        }
        successfulSetupMethodCounts.put(methodKey,
                successfulSetupMethodCounts.getOrDefault(methodKey, 0) + 1);
        if (displayLabel != null && !displayLabel.isEmpty()) {
            successfulMethodLabelsByKey.put(methodKey, displayLabel);
        }
        ProblemCardLabels.incrementLabel(successfulMethodLabelCounts, displayLabel);
    }

    void recordFailingSetupMethod(String methodKey, String displayLabel) {
        if (methodKey == null || methodKey.isEmpty()) {
            return;
        }
        setupAttempts++;
        failingSetupMethods.add(methodKey);
        failingSetupMethodCounts.put(methodKey, failingSetupMethodCounts.getOrDefault(methodKey, 0) + 1);
        if (displayLabel != null && !displayLabel.isEmpty()) {
            failingSetupLabelsByKey.put(methodKey, displayLabel);
        }
        ProblemCardLabels.incrementLabel(failingSetupLabelCounts, displayLabel);
    }

    int countSuccessfulExecutionsForFailingSetupMethods() {
        int total = 0;
        for (String methodKey : failingSetupMethods) {
            total += successfulSetupMethodCounts.getOrDefault(methodKey, 0);
        }
        return total;
    }

    int totalConstructionAttempts() {
        return constructorAttempts + factoryAttempts;
    }

    int totalConstructionSuccesses() {
        return constructorSuccesses + factorySuccesses;
    }

    int totalConstructionSuccessesWithProgress() {
        return progressedConstructorSuccesses + progressedFactorySuccesses;
    }

    int totalConstructionSuccessesWithoutProgress() {
        return Math.max(0, totalConstructionSuccesses() - totalConstructionSuccessesWithProgress());
    }

    boolean hasAcquisitionProgressBeyondCreation() {
        return totalConstructionSuccessesWithProgress() > 0;
    }

    double acquisitionFailureRate() {
        double total = totalConstructionAttempts() + totalConstructionSuccesses();
        if (total <= 0.0) {
            return 0.0;
        }
        return ((double) totalConstructionAttempts()) / total;
    }

    boolean hasSuccessfulAcquisition() {
        return totalConstructionSuccesses() > 0;
    }

    boolean hasReachabilityEvidence() {
        return hasSuccessfulAcquisition() || !successfulMethodLabelCounts.isEmpty()
                || totalConstructionAttempts() > 0;
    }

    boolean hasReusableSuccessfulPrefix(String excludedMethodKey) {
        if (!hasAcquisitionProgressBeyondCreation() && successfulMethodLabelCounts.isEmpty()) {
            return false;
        }
        return !describeReusableSuccessfulPrefix(1, excludedMethodKey).isEmpty();
    }

    String dominantFailingAcquisitionLabel() {
        Map<String, Integer> combined = new LinkedHashMap<>();
        ProblemCardLabels.mergeLabelCounts(combined, failingConstructorLabelCounts);
        ProblemCardLabels.mergeLabelCounts(combined, failingFactoryLabelCounts);
        return ProblemCardLabels.dominantLabel(combined);
    }

    String dominantFailingSetupMethodKey() {
        return ProblemCardLabels.dominantLabel(failingSetupMethodCounts);
    }

    String dominantFailingSetupLabel() {
        String methodKey = dominantFailingSetupMethodKey();
        if (methodKey != null && !methodKey.isEmpty()) {
            String label = failingSetupLabelsByKey.get(methodKey);
            if (label != null && !label.isEmpty()) {
                return label;
            }
        }
        return ProblemCardLabels.dominantLabel(failingSetupLabelCounts);
    }

    int distinctFailingSetupSteps() {
        return failingSetupMethodCounts.size();
    }

    String describeFailingSetupLabels(int maxLabels) {
        return String.join(", ", ProblemCardLabels.topLabels(failingSetupLabelCounts, maxLabels));
    }

    int failingExecutionsForSetupMethod(String methodKey) {
        if (methodKey == null || methodKey.isEmpty()) {
            return 0;
        }
        return failingSetupMethodCounts.getOrDefault(methodKey, 0);
    }

    int successfulExecutionsForSetupMethod(String methodKey) {
        if (methodKey == null || methodKey.isEmpty()) {
            return 0;
        }
        return successfulSetupMethodCounts.getOrDefault(methodKey, 0);
    }

    String describeReusableSuccessfulPrefix(int maxLabels, String excludedMethodKey) {
        if (maxLabels <= 0) {
            return "";
        }
        Map<String, Integer> prefixLabels = new LinkedHashMap<>();
        ProblemCardLabels.mergeLabelCounts(prefixLabels, successfulConstructorLabelCounts);
        ProblemCardLabels.mergeLabelCounts(prefixLabels, successfulFactoryLabelCounts);
        for (Map.Entry<String, Integer> entry : successfulSetupMethodCounts.entrySet()) {
            if (excludedMethodKey != null && excludedMethodKey.equals(entry.getKey())) {
                continue;
            }
            String label = successfulMethodLabelsByKey.get(entry.getKey());
            if (label == null || label.isEmpty()) {
                continue;
            }
            prefixLabels.put(label, prefixLabels.getOrDefault(label, 0) + entry.getValue());
        }
        return String.join(", ", ProblemCardLabels.topLabels(prefixLabels, maxLabels));
    }

    String prefixFingerprint(String excludedMethodKey) {
        return describeReusableSuccessfulPrefix(2, excludedMethodKey).replace(' ', '_');
    }

    String describeSuccessfulMethodLabels(int maxLabels, String excludedMethodKey) {
        if (successfulMethodLabelsByKey.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> entry : successfulMethodLabelsByKey.entrySet()) {
            if (excludedMethodKey != null && excludedMethodKey.equals(entry.getKey())) {
                continue;
            }
            String label = entry.getValue();
            if (label == null || label.isEmpty()) {
                continue;
            }
            labels.add(label);
            if (labels.size() >= maxLabels) {
                break;
            }
        }
        return String.join(", ", labels);
    }
}
