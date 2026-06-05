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

final class UnreachedMethodCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.goalsByMethod == null || context.goalsByMethod.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : context.goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            if (context.coveredMethods.contains(methodKey)) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = entry.getValue();
            MethodPromptContext methodContext = CardBuildSupport.describeMethodContext(methodKey, relatedGoals);
            TypeBarrierSignal typeSignal = context.typeBarrierSignals.get(methodContext.typeName);
            // Strict prefix gate preserved: without a reusable working prefix we have no actionable
            // guidance to give the LLM beyond what the goal text already conveys. The "type never
            // touched at all" case (typeSignal == null and no covered methods on the type) is owned
            // by TYPE_NEVER_ATTEMPTED with stronger framing.
            if (typeSignal == null || !typeSignal.hasReusableSuccessfulPrefix(methodKey)) {
                continue;
            }
            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            double confidence = context.populationSize >= 3 ? 0.9 : 0.7;
            List<String> evidence = new ArrayList<>();
            evidence.add("Goal method had zero observed executions in the current population.");
            evidence.add("Observed executions: 0/" + Math.max(1, context.populationSize) + " tests.");
            evidence.add("Type reachability: " + methodContext.typeName
                    + " was reached successfully, but " + methodContext.displayLabel
                    + " was never observed.");
            String reusablePrefix = typeSignal.describeReusableSuccessfulPrefix(3, methodKey);
            if (!reusablePrefix.isEmpty()) {
                evidence.add("Reusable successful prefix on this type: " + reusablePrefix + ".");
            }
            evidence.add("Related uncovered goals: " + relatedGoals.size() + ".");
            CardBuildSupport.addOverloadEvidence(evidence, methodContext);
            out.add(ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                    .title("Method not reached: " + methodContext.displayLabel)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.EXECUTION)
                    .rootCauseKey(CardBuildSupport.rootCauseKeyForType(methodContext.typeName, methodKey))
                    .scopeKey(methodKey)
                    .selectionFingerprint("unreached:" + methodKey
                            + "|population=" + context.populationSize
                            + "|goals=" + relatedGoals.size()
                            + "|typeReachable=true"
                            + "|acquired=" + typeSignal.hasSuccessfulAcquisition()
                            + "|prefix=" + typeSignal.prefixFingerprint(methodKey))
                    .build());
        }
    }
}
