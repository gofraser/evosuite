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

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BranchObserver {

    Map<String, BranchGoalSignal> observe(Collection<TestFitnessFunction> uncoveredGoals,
                                          List<TestChromosome> snapshot) {
        Map<String, BranchGoalSignal> signals = new LinkedHashMap<>();
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return signals;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            BranchGoalSignal signal = ExtractorObservationSupport.branchSignalForGoal(goal);
            if (signal == null) {
                continue;
            }
            String key = signal.signalKey();
            BranchGoalSignal existing = signals.get(key);
            if (existing == null) {
                signals.put(key, signal);
            } else {
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
            if (result == null) {
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            if (trace == null) {
                continue;
            }
            Set<Integer> trueBranches = ExtractorObservationSupport.safeSet(trace.getCoveredTrueBranches());
            Set<Integer> falseBranches = ExtractorObservationSupport.safeSet(trace.getCoveredFalseBranches());
            for (BranchGoalSignal signal : signals.values()) {
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
