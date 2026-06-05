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
import java.util.Map;

final class TypeNeverAttemptedCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.goalsByMethod == null || context.goalsByMethod.isEmpty()) {
            return;
        }
        Map<String, List<TestFitnessFunction>> goalsByType =
                CardBuildSupport.groupGoalsByType(context.goalsByMethod);
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByType.entrySet()) {
            String typeName = entry.getKey();
            if (typeName == null || typeName.isEmpty()) {
                continue;
            }
            if (context.touchedTypes != null && context.touchedTypes.contains(typeName)) {
                continue;
            }
            TypeBarrierSignal typeSignal = context.typeBarrierSignals == null
                    ? null : context.typeBarrierSignals.get(typeName);
            if (typeSignal != null && typeSignal.hasReachabilityEvidence()) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = CardBuildSupport.deduplicateGoals(entry.getValue());
            if (relatedGoals.isEmpty()) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Type " + typeName
                    + " has uncovered goals but was never instantiated, acquired, or invoked in any "
                    + "test in the current population.");
            evidence.add("Observed acquisition/invocation activity on this type: 0/"
                    + Math.max(1, context.populationSize) + " tests.");
            evidence.add("Related uncovered goals: " + relatedGoals.size() + ".");
            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            double confidence = context.populationSize >= 3 ? 0.9 : 0.7;
            out.add(ProblemCard.builder(ProblemCardType.TYPE_NEVER_ATTEMPTED)
                    .title("Type never attempted: " + typeName)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.STRUCTURAL)
                    .rootCauseKey(typeName)
                    .scopeKey("type-never-attempted:" + typeName)
                    .selectionFingerprint("type-never-attempted:" + typeName
                            + "|population=" + context.populationSize
                            + "|goals=" + relatedGoals.size())
                    .build());
        }
    }
}
