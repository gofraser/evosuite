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
import java.util.List;

final class TypeBarrierCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.typeBarrierSignals == null || context.typeBarrierSignals.isEmpty()) {
            return;
        }
        for (TypeBarrierSignal signal : context.typeBarrierSignals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty()) {
                continue;
            }
            if (signal.totalConstructionAttempts() > 0) {
                context.telemetry.increment(ExtractorCandidateMetric.TYPE_BARRIER_SIGNALS_WITH_CONSTRUCTION_FAILURE);
            }
            if (signal.setupAttempts > 0) {
                context.telemetry.increment(ExtractorCandidateMetric.TYPE_BARRIER_SIGNALS_WITH_SETUP_FAILURE);
            }
            addUninstantiableTypeCard(out, signal, context);
            addStateSetupBarrierCard(out, signal, context);
        }
    }

    private void addUninstantiableTypeCard(List<ProblemCard> out,
                                           TypeBarrierSignal signal,
                                           CardBuildContext context) {
        if (signal.totalConstructionAttempts() < context.thresholds.minAttemptsForTypeBarrier()) {
            if (signal.totalConstructionAttempts() > 0) {
                context.telemetry.increment(
                        ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            }
            return;
        }
        context.telemetry.increment(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_CANDIDATES);
        if (signal.hasAcquisitionProgressBeyondCreation()) {
            context.telemetry.increment(ExtractorRejectReason.UNINSTANTIABLE_PROGRESS_BEYOND_CREATION);
            return;
        }
        double impact = CardBuildSupport.leverageAwareImpact(signal.relatedGoals, 5.0);
        double blockage = signal.acquisitionFailureRate();
        if (signal.totalConstructionSuccesses() > 0
                && blockage < context.thresholds.minFailureRateForPracticalAcquisitionBarrier()) {
            context.telemetry.increment(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE);
            return;
        }
        double confidence = CardBuildSupport.barrierConfidence(signal.totalConstructionAttempts());
        List<String> evidence = new ArrayList<>();
        evidence.add("Acquisition attempts that ended with exceptions: "
                + signal.totalConstructionAttempts() + ".");
        if (signal.constructorAttempts > 0 || signal.factoryAttempts > 0) {
            evidence.add("Constructor failures: " + signal.constructorAttempts
                    + "; factory failures: " + signal.factoryAttempts + ".");
        }
        evidence.add("Successful acquisitions observed: " + signal.totalConstructionSuccesses() + ".");
        if (signal.constructorSuccesses > 0 || signal.factorySuccesses > 0) {
            evidence.add("Constructor successes: " + signal.constructorSuccesses
                    + "; factory successes: " + signal.factorySuccesses + ".");
        }
        evidence.add("Acquisitions that progressed into same-type or goal-bearing execution: "
                + signal.totalConstructionSuccessesWithProgress() + ".");
        if (signal.totalConstructionSuccessesWithoutProgress() > 0) {
            evidence.add("Successful acquisitions without any later same-type or goal-bearing step: "
                    + signal.totalConstructionSuccessesWithoutProgress() + ".");
        }
        if (signal.totalConstructionSuccesses() > 0 && !signal.hasAcquisitionProgressBeyondCreation()) {
            evidence.add("Observed acquisitions never led to a usable post-construction step.");
        }
        evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");
        String dominant = CardBuildSupport.dominantException(signal.exceptionTypeCounts);
        if (!dominant.isEmpty()) {
            evidence.add("Dominant exception: " + dominant + ".");
        }
        String dominantAcquisition = signal.dominantFailingAcquisitionLabel();
        if (!dominantAcquisition.isEmpty()) {
            evidence.add("Dominant failing acquisition entry point: " + dominantAcquisition + ".");
        }
        CardBuildSupport.addBootstrapDependencyEvidence(evidence, signal.typeName, dominantAcquisition);
        out.add(ProblemCard.builder(ProblemCardType.UNINSTANTIABLE_TYPE)
                .title("Cannot instantiate/acquire type: " + signal.typeName)
                .evidence(evidence)
                .relatedGoals(signal.relatedGoals)
                .impact(impact)
                .blockage(blockage)
                .confidence(confidence)
                .family(ProblemCardFamily.STRUCTURAL)
                .rootCauseKey(signal.typeName)
                .scopeKey("acquisition:" + signal.typeName)
                .selectionFingerprint("uninstantiable:" + signal.typeName
                        + "|constructors=" + signal.constructorAttempts
                        + "|factories=" + signal.factoryAttempts
                        + "|successes=" + signal.totalConstructionSuccesses()
                        + "|progressed=" + signal.totalConstructionSuccessesWithProgress()
                        + "|exception=" + dominant)
                .build());
    }

    private void addStateSetupBarrierCard(List<ProblemCard> out,
                                          TypeBarrierSignal signal,
                                          CardBuildContext context) {
        if (signal.setupAttempts <= 0) {
            return;
        }
        if (signal.totalConstructionSuccesses() <= 0) {
            context.telemetry.increment(
                    ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_NO_SUCCESSFUL_ACQUISITION);
            return;
        }
        if (signal.setupAttempts < context.thresholds.minAttemptsForTypeBarrier()) {
            context.telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            return;
        }
        context.telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_CANDIDATES);
        String dominantSetupKey = signal.dominantFailingSetupMethodKey();
        int failingExecutions = signal.failingExecutionsForSetupMethod(dominantSetupKey);
        int successfulExecutions = signal.successfulExecutionsForSetupMethod(dominantSetupKey);
        boolean distributedSetupBreakdown = false;
        if (failingExecutions < context.thresholds.minAttemptsForTypeBarrier()) {
            distributedSetupBreakdown = signal.distinctFailingSetupSteps() >= 2;
            if (!distributedSetupBreakdown) {
                context.telemetry.increment(
                        ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_INCONSISTENT_FAILING_STEP);
                return;
            }
            failingExecutions = signal.setupAttempts;
            successfulExecutions = signal.countSuccessfulExecutionsForFailingSetupMethods();
        }
        double blockage = CardBuildSupport.failureRate(failingExecutions, successfulExecutions);
        if (blockage < context.thresholds.minFailureRateForStateSetupBarrier()) {
            context.telemetry.increment(ExtractorRejectReason.STATE_SETUP_DILUTED_SUCCESS);
            return;
        }
        double impact = CardBuildSupport.leverageAwareImpact(signal.relatedGoals, 5.0);
        double confidence = CardBuildSupport.barrierConfidence(signal.setupAttempts);
        List<String> evidence = new ArrayList<>();
        evidence.add("Construction succeeded, but setup/lifecycle calls failed: "
                + signal.setupAttempts + " exception outcomes.");
        evidence.add("Dominant failing setup-step outcomes: "
                + failingExecutions + "/" + (failingExecutions + successfulExecutions) + ".");
        evidence.add("Successful executions of the dominant failing setup step observed: "
                + successfulExecutions + ".");
        evidence.add("Successful acquisitions observed: " + signal.totalConstructionSuccesses() + ".");
        evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");
        String dominant = CardBuildSupport.dominantException(signal.exceptionTypeCounts);
        if (!dominant.isEmpty()) {
            evidence.add("Dominant exception: " + dominant + ".");
        }
        String dominantSetup = signal.dominantFailingSetupLabel();
        if (distributedSetupBreakdown) {
            evidence.add("Failing setup/lifecycle steps are distributed across multiple bootstrap operations.");
            String failingSteps = signal.describeFailingSetupLabels(3);
            if (!failingSteps.isEmpty()) {
                evidence.add("Observed failing setup/lifecycle steps: " + failingSteps + ".");
            }
        } else if (!dominantSetup.isEmpty()) {
            evidence.add("Dominant failing setup/lifecycle step: " + dominantSetup + ".");
        }
        CardBuildSupport.addBootstrapDependencyEvidence(evidence, signal.typeName,
                distributedSetupBreakdown ? signal.describeFailingSetupLabels(1) : dominantSetup);
        out.add(ProblemCard.builder(ProblemCardType.STATE_SETUP_BARRIER)
                .title("Type setup/lifecycle fails before target behavior: " + signal.typeName)
                .evidence(evidence)
                .relatedGoals(signal.relatedGoals)
                .impact(impact)
                .blockage(blockage)
                .confidence(confidence)
                .family(ProblemCardFamily.STRUCTURAL)
                .rootCauseKey(signal.typeName)
                .scopeKey("setup:" + signal.typeName)
                .selectionFingerprint("setup:" + signal.typeName
                        + "|attempts=" + signal.setupAttempts
                        + "|dominantFailures=" + failingExecutions
                        + "|dominantSuccesses=" + successfulExecutions
                        + "|acquisitions=" + signal.totalConstructionSuccesses()
                        + "|setup=" + dominantSetup)
                .build());
    }
}
