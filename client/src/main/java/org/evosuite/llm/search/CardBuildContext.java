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

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CardBuildContext {
    final Collection<TestFitnessFunction> uncoveredGoals;
    final List<TestChromosome> snapshot;
    final Map<String, List<TestFitnessFunction>> goalsByMethod;
    final Set<String> coveredMethods;
    final Set<String> touchedTypes;
    final Map<String, AttemptStats> methodStats;
    final Map<String, BranchGoalSignal> branchSignals;
    final Map<String, CdgDependencySignal> cdgSignals;
    final Map<String, TypeBarrierSignal> typeBarrierSignals;
    final Map<String, OuterTypeAttemptSignal> outerTypeSignals;
    final Map<String, MockDependencySignal> mockDependencySignals;
    final Map<String, EnvironmentBarrierSignal> environmentBarrierSignals;
    final Map<String, ExceptionBarrierTracker.AggregatedStats> historicalExceptionStats;
    final ProblemCardThresholds thresholds;
    final ExtractorTraceSink traceSink;
    final ExtractorTelemetry telemetry;
    final int populationSize;

    CardBuildContext(Collection<TestFitnessFunction> uncoveredGoals,
                     List<TestChromosome> snapshot,
                     Map<String, List<TestFitnessFunction>> goalsByMethod,
                     Set<String> coveredMethods,
                     Set<String> touchedTypes,
                     Map<String, AttemptStats> methodStats,
                     Map<String, BranchGoalSignal> branchSignals,
                     Map<String, CdgDependencySignal> cdgSignals,
                     Map<String, TypeBarrierSignal> typeBarrierSignals,
                     Map<String, OuterTypeAttemptSignal> outerTypeSignals,
                     Map<String, MockDependencySignal> mockDependencySignals,
                     Map<String, EnvironmentBarrierSignal> environmentBarrierSignals,
                     Map<String, ExceptionBarrierTracker.AggregatedStats> historicalExceptionStats,
                     ProblemCardThresholds thresholds,
                     ExtractorTraceSink traceSink,
                     ExtractorTelemetry telemetry,
                     int populationSize) {
        this.uncoveredGoals = uncoveredGoals;
        this.snapshot = snapshot;
        this.goalsByMethod = goalsByMethod;
        this.coveredMethods = coveredMethods;
        this.touchedTypes = touchedTypes;
        this.methodStats = methodStats;
        this.branchSignals = branchSignals;
        this.cdgSignals = cdgSignals;
        this.typeBarrierSignals = typeBarrierSignals;
        this.outerTypeSignals = outerTypeSignals;
        this.mockDependencySignals = mockDependencySignals;
        this.environmentBarrierSignals = environmentBarrierSignals;
        this.historicalExceptionStats = historicalExceptionStats;
        this.thresholds = thresholds;
        this.traceSink = traceSink;
        this.telemetry = telemetry;
        this.populationSize = populationSize;
    }
}
