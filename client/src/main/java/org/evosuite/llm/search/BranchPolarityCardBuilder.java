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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BranchPolarityCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.branchSignals == null || context.branchSignals.isEmpty()) {
            return;
        }
        List<BranchGoalSignal> candidates = new ArrayList<>();
        for (BranchGoalSignal signal : context.branchSignals.values()) {
            if (signal == null) {
                continue;
            }
            if (signal.desiredHits > 0) {
                continue;
            }
            if (signal.oppositeHits < context.thresholds.minPolarityGapOppositeObservations()) {
                continue;
            }
            candidates.add(signal);
        }

        Map<String, Integer> saturatedCandidatesByMethod = new HashMap<>();
        for (BranchGoalSignal signal : candidates) {
            if (isSaturatedPolarityGap(signal, context.thresholds)) {
                saturatedCandidatesByMethod.put(signal.methodKey,
                        saturatedCandidatesByMethod.getOrDefault(signal.methodKey, 0) + 1);
            }
        }

        for (BranchGoalSignal signal : candidates) {
            if (CardBuildSupport.isSyntheticCompilerMethod(signal.branchTarget.getTarget().getBaseMethodName())) {
                continue;
            }
            double impact = CardBuildSupport.normalizeCount(signal.relatedGoals.size(), 3.0);
            double blockage = signal.predicateExecutions <= 0
                    ? 0.0
                    : ((double) signal.oppositeHits / (double) signal.predicateExecutions);
            double confidence = CardBuildSupport.normalizeCount(signal.oppositeHits, 6.0);
            boolean lowSteerability = isLikelyLowSteerabilityBranchGap(
                    signal,
                    saturatedCandidatesByMethod.getOrDefault(signal.methodKey, 0),
                    context.thresholds);
            if (lowSteerability) {
                // Saturated polarity gaps repeated across one method are often caller-locked/noise.
                impact *= 0.55;
                blockage *= 0.55;
                confidence *= 0.60;
            }
            String branchLocation = signal.branchTarget.getLocationLabel();
            String desiredLabel = signal.branchTarget.getOutcomeLabel();
            List<String> evidence = new ArrayList<>();
            evidence.add("Desired branch outcome " + desiredLabel
                    + " was never observed for " + branchLocation + ".");
            evidence.add("Observed non-target branch outcome in "
                    + signal.oppositeHits + " tests.");
            evidence.add("Predicate executed in " + signal.predicateExecutions + " tests.");
            evidence.add("Opposite-outcome dominance: " + signal.oppositeHits
                    + "/" + Math.max(1, signal.predicateExecutions) + ".");
            if (lowSteerability) {
                evidence.add("Observed a saturated opposite-outcome pattern across multiple branches in this method; "
                        + "this gap is likely caller-locked from available public entrypoints.");
            }
            out.add(ProblemCard.builder(ProblemCardType.BRANCH_POLARITY_GAP)
                    .title("Branch polarity gap in " + branchLocation
                            + " (" + signal.branchTarget.getNeedLabel() + ")")
                    .evidence(evidence)
                    .relatedGoals(signal.relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.LOCAL)
                    .rootCauseKey(signal.signalKey())
                    .scopeKey(signal.signalKey())
                    .selectionFingerprint("branch:" + signal.signalKey()
                            + "|opposite=" + signal.oppositeHits
                            + "|desired=" + signal.desiredHits
                            + "|executions=" + signal.predicateExecutions
                            + "|steer=" + (lowSteerability ? "low" : "normal"))
                    .build());
        }
    }

    private boolean isSaturatedPolarityGap(BranchGoalSignal signal, ProblemCardThresholds thresholds) {
        return signal != null
                && signal.predicateExecutions >= thresholds.minLowSteerabilityBranchExecutions()
                && signal.oppositeHits >= signal.predicateExecutions
                && signal.desiredHits == 0;
    }

    private boolean isLikelyLowSteerabilityBranchGap(BranchGoalSignal signal,
                                                     int saturatedPeersInMethod,
                                                     ProblemCardThresholds thresholds) {
        if (!isSaturatedPolarityGap(signal, thresholds)) {
            return false;
        }
        if (saturatedPeersInMethod >= 2) {
            return true;
        }
        if (signal.predicateExecutions >= 8) {
            return true;
        }
        String baseMethodName = signal == null || signal.branchTarget == null
                || signal.branchTarget.getTarget() == null
                ? ""
                : signal.branchTarget.getTarget().getBaseMethodName();
        return looksLikeHelperMethod(baseMethodName) && signal.predicateExecutions >= 6;
    }

    private boolean looksLikeHelperMethod(String baseMethodName) {
        if (baseMethodName == null || baseMethodName.isEmpty()) {
            return false;
        }
        String method = baseMethodName.toLowerCase(java.util.Locale.ROOT);
        return method.startsWith("write")
                || method.startsWith("build")
                || method.startsWith("create")
                || method.startsWith("to")
                || method.startsWith("from")
                || method.startsWith("setup")
                || method.startsWith("configure")
                || method.startsWith("init");
    }
}
