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
 */
package org.evosuite.testcase.factories;

import java.util.Map;
import java.util.Set;

import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.ObjectRuleTelemetry;
import org.evosuite.testcase.synthesizer.TargetContext;

/** Publishes a run session without coupling generation state to the RMI statistics service. */
public final class ObjectRuleStatisticsReporter {

    private ObjectRuleStatisticsReporter() {
    }

    public static void publish(ObjectRuleSession session) {
        double successRate = session.legitimizationTrials == 0
                ? 0.0
                : (double) session.legitimizationSuccess / session.legitimizationTrials;

        track(RuntimeVariable.ObjectGraphBranches, countObjectGraphBranches());
        track(RuntimeVariable.ObjectGraphMaxSize, maxObjectGraphSize());
        track(RuntimeVariable.TemplateSynthesisTime, session.templateSynthesisTimeMs);
        track(RuntimeVariable.ObjectRuleSynthesisTime, session.synthesisTimeMs);
        track(RuntimeVariable.ObjectRuleTemplateExecTime, session.templateExecTimeMs);
        track(RuntimeVariable.ObjectRuleLegitimizationTime, session.legitimizationTimeMs);
        track(RuntimeVariable.TemplateInjections, session.templateInjections);
        track(RuntimeVariable.LegitimizationSuccessRate, successRate);
        track(RuntimeVariable.LegitimizationAttempts, session.legitimizationTrials);
        track(RuntimeVariable.LegitimizationSuccesses, session.legitimizationSuccess);
        track(RuntimeVariable.LegitimizationSkipped, session.legitimizationSkipped);
        track(RuntimeVariable.ObjectRuleEverEligibleBranches,
                ObjectRuleBranchSelector.getInstance().getEverEligibleCount());
        track(RuntimeVariable.ObjectRuleBlacklistedBranches,
                ObjectRuleBranchSelector.getInstance().getBlacklistedCount());
        track(RuntimeVariable.ObjectRuleTargetedBranches,
                ObjectRuleBranchSelector.getInstance().getTargetedBranchCount());
        track(RuntimeVariable.ObjectRuleMaxInjectionsPerBranch,
                ObjectRuleBranchSelector.getInstance().getMaxInjectionsPerBranch());
        track(RuntimeVariable.ObjectRuleRepeatedFailureBranches,
                ObjectRuleBranchSelector.getInstance().getRepeatedFailureBranchCount());
        track(RuntimeVariable.ObjectRuleMaxConsecutiveFailures,
                ObjectRuleBranchSelector.getInstance().getMaxConsecutiveFailures());
        track(RuntimeVariable.ObjectRuleLastFreshSynthesisIteration,
                session.lastFreshSynthesisIteration);
        track(RuntimeVariable.ObjectRuleEntryRejectedConstructor,
                TargetContext.getRejectedConstructorEntries());
        track(RuntimeVariable.ObjectRuleEntryRejectedNonPublic,
                TargetContext.getRejectedNonPublicEntries());
        track(RuntimeVariable.ObjectRuleEntryRejectedOther,
                TargetContext.getRejectedOtherEntries());
        track(RuntimeVariable.ObjectRuleTemplateSurvivalMean,
                ObjectRuleTelemetry.getInstance().meanSurvival());
        track(RuntimeVariable.ObjectRuleTemplateSurvival5,
                ObjectRuleTelemetry.getInstance().survivalAtLeastFive());
        track(RuntimeVariable.TemplateCacheHits, session.templateCacheHits);
        track(RuntimeVariable.ObjectRuleFreshSyntheses, session.freshSyntheses);
        track(RuntimeVariable.ObjectRuleFirstShotSuccesses, session.firstShotSuccesses);
        track(RuntimeVariable.ObjectRuleImmediateCoverage, session.immediateCoverage);
        track(RuntimeVariable.ObjectRuleReachedNotCovered, session.reachedNotCovered);
        track(RuntimeVariable.ObjectRuleWorkingGoalUnavailable, session.workingGoalUnavailable);
        track(RuntimeVariable.ObjectRuleWorkingGoalEvaluationFailures,
                session.workingGoalEvaluationFailures);
        track(RuntimeVariable.ObjectRuleMockSetupOpportunities,
                FunctionalMockSetupCoordinator.getOpportunities());
        track(RuntimeVariable.ObjectRuleMockSetupMethodsObserved,
                FunctionalMockSetupCoordinator.getMethodsObserved());
        track(RuntimeVariable.ObjectRuleMockSetupStubsAdded,
                FunctionalMockSetupCoordinator.getStubsAdded());
        track(RuntimeVariable.ObjectRuleMockSetupAttempts,
                FunctionalMockSetupCoordinator.getAttempts());
        track(RuntimeVariable.ObjectRuleMockSetupSuccesses,
                FunctionalMockSetupCoordinator.getSuccesses());
        track(RuntimeVariable.ObjectRuleMockSetupProactiveUses,
                FunctionalMockSetupCoordinator.getProactiveUses());
        track(RuntimeVariable.ObjectRuleMockSetupExtraExecutions,
                FunctionalMockSetupCoordinator.getExtraExecutions());
        track(RuntimeVariable.ObjectRuleMockSetupNegativeCacheHits,
                FunctionalMockSetupCoordinator.getNegativeCacheHits());
        track(RuntimeVariable.ObjectRuleMockSetupBlacklistedRecipes,
                FunctionalMockSetupCoordinator.getBlacklistedRecipes());
    }

    private static void track(RuntimeVariable variable, Number value) {
        ClientServices.getInstance().getClientNode().trackOutputVariable(variable, value);
    }

    static int countObjectGraphBranches() {
        int count = 0;
        for (Map<org.evosuite.coverage.branch.Branch, Set<DepVariable>> branches
                : ObjectConstructionContext.branchInterestedVarsMap.values()) {
            if (branches != null) {
                for (Set<DepVariable> variables : branches.values()) {
                    if (variables != null && !variables.isEmpty()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    static int maxObjectGraphSize() {
        int max = 0;
        for (Map<org.evosuite.coverage.branch.Branch, Set<DepVariable>> branches
                : ObjectConstructionContext.branchInterestedVarsMap.values()) {
            if (branches != null) {
                for (Set<DepVariable> variables : branches.values()) {
                    if (variables != null) {
                        max = Math.max(max, variables.size());
                    }
                }
            }
        }
        return max;
    }
}
