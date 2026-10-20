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
package org.evosuite.strategy;

import org.evosuite.llm.IterativeLlmSidecarRecorder;
import org.evosuite.llm.IterativeLlmTimelineRecorder;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-round timelines and totals of one ITERATIVE_BUDGETED run. Every exit path of the loop ends in
 * {@link #finish}, so the {@code LLM_Iterative_*} runtime variables are emitted consistently.
 */
final class IterativeTelemetry {

    private final long strategyStartedNanos;
    private final long deadlineNanos;
    private final int totalGoals;
    private final IterativeLlmTimelineRecorder timelineRecorder = new IterativeLlmTimelineRecorder();
    private final IterativeLlmSidecarRecorder sidecarRecorder;

    private final List<Integer> newGoalsTimeline = new ArrayList<>();
    private final List<Integer> coverageTimeline = new ArrayList<>();
    private final List<Long> elapsedTimeline = new ArrayList<>();
    private final List<Long> queryLatencyTimeline = new ArrayList<>();
    private final List<Integer> suiteSizeTimeline = new ArrayList<>();
    private final List<Integer> parsedTestsTimeline = new ArrayList<>();
    private final List<Integer> uniqueTestsTimeline = new ArrayList<>();
    private final List<Integer> targetGoalsTimeline = new ArrayList<>();

    private int rounds;
    private int totalParsedTests;
    private int totalUniqueTests;
    private int totalDuplicates;

    IterativeTelemetry(long strategyStartedNanos, long deadlineNanos, int totalGoals,
                       IterativeLlmSidecarRecorder sidecarRecorder) {
        this.strategyStartedNanos = strategyStartedNanos;
        this.deadlineNanos = deadlineNanos;
        this.totalGoals = totalGoals;
        this.sidecarRecorder = sidecarRecorder;
    }

    /** Starts the next round and returns its 1-based number. */
    int beginRound() {
        return ++rounds;
    }

    /**
     * Records one finished round: the timelines, both recorders and the goal timelines.
     *
     * @param targetGoals number of goals the round's query was aimed at
     * @param newGoals    goals covered for the first time in this round
     */
    void recordRound(String roundType, int targetGoals, LlmStrategy.QueryOutcome outcome,
                     int added, int duplicates, int newGoals, int coveredGoals,
                     TestSuiteChromosome suite) {
        totalParsedTests += outcome.tests.size();
        totalUniqueTests += added;
        totalDuplicates += duplicates;
        newGoalsTimeline.add(newGoals);
        coverageTimeline.add(coveredGoals);
        long elapsedMs = LlmStrategy.elapsedMillis(strategyStartedNanos);
        elapsedTimeline.add(elapsedMs);
        queryLatencyTimeline.add(outcome.latencyMillis);
        suiteSizeTimeline.add(suite.size());
        parsedTestsTimeline.add(outcome.tests.size());
        uniqueTestsTimeline.add(added);
        targetGoalsTimeline.add(targetGoals);
        timelineRecorder.record(rounds, roundType, elapsedMs,
                LlmStrategy.remainingMillis(deadlineNanos), targetGoals,
                outcome.latencyMillis, outcome.tests.size(), added, duplicates,
                newGoals, coveredGoals, totalGoals, suite.size(),
                suite.totalLengthOfTestCases(), suite.getCoverage(),
                LlmStrategy.countAssertions(suite),
                LlmStrategy.countTestsWithAssertions(suite),
                outcome.failureSummary);
        // Covered_Goals_Timeline / Remaining_Goals_Timeline are backed by
        // DirectSequenceOutputVariableFactory, so setting them directly is valid.
        // The Coverage/Fitness/Size/Length timelines are NOT direct factories and
        // must not be set via track(); their per-round values are exported through
        // the LLM_Iterative_* variables and the iterative_llm_timeline sidecar.
        ClientServices.track(RuntimeVariable.Covered_Goals_Timeline,
                coveredGoals);
        ClientServices.track(RuntimeVariable.Remaining_Goals_Timeline,
                Math.max(0, totalGoals - coveredGoals));
        sidecarRecorder.record(rounds,
                LlmStrategy.elapsedMillis(strategyStartedNanos), suite);
    }

    /** Emits the ITERATIVE_BUDGETED runtime variables and flushes the recorders. */
    void finish(int iteration, String exitReason, int parseFailureCount) {
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Iterations, iteration);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Rounds, rounds);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Exit_Reason, exitReason);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Parse_Failures, parseFailureCount);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_New_Goals_Timeline,
                        join(newGoalsTimeline));
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Coverage_Timeline,
                        join(coverageTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Parsed,
                totalParsedTests);
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Unique,
                totalUniqueTests);
        ClientServices.track(RuntimeVariable.LLM_Iterative_Duplicates,
                totalDuplicates);
        ClientServices.track(
                RuntimeVariable.LLM_Iterative_Round_Elapsed_Millis_Timeline,
                join(elapsedTimeline));
        ClientServices.track(
                RuntimeVariable.LLM_Iterative_Query_Latency_Millis_Timeline,
                join(queryLatencyTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Suite_Size_Timeline,
                join(suiteSizeTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Parsed_Timeline,
                join(parsedTestsTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Unique_Timeline,
                join(uniqueTestsTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Target_Goals_Timeline,
                join(targetGoalsTimeline));
        timelineRecorder.flush();
        sidecarRecorder.flush();
        // Each LLM request/evaluation round is the iterative strategy's
        // generation-equivalent unit, including the initial request.
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Generations, rounds);
    }

    private static String join(List<? extends Number> values) {
        return values.stream().map(String::valueOf)
                .collect(Collectors.joining(";"));
    }
}
