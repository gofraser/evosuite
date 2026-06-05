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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IndirectReachabilityCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.goalsByMethod == null || context.goalsByMethod.isEmpty()) {
            return;
        }
        addInvocationGapIndirectReachabilityCards(out, context);
        Map<String, List<TestFitnessFunction>> goalsBySyntheticType = new LinkedHashMap<>();
        Map<String, List<String>> methodKeysBySyntheticType = new LinkedHashMap<>();
        Map<String, MethodPromptContext> contextBySyntheticType = new LinkedHashMap<>();

        for (Map.Entry<String, List<TestFitnessFunction>> entry : context.goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            if (context.coveredMethods.contains(methodKey)) {
                continue;
            }
            MethodPromptContext methodContext =
                    CardBuildSupport.describeMethodContext(methodKey, entry.getValue());
            if (!CardBuildSupport.isLikelyIndirectHelperType(methodContext.typeName)) {
                continue;
            }
            TypeBarrierSignal typeSignal = context.typeBarrierSignals.get(methodContext.typeName);
            if (typeSignal != null && typeSignal.hasReusableSuccessfulPrefix(methodKey)) {
                continue;
            }
            goalsBySyntheticType.computeIfAbsent(methodContext.typeName, ignored -> new ArrayList<>())
                    .addAll(entry.getValue());
            methodKeysBySyntheticType.computeIfAbsent(methodContext.typeName, ignored -> new ArrayList<>())
                    .add(methodKey);
            contextBySyntheticType.putIfAbsent(methodContext.typeName, methodContext);
        }

        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsBySyntheticType.entrySet()) {
            String syntheticType = entry.getKey();
            List<TestFitnessFunction> relatedGoals = CardBuildSupport.deduplicateGoals(entry.getValue());
            MethodPromptContext methodContext = contextBySyntheticType.get(syntheticType);
            if (methodContext == null || relatedGoals.isEmpty()) {
                continue;
            }
            String outerType = CardBuildSupport.outerTypeName(syntheticType);
            OuterTypeAttemptSignal outerSignal = context.outerTypeSignals.get(outerType);

            List<String> evidence = new ArrayList<>();
            evidence.add("Unreached goals are concentrated in synthetic/local helper type: "
                    + syntheticType + ".");
            evidence.add("These helper methods are usually reached indirectly through outer entrypoint workflows.");
            List<String> methodKeys = methodKeysBySyntheticType.getOrDefault(syntheticType, Collections.emptyList());
            evidence.add("Blocked helper methods: " + methodKeys.size() + ".");
            if (!methodKeys.isEmpty()) {
                evidence.add("Example blocked helper: " + methodKeys.get(0) + ".");
            }
            evidence.add("Inferred outer entrypoint type: " + outerType + ".");
            if (outerSignal == null || outerSignal.attempts <= 0) {
                evidence.add("No observed outer entrypoint invocations for " + outerType
                        + " in current executions.");
            } else {
                evidence.add("Observed outer entrypoint invocations: " + outerSignal.attempts
                        + " (successes=" + outerSignal.successes + ", exceptions=" + outerSignal.exceptions + ").");
                String successfulEntry = ProblemCardLabels.dominantLabel(outerSignal.successfulEntryPointLabelCounts);
                if (!successfulEntry.isEmpty()) {
                    evidence.add("Most frequent successful outer entrypoint: " + successfulEntry + ".");
                }
                String failingEntry = ProblemCardLabels.dominantLabel(outerSignal.failingEntryPointLabelCounts);
                if (!failingEntry.isEmpty()) {
                    evidence.add("Most frequent failing outer entrypoint: " + failingEntry + ".");
                }
                String dominantException = CardBuildSupport.dominantException(outerSignal.exceptionTypeCounts);
                if (!dominantException.isEmpty()) {
                    evidence.add("Dominant outer entrypoint exception: " + dominantException + ".");
                }
            }
            CardBuildSupport.addOverloadEvidence(evidence, methodContext);

            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            int attempts = outerSignal == null ? 0 : outerSignal.attempts;
            double confidence = attempts > 0 ? CardBuildSupport.barrierConfidence(attempts) : 0.6;
            out.add(ProblemCard.builder(ProblemCardType.INDIRECT_REACHABILITY_BARRIER)
                    .title("Indirect reachability barrier for helper type: " + syntheticType)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.STRUCTURAL)
                    .rootCauseKey(syntheticType)
                    .scopeKey("indirect:" + syntheticType)
                    .selectionFingerprint("indirect:" + syntheticType
                            + "|outer=" + outerType
                            + "|methods=" + methodKeys.size()
                            + "|outerAttempts=" + attempts
                            + "|outerExceptions=" + (outerSignal == null ? 0 : outerSignal.exceptions))
                    .build());
        }
    }

    private void addInvocationGapIndirectReachabilityCards(List<ProblemCard> out, CardBuildContext context) {
        Map<String, TypeInvocationSignal> invocationSignals = collectTypeInvocationSignals(context);
        for (TypeInvocationSignal signal : invocationSignals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty() || signal.typeName.isEmpty()) {
                continue;
            }
            if (CardBuildSupport.isLikelyIndirectHelperType(signal.typeName) || signal.directAttempts > 0) {
                continue;
            }
            TypeBarrierSignal typeSignal = context.typeBarrierSignals.get(signal.typeName);
            if (typeSignal == null
                    || (!typeSignal.hasSuccessfulAcquisition() && typeSignal.successfulMethodLabelCounts.isEmpty())) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Observed setup/acquisition activity for " + signal.typeName
                    + ", but no direct goal-bearing method on this type executed.");
            evidence.add("Observed direct goal-method executions: 0 across "
                    + signal.methodKeys.size() + " blocked methods.");
            evidence.add("Blocked goal methods on this type: " + signal.describeBlockedMethodLabels(3) + ".");
            evidence.add("Successful acquisitions observed for this type: "
                    + typeSignal.totalConstructionSuccesses() + ".");
            if (typeSignal.totalConstructionSuccessesWithProgress() > 0) {
                evidence.add("Acquisitions that progressed into same-type or goal-bearing execution: "
                        + typeSignal.totalConstructionSuccessesWithProgress() + ".");
            }
            String successfulSteps = typeSignal.describeSuccessfulMethodLabels(3, null);
            if (!successfulSteps.isEmpty()) {
                evidence.add("Observed successful setup/lifecycle steps: " + successfulSteps + ".");
            }
            String reusablePrefix = typeSignal.describeReusableSuccessfulPrefix(3, null);
            if (!reusablePrefix.isEmpty()) {
                evidence.add("Reusable setup prefix that still stopped before the direct target call: "
                        + reusablePrefix + ".");
            }
            evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");

            double impact = CardBuildSupport.leverageAwareImpact(signal.relatedGoals, 5.0);
            double blockage = 1.0;
            int reachabilityEvidence = typeSignal.totalConstructionSuccesses()
                    + typeSignal.successfulMethodLabelCounts.size();
            double confidence = CardBuildSupport.barrierConfidence(Math.max(1, reachabilityEvidence));
            out.add(ProblemCard.builder(ProblemCardType.INDIRECT_REACHABILITY_BARRIER)
                    .title("Indirect reachability barrier before direct target invocation: " + signal.typeName)
                    .evidence(evidence)
                    .relatedGoals(signal.relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.STRUCTURAL)
                    .rootCauseKey(CardBuildSupport.rootCauseKeyForType(signal.typeName, signal.typeName))
                    .scopeKey("indirect-invocation:" + signal.typeName)
                    .selectionFingerprint("indirect:" + signal.typeName
                            + "|mode=invocation_gap"
                            + "|blockedMethods=" + signal.methodKeys.size()
                            + "|successfulAcquisitions=" + typeSignal.totalConstructionSuccesses()
                            + "|successfulSetup=" + typeSignal.successfulMethodLabelCounts.size()
                            + "|directAttempts=0")
                    .build());
        }
    }

    private Map<String, TypeInvocationSignal> collectTypeInvocationSignals(CardBuildContext context) {
        Map<String, TypeInvocationSignal> signals = new LinkedHashMap<>();
        if (context == null || context.goalsByMethod == null || context.goalsByMethod.isEmpty()) {
            return signals;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : context.goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            MethodPromptContext methodContext =
                    CardBuildSupport.describeMethodContext(methodKey, entry.getValue());
            if (methodContext.typeName.isEmpty()) {
                continue;
            }
            TypeInvocationSignal signal = signals.computeIfAbsent(methodContext.typeName,
                    ignored -> new TypeInvocationSignal(methodContext.typeName));
            signal.recordMethod(methodKey, methodContext.displayLabel, entry.getValue(),
                    context.methodStats.get(methodKey));
        }
        return signals;
    }
}
