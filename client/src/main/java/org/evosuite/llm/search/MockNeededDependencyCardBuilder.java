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
import java.util.List;

final class MockNeededDependencyCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.mockDependencySignals == null
                || context.mockDependencySignals.isEmpty()) {
            return;
        }
        for (MockDependencySignal signal : context.mockDependencySignals.values()) {
            if (signal == null) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = CardBuildSupport.deduplicateGoals(signal.relatedGoals);
            if (relatedGoals.isEmpty()) {
                continue;
            }
            String dependency = signal.dependencyTypeName;
            List<String> evidence = new ArrayList<>();
            if (signal.directParameter) {
                evidence.add("Goal method(s) take parameter of interface/abstract type " + dependency
                        + ", but no concrete instance or functional mock of it was materialized in any of "
                        + Math.max(1, context.populationSize) + " tests.");
            } else {
                evidence.add("Goal method(s) cannot be constructed because a required parameter ("
                        + String.join(", ", signal.viaConcreteTypes) + ") transitively needs "
                        + dependency + ", an interface/abstract type with no available implementation.");
            }
            if (!signal.requiringMethodLabels.isEmpty()) {
                evidence.add("Required by: " + String.join(", ", signal.requiringMethodLabels) + ".");
            }
            evidence.add(signal.noGenerator
                    ? "No registered generator for " + dependency
                            + " exists, so the search cannot supply it on its own."
                    : "A generator for " + dependency
                            + " exists but the collaborator was never supplied to these calls.");
            evidence.add("Related uncovered goals: " + relatedGoals.size() + ".");

            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            double confidence = confidenceFor(signal, context);
            out.add(ProblemCard.builder(ProblemCardType.MOCK_NEEDED_DEPENDENCY)
                    .title("Missing collaborator: " + dependency)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.STRUCTURAL)
                    .rootCauseKey(dependency)
                    .scopeKey("mock-needed:" + dependency)
                    .selectionFingerprint("mock-needed:" + dependency
                            + "|direct=" + signal.directParameter
                            + "|nogen=" + signal.noGenerator
                            + "|methods=" + signal.requiringMethodLabels.size()
                            + "|goals=" + relatedGoals.size())
                    .build());
        }
    }

    private double confidenceFor(MockDependencySignal signal, CardBuildContext context) {
        // A direct interface/abstract parameter is a high-confidence missing
        // collaborator; an indirect (transitive) attribution is weaker because
        // the parameter might be absent only because the search has not reached
        // it yet. A confirmed missing generator strengthens either case.
        double base = signal.directParameter ? 0.85 : 0.6;
        if (signal.noGenerator) {
            base = Math.min(1.0, base + 0.1);
        }
        if (context.populationSize < 3) {
            base -= 0.1;
        }
        return Math.max(0.4, base);
    }
}
