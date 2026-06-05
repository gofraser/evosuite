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
import java.util.Locale;
import java.util.Map;

final class StateDiversificationCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.goalsByMethod == null || context.goalsByMethod.isEmpty()
                || context.methodStats == null || context.methodStats.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : context.goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            AttemptStats stats = context.methodStats.get(methodKey);
            if (stats == null
                    || stats.successes < context.thresholds.minAttemptsForStateDiversification()) {
                continue;
            }
            int outcomeAttempts = stats.successes + stats.exceptions;
            double successShare = outcomeAttempts <= 0
                    ? 0.0
                    : ((double) stats.successes) / (double) outcomeAttempts;
            if (successShare < context.thresholds.minSuccessShareForStateDiversification()) {
                continue;
            }
            StateDiversificationContextEvidence dominantContext =
                    selectStateDiversificationContext(stats, context.thresholds);
            if (dominantContext == null) {
                continue;
            }
            double dominantShare = dominantContext.successShare(stats.successes);
            if (dominantContext.successes < context.thresholds.minAttemptsForStateDiversification()
                    || dominantShare < context.thresholds.minContextShareForStateDiversification()) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = entry.getValue();
            MethodPromptContext methodContext = CardBuildSupport.describeMethodContext(methodKey, relatedGoals);
            if (CardBuildSupport.isSyntheticCompilerMethod(
                    CardBuildSupport.baseMethodNameFromExecutionKey(methodContext.executionKey))) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Direct executions repeatedly reached " + methodContext.displayLabel
                    + " but stayed in the same successful regime.");
            evidence.add("Observed direct executions: " + stats.attempts + " (successes="
                    + stats.successes + ", exceptions=" + stats.exceptions + ").");
            evidence.add("Dominant successful context: " + dominantContext.contextLabel
                    + " (" + dominantContext.successes + "/" + stats.successes + " successful executions).");
            evidence.add("Dominant-regime share: " + dominantContext.successes + "/"
                    + stats.successes + " successful executions.");
            evidence.add("Distinct successful contexts observed: " + countSuccessfulContexts(stats) + ".");
            evidence.add("Related uncovered goals still remaining for this method: " + relatedGoals.size() + ".");
            CardBuildSupport.addOverloadEvidence(evidence, methodContext);

            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = dominantShare;
            double confidence = CardBuildSupport.barrierConfidence(stats.successes);
            out.add(ProblemCard.builder(ProblemCardType.STATE_DIVERSIFICATION_GAP)
                    .title("Direct executions stay in one regime: " + methodContext.displayLabel)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.LOCAL)
                    .rootCauseKey(methodKey)
                    .scopeKey("diversification:" + methodKey)
                    .selectionFingerprint("state_diversification:" + methodKey
                            + "|context=" + dominantContext.contextKey
                            + "|successes=" + stats.successes
                            + "|share=" + String.format(Locale.ROOT, "%.3f", dominantShare))
                    .build());
        }
    }

    private StateDiversificationContextEvidence selectStateDiversificationContext(
            AttemptStats stats,
            ProblemCardThresholds thresholds) {
        if (stats == null || stats.contextStats.isEmpty()) {
            return null;
        }
        StateDiversificationContextEvidence best = null;
        for (ContextAttemptStats contextStats : stats.contextStats.values()) {
            if (contextStats == null || contextStats.successes <= 0) {
                continue;
            }
            int contextOutcomes = contextStats.successes + contextStats.exceptions;
            double contextSuccessShare = contextOutcomes <= 0
                    ? 0.0
                    : ((double) contextStats.successes) / (double) contextOutcomes;
            if (contextSuccessShare < thresholds.minSuccessShareForStateDiversification()) {
                continue;
            }
            StateDiversificationContextEvidence candidate = new StateDiversificationContextEvidence(
                    contextStats.contextKey, contextStats.contextLabel, contextStats.successes);
            if (best == null || candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private int countSuccessfulContexts(AttemptStats stats) {
        if (stats == null || stats.contextStats.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ContextAttemptStats contextStats : stats.contextStats.values()) {
            if (contextStats != null && contextStats.successes > 0) {
                count++;
            }
        }
        return count;
    }
}
