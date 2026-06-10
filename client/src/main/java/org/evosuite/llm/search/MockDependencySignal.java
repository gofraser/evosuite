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
 * Per-collaborator signal aggregating goal methods that depend on an
 * interface/abstract type which the search never supplied (no concrete instance
 * and no functional mock ever materialized in the population).
 *
 * <p>Keyed by the collaborator type the LLM should provide. For the indirect
 * case — a concrete parameter that could not be built because its own
 * construction transitively requires such a collaborator — the key is the
 * deepest unconstructable interface/abstract leaf, and the intermediate
 * concrete types are recorded in {@link #viaConcreteTypes} for the evidence.
 */
final class MockDependencySignal {

    /** Interface/abstract type the LLM should implement or mock. */
    final String dependencyTypeName;
    /** Goal-method display labels that require this collaborator. */
    final Set<String> requiringMethodLabels = new LinkedHashSet<>();
    /** Concrete intermediary types blocked on this collaborator (indirect case). */
    final Set<String> viaConcreteTypes = new LinkedHashSet<>();
    final List<TestFitnessFunction> relatedGoals = new ArrayList<>();

    /** True if at least one goal method takes this type as a direct parameter. */
    boolean directParameter;
    /** True if the cluster has no registered generator for this collaborator. */
    boolean noGenerator;

    MockDependencySignal(String dependencyTypeName) {
        this.dependencyTypeName = dependencyTypeName;
    }

    void addRelatedGoals(List<TestFitnessFunction> goals) {
        CardBuildSupport.mergeGoals(relatedGoals, goals);
    }

    String signalKey() {
        return dependencyTypeName;
    }
}
