/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CdgObserver {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();

    Map<String, CdgDependencySignal> observe(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> snapshot) {
        Map<String, CdgDependencySignal> signals = new LinkedHashMap<>();
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return signals;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            BranchCoverageGoal branchGoal = ExtractorObservationSupport.branchCoverageGoalFor(goal);
            if (branchGoal == null || branchGoal.getBranch() == null
                    || branchGoal.getBranch().getInstruction() == null) {
                continue;
            }
            Set<ControlDependency> deps = branchGoal.getBranch().getInstruction().getControlDependencies();
            if (deps == null || deps.isEmpty()) {
                continue;
            }
            for (ControlDependency dep : deps) {
                if (dep == null || dep.getBranch() == null) {
                    continue;
                }
                Branch depBranch = dep.getBranch();
                GoalDescriptionMapper.BranchTarget branchTarget =
                        goalDescriptionMapper.describeBranchTarget(depBranch, dep.getBranchExpressionValue());
                if (ExtractorObservationSupport.isSyntheticCompilerMethod(
                        branchTarget.getTarget().getBaseMethodName())) {
                    continue;
                }
                String depMethod = ExtractorObservationSupport.qualifiedMethodKey(branchTarget.getTarget());
                if (depMethod.isEmpty()) {
                    continue;
                }
                CdgDependencySignal signal = new CdgDependencySignal(
                        depMethod,
                        depBranch.getActualBranchId(),
                        dep.getBranchExpressionValue(),
                        branchTarget);
                CdgDependencySignal existing = signals.get(signal.signalKey());
                if (existing == null) {
                    existing = signal;
                    signals.put(existing.signalKey(), existing);
                }
                existing.relatedGoals.add(goal);
            }
        }
        if (signals.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null || result.getTrace() == null) {
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            Set<Integer> trueBranches = ExtractorObservationSupport.safeSet(trace.getCoveredTrueBranches());
            Set<Integer> falseBranches = ExtractorObservationSupport.safeSet(trace.getCoveredFalseBranches());
            for (CdgDependencySignal signal : signals.values()) {
                boolean seenDesired = signal.desiredValue
                        ? trueBranches.contains(signal.branchId)
                        : falseBranches.contains(signal.branchId);
                boolean seenOpposite = signal.desiredValue
                        ? falseBranches.contains(signal.branchId)
                        : trueBranches.contains(signal.branchId);
                if (seenDesired || seenOpposite) {
                    signal.predicateExecutions++;
                }
                if (seenDesired) {
                    signal.desiredHits++;
                }
                if (seenOpposite) {
                    signal.oppositeHits++;
                }
            }
        }
        return signals;
    }

}
