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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-goal-method signal aggregating external-environment reads (file system,
 * network, system properties) observed in tests that reached the method while
 * its goals stayed uncovered.
 */
final class EnvironmentBarrierSignal {

    final String methodKey;
    String methodLabel = "";
    final Set<String> accessedFiles = new LinkedHashSet<>();
    final Set<String> readProperties = new LinkedHashSet<>();
    final Set<String> remoteResources = new LinkedHashSet<>();
    boolean networkAccessed;
    int observingTests;
    final List<TestFitnessFunction> relatedGoals = new ArrayList<>();

    EnvironmentBarrierSignal(String methodKey) {
        this.methodKey = methodKey;
    }

    void addRelatedGoals(List<TestFitnessFunction> goals) {
        CardBuildSupport.mergeGoals(relatedGoals, goals);
    }

    boolean hasFileAccess() {
        return !accessedFiles.isEmpty();
    }

    boolean hasPropertyAccess() {
        return !readProperties.isEmpty();
    }

    boolean hasNetworkAccess() {
        return networkAccessed || !remoteResources.isEmpty();
    }

    boolean hasAnyAccess() {
        return hasFileAccess() || hasPropertyAccess() || hasNetworkAccess();
    }

    String signalKey() {
        return methodKey;
    }
}
