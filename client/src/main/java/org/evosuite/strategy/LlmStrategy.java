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

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.stoppingconditions.MaxFitnessEvaluationsStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxGenerationStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.llm.IterativeLlmTimelineRecorder;
import org.evosuite.llm.IterativeLlmSidecarRecorder;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmWaitBudget;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TestRelevanceRanker;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testparser.TestParser;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LLM-only strategy for test generation.
 *
 * <p>Supports two modes via {@code Properties.LLM_STRATEGY_MODE}:
 * <ul>
 *   <li>{@code SINGLE_PROMPT} — one-shot baseline: generate once and stop.</li>
 *   <li>{@code ITERATIVE_BUDGETED} — iterative baseline: initial broad query,
 *       evaluate, re-query for uncovered goals. The loop exits on the first of:
 *       the configured stopping condition firing, all goals covered, LLM call
 *       budget exhausted, the {@code LLM_STRATEGY_MAX_ITERATIONS} cap, or
 *       {@code LLM_STRATEGY_NO_PROGRESS_LIMIT} consecutive iterations without
 *       new coverage.</li>
 * </ul>
 */
public class LlmStrategy extends TestGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LlmStrategy.class);

    /**
     * Whether this strategy operates as the LLMSTRATEGY (vs. an LLM helper
     * embedded in another strategy). Used by {@link LlmAssertionPolicyResolver}
     * to resolve the effective assertion-handling policy. Centralized so the
     * resolver argument has a single source of truth.
     */
    private static final boolean LLM_STRATEGY_CONTEXT = true;

    @Override
    public TestSuiteChromosome generateTests() {
        // JUnit 5 tests are incompatible with the separate-classloader scaffolding.
        // Force it off so the generated tests are directly runnable.
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            Properties.USE_SEPARATE_CLASSLOADER = false;
        }

        LoggingUtils.getEvoLogger().info("* Using LLM strategy (mode={})",
                Properties.LLM_STRATEGY_MODE);

        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getConfiguredGoalFactories();
        List<TestFitnessFunction> allGoals = goalFactories.stream()
                .flatMap(f -> f.getCoverageGoals().stream())
                .collect(Collectors.toList());
        int totalGoals = allGoals.size();
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Total_Goals, totalGoals);

        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info(
                    "* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            return new TestSuiteChromosome();
        }

        ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

        TestSuiteChromosome suite;
        if (Properties.LLM_STRATEGY_MODE == Properties.LlmStrategyMode.ITERATIVE_BUDGETED) {
            suite = runIterativeBudgeted(fitnessFunctions, allGoals);
        } else {
            suite = runSinglePrompt(fitnessFunctions);
        }

        emitParsedStatementRatio(suite);
        ClientServices.track(RuntimeVariable.LLM_Strategy_Assertions_Retained,
                countAssertions(suite));
        ClientServices.track(RuntimeVariable.LLM_Strategy_Tests_With_Assertions,
                countTestsWithAssertions(suite));
        if (!iterativeEmittedGenerations) {
            ClientServices.getInstance().getClientNode()
                    .trackOutputVariable(RuntimeVariable.Generations, 1);
        }
        getLlmService().getStatistics().publishRuntimeVariables();
        sendExecutionStatistics();
        return suite;
    }

    /**
     * Computes and emits the ratio of LLM-parsed statements to total statements.
     */
    private void emitParsedStatementRatio(TestSuiteChromosome suite) {
        int totalStatements = 0;
        int parsedStatements = 0;
        for (TestChromosome tc : suite.getTestChromosomes()) {
            for (int i = 0; i < tc.getTestCase().size(); i++) {
                totalStatements++;
                if (tc.getTestCase().getStatement(i).isParsedFromLlm()) {
                    parsedStatements++;
                }
            }
        }
        double ratio = totalStatements > 0
                ? (double) parsedStatements / totalStatements : 0.0;
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio, ratio);
    }

    /**
     * SINGLE_PROMPT mode: one-shot LLM call, evaluate, return.
     */
    private TestSuiteChromosome runSinglePrompt(
            List<TestSuiteFitnessFunction> fitnessFunctions) {
        LlmSeededPopulationFactory seededFactory = createSeededFactory();
        long waitMillis = LlmWaitBudget.repairAwareWaitMillis(
                () -> TimeController.getInstance().getRemainingTimeInPhaseMs());
        List<TestChromosome> llmSeeds = seededFactory.awaitAndDrainSeeds(waitMillis);
        LoggingUtils.getEvoLogger().info("* Received {} LLM seeds",
                llmSeeds.size());

        TestSuiteChromosome suite = new TestSuiteChromosome();
        llmSeeds.forEach(suite::addTest);

        if (suite.getTestChromosomes().isEmpty()
                && !ArrayUtil.contains(Properties.CRITERION,
                Properties.Criterion.EXCEPTION)) {
            return suite;
        }

        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
        return suite;
    }

    /**
     * Runs the ITERATIVE_BUDGETED mode.
     * <ol>
     *   <li>Initial broad-coverage query</li>
     *   <li>Evaluate tests, compute uncovered goals and the newly-covered delta
     *       vs. the previous iteration</li>
     *   <li>Follow-up queries targeting uncovered goals, primed with both the
     *       newly-covered feedback and the most relevant existing tests</li>
     *   <li>Stop on stopping-condition / globalTime / all-goals-covered /
     *       LLM-budget-exhausted / {@code LLM_STRATEGY_MAX_ITERATIONS} /
     *       {@code LLM_STRATEGY_NO_PROGRESS_LIMIT}</li>
     * </ol>
     * Duplicate tests (by rendered code) are skipped on insertion so the suite
     * grows by genuinely new content only.
     */
    private TestSuiteChromosome runIterativeBudgeted(
            List<TestSuiteFitnessFunction> fitnessFunctions,
            List<TestFitnessFunction> allGoals) {
        StoppingCondition<TestSuiteChromosome> stoppingCondition = getStoppingCondition();
        stoppingCondition.reset();

        LlmService llmService = getLlmService();
        long strategyStartedNanos = System.nanoTime();
        long deadlineNanos = phaseDeadlineNanos(strategyStartedNanos);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        IterativeLlmTimelineRecorder timelineRecorder =
                new IterativeLlmTimelineRecorder();
        IterativeLlmSidecarRecorder sidecarRecorder =
                new IterativeLlmSidecarRecorder(allGoals);
        List<Double> ratioTimeline = new ArrayList<>();
        List<Integer> newGoalsTimeline = new ArrayList<>();
        List<Integer> coverageTimeline = new ArrayList<>();
        List<Long> elapsedTimeline = new ArrayList<>();
        List<Long> queryLatencyTimeline = new ArrayList<>();
        List<Integer> suiteSizeTimeline = new ArrayList<>();
        List<Integer> parsedTestsTimeline = new ArrayList<>();
        List<Integer> uniqueTestsTimeline = new ArrayList<>();
        List<Integer> targetGoalsTimeline = new ArrayList<>();
        int totalGoals = allGoals.size();
        int parseFailureCount = 0;
        int parseFailStreak = 0;
        int parseFailLimit = Properties.LLM_STRATEGY_PARSE_FAIL_LIMIT;
        int totalParsedTests = 0;
        int totalUniqueTests = 0;
        int totalDuplicates = 0;
        int rounds = 0;
        ExitReason exitReason = ExitReason.STOPPING_CONDITION;

        // Track uncovered goals for feedback between iterations
        Set<TestFitnessFunction> previousUncovered = new HashSet<>(allGoals);

        // Dedup keys for tests already in the suite (structural equality via toCode()).
        Set<String> suiteTestCodes = new HashSet<>();

        // --- Initial broad-coverage query ---
        QueryOutcome initial = queryForBroadCoverage(llmService, deadlineNanos);
        int initialAdded = addUniqueTests(suite, initial.tests, suiteTestCodes);
        int initialDuplicates = initial.tests.size() - initialAdded;
        boolean initialEvaluated = evaluateSuiteAndGoals(
                suite, lastAddedTests(suite, initialAdded),
                fitnessFunctions, allGoals);
        rounds++;
        advanceStoppingCondition(stoppingCondition, rounds, suite,
                initialEvaluated);
        totalParsedTests += initial.tests.size();
        totalUniqueTests += initialAdded;
        totalDuplicates += initialDuplicates;
        ratioTimeline.add(computeParsedRatio(suite));
        Set<TestFitnessFunction> coveredGoals = suite.getCoveredGoals();
        coverageTimeline.add(coveredGoals.size());
        newGoalsTimeline.add(coveredGoals.size());
        recordRound(timelineRecorder, rounds, "INITIAL", strategyStartedNanos,
                deadlineNanos, totalGoals, initial, initialAdded,
                initialDuplicates, coveredGoals.size(), coveredGoals.size(),
                totalGoals, suite, elapsedTimeline, queryLatencyTimeline,
                suiteSizeTimeline, parsedTestsTimeline, uniqueTestsTimeline,
                targetGoalsTimeline);
        sidecarRecorder.record(rounds, elapsedMillis(strategyStartedNanos), suite);

        String previousFailureHint = null;
        if (initial.failureSummary != null) {
            LoggingUtils.getEvoLogger().info(
                    "* Initial broad-coverage query produced no usable tests: {}",
                    initial.failureSummary);
            previousFailureHint = initial.failureSummary;
            parseFailureCount++;
            parseFailStreak++;
        }

        if (isFinishedWithTests(suite, stoppingCondition)) {
            exitReason = isDeadlineExpired(deadlineNanos)
                    ? ExitReason.TIME_BUDGET_EXHAUSTED
                    : ExitReason.STOPPING_CONDITION;
            return finishIterative(suite, 0, rounds, exitReason,
                    parseFailureCount, totalParsedTests, totalUniqueTests,
                    totalDuplicates, ratioTimeline, newGoalsTimeline,
                    coverageTimeline, elapsedTimeline, queryLatencyTimeline,
                    suiteSizeTimeline, parsedTestsTimeline,
                    uniqueTestsTimeline, targetGoalsTimeline, timelineRecorder,
                    sidecarRecorder);
        }

        // --- Iterative follow-up queries ---
        int iteration = 0;
        int noProgressStreak = 0;
        int maxIterations = Properties.LLM_STRATEGY_MAX_ITERATIONS;
        int noProgressLimit = Properties.LLM_STRATEGY_NO_PROGRESS_LIMIT;
        while (true) {
            if (isDeadlineExpired(deadlineNanos)
                    || !TimeController.getInstance().isThereStillTimeInThisPhase()) {
                exitReason = ExitReason.TIME_BUDGET_EXHAUSTED;
                break;
            }
            if (isFinishedWithTests(suite, stoppingCondition)) {
                exitReason = ExitReason.STOPPING_CONDITION;
                break;
            }
            iteration++;
            if (maxIterations > 0 && iteration > maxIterations) {
                iteration--; // we never actually started this iteration
                LoggingUtils.getEvoLogger().info(
                        "* Reached iteration cap ({}), stopping", maxIterations);
                exitReason = ExitReason.MAX_ITERATIONS;
                break;
            }
            final Set<TestFitnessFunction> coveredSnapshot = coveredGoals;
            Collection<TestFitnessFunction> uncoveredGoals = allGoals.stream()
                    .filter(g -> !coveredSnapshot.contains(g))
                    .collect(Collectors.toList());

            if (uncoveredGoals.isEmpty()) {
                iteration--; // never executed
                LoggingUtils.getEvoLogger().info(
                        "* All goals covered after {} iterations", iteration);
                exitReason = ExitReason.ALL_GOALS_COVERED;
                break;
            }

            if (!llmService.isAvailable() || !llmService.hasBudget()) {
                iteration--;
                LoggingUtils.getEvoLogger().info(
                        "* LLM budget exhausted after {} iterations", iteration);
                exitReason = ExitReason.LLM_BUDGET_EXHAUSTED;
                break;
            }

            // Compute newly-covered delta for feedback
            Set<TestFitnessFunction> currentUncovered = new HashSet<>(uncoveredGoals);
            Set<TestFitnessFunction> newlyCovered = new HashSet<>(previousUncovered);
            newlyCovered.removeAll(currentUncovered);
            int coveredCount = coveredGoals.size();
            previousUncovered = currentUncovered;

            QueryOutcome outcome = queryForUncoveredGoals(
                    llmService, uncoveredGoals, suite.getTestChromosomes(),
                    newlyCovered, totalGoals, coveredCount, previousFailureHint,
                    deadlineNanos);
            List<TestChromosome> newTests = outcome.tests;

            int coveredBefore = coveredGoals.size();
            int added = addUniqueTests(suite, newTests, suiteTestCodes);
            int duplicates = newTests.size() - added;
            boolean evaluated = false;
            if (added > 0) {
                evaluated = evaluateSuiteAndGoals(
                        suite, lastAddedTests(suite, added),
                        fitnessFunctions, allGoals);
                coveredGoals = suite.getCoveredGoals();

                if (duplicates > 0) {
                    LoggingUtils.getEvoLogger().info(
                            "* Iteration {}: suite size={}, covered goals={} "
                                    + "(skipped {} duplicate test(s))",
                            iteration, suite.size(), coveredGoals.size(), duplicates);
                } else {
                    LoggingUtils.getEvoLogger().info(
                            "* Iteration {}: suite size={}, covered goals={}",
                            iteration, suite.size(), coveredGoals.size());
                }
            } else if (!newTests.isEmpty()) {
                LoggingUtils.getEvoLogger().info(
                        "* Iteration {}: all {} LLM-produced test(s) were duplicates of existing suite tests",
                        iteration, newTests.size());
            }

            // Per-iteration sample (gap-free).
            ratioTimeline.add(computeParsedRatio(suite));
            int newGoalsThisIter = coveredGoals.size() - coveredBefore;
            newGoalsTimeline.add(newGoalsThisIter);
            coverageTimeline.add(coveredGoals.size());
            rounds++;
            advanceStoppingCondition(stoppingCondition, rounds, suite,
                    evaluated);
            totalParsedTests += newTests.size();
            totalUniqueTests += added;
            totalDuplicates += duplicates;
            recordRound(timelineRecorder, rounds, "FOLLOW_UP",
                    strategyStartedNanos, deadlineNanos, uncoveredGoals.size(),
                    outcome, added, duplicates, newGoalsThisIter,
                    coveredGoals.size(), totalGoals, suite, elapsedTimeline,
                    queryLatencyTimeline, suiteSizeTimeline,
                    parsedTestsTimeline, uniqueTestsTimeline,
                    targetGoalsTimeline);
            sidecarRecorder.record(rounds,
                    elapsedMillis(strategyStartedNanos), suite);

            if (isDeadlineExpired(deadlineNanos)
                    || !TimeController.getInstance().isThereStillTimeInThisPhase()) {
                exitReason = ExitReason.TIME_BUDGET_EXHAUSTED;
                break;
            }

            // Update parse-failure streak/hint.
            if (outcome.failureSummary != null && added == 0) {
                parseFailureCount++;
                parseFailStreak++;
                previousFailureHint = outcome.failureSummary;
                if (parseFailLimit > 0 && parseFailStreak >= parseFailLimit) {
                    LoggingUtils.getEvoLogger().info(
                            "* {} consecutive iterations produced no parsed tests, stopping",
                            parseFailStreak);
                    exitReason = ExitReason.PARSE_FAIL_STREAK;
                    break;
                }
            } else {
                parseFailStreak = 0;
                previousFailureHint = null;
            }

            if (newGoalsThisIter > 0) {
                noProgressStreak = 0;
            } else {
                noProgressStreak++;
                if (noProgressLimit > 0 && noProgressStreak >= noProgressLimit) {
                    LoggingUtils.getEvoLogger().info(
                            "* No new coverage for {} consecutive iterations, stopping",
                            noProgressStreak);
                    exitReason = ExitReason.NO_PROGRESS;
                    break;
                }
            }
        }

        return finishIterative(suite, iteration, rounds, exitReason,
                parseFailureCount, totalParsedTests, totalUniqueTests,
                totalDuplicates, ratioTimeline, newGoalsTimeline,
                coverageTimeline, elapsedTimeline, queryLatencyTimeline,
                suiteSizeTimeline, parsedTestsTimeline, uniqueTestsTimeline,
                targetGoalsTimeline, timelineRecorder, sidecarRecorder);
    }

    /**
     * Emits ITERATIVE_BUDGETED telemetry RuntimeVariables and returns the suite.
     * Centralized so all exit paths emit consistent data.
     */
    private TestSuiteChromosome finishIterative(TestSuiteChromosome suite,
                                                int iteration,
                                                int rounds,
                                                ExitReason exitReason,
                                                int parseFailureCount,
                                                int totalParsedTests,
                                                int totalUniqueTests,
                                                int totalDuplicates,
                                                List<Double> ratioTimeline,
                                                List<Integer> newGoalsTimeline,
                                                List<Integer> coverageTimeline,
                                                List<Long> elapsedTimeline,
                                                List<Long> queryLatencyTimeline,
                                                List<Integer> suiteSizeTimeline,
                                                List<Integer> parsedTestsTimeline,
                                                List<Integer> uniqueTestsTimeline,
                                                List<Integer> targetGoalsTimeline,
                                                IterativeLlmTimelineRecorder timelineRecorder,
                                                IterativeLlmSidecarRecorder sidecarRecorder) {
        emitRatioTimeline(ratioTimeline);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Iterations, iteration);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Rounds, rounds);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Exit_Reason, exitReason.name());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Parse_Failures, parseFailureCount);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_New_Goals_Timeline,
                        joinInts(newGoalsTimeline));
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Coverage_Timeline,
                        joinInts(coverageTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Parsed,
                totalParsedTests);
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Unique,
                totalUniqueTests);
        ClientServices.track(RuntimeVariable.LLM_Iterative_Duplicates,
                totalDuplicates);
        ClientServices.track(RuntimeVariable.LLM_Strategy_Assertions_Retained,
                countAssertions(suite));
        ClientServices.track(RuntimeVariable.LLM_Strategy_Tests_With_Assertions,
                countTestsWithAssertions(suite));
        ClientServices.track(
                RuntimeVariable.LLM_Iterative_Round_Elapsed_Millis_Timeline,
                joinLongs(elapsedTimeline));
        ClientServices.track(
                RuntimeVariable.LLM_Iterative_Query_Latency_Millis_Timeline,
                joinLongs(queryLatencyTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Suite_Size_Timeline,
                joinInts(suiteSizeTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Parsed_Timeline,
                joinInts(parsedTestsTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Tests_Unique_Timeline,
                joinInts(uniqueTestsTimeline));
        ClientServices.track(RuntimeVariable.LLM_Iterative_Target_Goals_Timeline,
                joinInts(targetGoalsTimeline));
        timelineRecorder.flush();
        sidecarRecorder.flush();
        // Each LLM request/evaluation round is the iterative strategy's
        // generation-equivalent unit, including the initial request.
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Generations, rounds);
        iterativeEmittedGenerations = true;
        return suite;
    }

    /** Iterative-mode loop exit reasons; surfaced via {@code LLM_Iterative_Exit_Reason}. */
    private enum ExitReason {
        STOPPING_CONDITION,
        ALL_GOALS_COVERED,
        LLM_BUDGET_EXHAUSTED,
        TIME_BUDGET_EXHAUSTED,
        MAX_ITERATIONS,
        NO_PROGRESS,
        PARSE_FAIL_STREAK,
    }

    /**
     * Marks whether {@link #runIterativeBudgeted} already emitted the
     * {@code Generations} runtime variable so {@link #generateTests} does not
     * overwrite it with the default {@code 1}.
     */
    private boolean iterativeEmittedGenerations = false;

    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static String joinLongs(List<Long> values) {
        return values.stream().map(String::valueOf)
                .collect(Collectors.joining(";"));
    }

    private static String optionalTestCountGuidance(int target, String description) {
        if (target <= 0) {
            return "";
        }
        return " Aim for about " + target + " " + description
                + " tests, but return more when they exercise distinct behavior; "
                + "this is not a hard limit.";
    }

    private static long phaseDeadlineNanos(long nowNanos) {
        long remainingMs =
                TimeController.getInstance().getRemainingTimeInPhaseMs();
        if (remainingMs == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(0L, remainingMs));
        if (Long.MAX_VALUE - nowNanos < remainingNanos) {
            return Long.MAX_VALUE;
        }
        return nowNanos + remainingNanos;
    }

    private static boolean isDeadlineExpired(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE
                && System.nanoTime() >= deadlineNanos;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static long remainingMillis(long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, deadlineNanos - System.nanoTime()));
    }

    /**
     * Applies the normal primary/global stopping conditions while suppressing
     * only the {@code STOP_ZERO} shortcut for an empty suite. A fresh
     * {@link TestSuiteChromosome} has fitness {@code 0.0}, but that does not
     * mean it covered every goal.
     */
    boolean isFinishedWithTests(
            TestSuiteChromosome suite,
            StoppingCondition<TestSuiteChromosome> stoppingCondition) {
        if (stoppingCondition.isFinished()) {
            return true;
        }
        if (suite.size() > 0 && Properties.STOP_ZERO
                && suite.getFitness() == 0.0) {
            return true;
        }
        return !(stoppingCondition instanceof MaxTimeStoppingCondition)
                && getGlobalTime().isFinished();
    }

    void advanceStoppingCondition(
            StoppingCondition<TestSuiteChromosome> stoppingCondition,
            int rounds, TestSuiteChromosome suite, boolean evaluated) {
        if (stoppingCondition instanceof MaxGenerationStoppingCondition) {
            stoppingCondition.forceCurrentValue(rounds);
        } else if (evaluated && stoppingCondition
                instanceof MaxFitnessEvaluationsStoppingCondition) {
            stoppingCondition.fitnessEvaluation(suite);
        }
    }

    private void recordRound(
            IterativeLlmTimelineRecorder recorder,
            int round, String roundType,
            long strategyStartedNanos, long deadlineNanos,
            int targetGoals, QueryOutcome outcome,
            int added, int duplicates, int newGoals,
            int coveredGoals, int totalGoals,
            TestSuiteChromosome suite,
            List<Long> elapsedTimeline,
            List<Long> queryLatencyTimeline,
            List<Integer> suiteSizeTimeline,
            List<Integer> parsedTestsTimeline,
            List<Integer> uniqueTestsTimeline,
            List<Integer> targetGoalsTimeline) {
        long elapsedMs = elapsedMillis(strategyStartedNanos);
        elapsedTimeline.add(elapsedMs);
        queryLatencyTimeline.add(outcome.latencyMillis);
        suiteSizeTimeline.add(suite.size());
        parsedTestsTimeline.add(outcome.tests.size());
        uniqueTestsTimeline.add(added);
        targetGoalsTimeline.add(targetGoals);
        recorder.record(round, roundType, elapsedMs,
                remainingMillis(deadlineNanos), targetGoals,
                outcome.latencyMillis, outcome.tests.size(), added, duplicates,
                newGoals, coveredGoals, totalGoals, suite.size(),
                suite.totalLengthOfTestCases(), suite.getCoverage(),
                countAssertions(suite), countTestsWithAssertions(suite),
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
    }

    private static int countAssertions(TestSuiteChromosome suite) {
        int count = 0;
        for (TestChromosome test : suite.getTestChromosomes()) {
            count += test.getTestCase().getAssertions().size();
        }
        return count;
    }

    private static int countTestsWithAssertions(TestSuiteChromosome suite) {
        int count = 0;
        for (TestChromosome test : suite.getTestChromosomes()) {
            if (test.getTestCase().hasAssertions()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Initial LLM query: broad coverage of the CUT.
     */
    private QueryOutcome queryForBroadCoverage(
            LlmService llmService, long deadlineNanos) {
        if (!llmService.isAvailable()) {
            LoggingUtils.getEvoLogger().info("* LLM broad-coverage query skipped: service is not available");
            return QueryOutcome.failure("LLM service not available");
        }
        if (!llmService.hasBudget()) {
            LoggingUtils.getEvoLogger().info("* LLM broad-coverage query skipped: call budget exhausted");
            return QueryOutcome.failure("LLM call budget exhausted");
        }
        PromptResult prompt = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(null, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate as many JUnit test methods for the target class as necessary. "
                        + "Focus on diverse paths, edge cases, and branch coverage."
                        + optionalTestCountGuidance(Properties.LLM_STRATEGY_INITIAL_TARGET_TESTS,
                        "broadly covering")
                        + LlmAssertionPolicyResolver.instructionSuffix(LLM_STRATEGY_CONTEXT))
                .buildWithMetadata();

        return queryAndParse(llmService, prompt,
                LlmFeature.ITERATIVE_STRATEGY, deadlineNanos);
    }

    /**
     * Follow-up LLM query targeting specific uncovered goals, with coverage feedback.
     */
    private QueryOutcome queryForUncoveredGoals(
            LlmService llmService,
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> currentTests,
            Set<TestFitnessFunction> newlyCovered,
            int totalGoals, int coveredCount,
            String previousFailureHint,
            long deadlineNanos) {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withCoverageFeedback(newlyCovered, totalGoals, coveredCount)
                .withUncoveredGoals(uncoveredGoals)
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(uncoveredGoals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);

        if (previousFailureHint != null && !previousFailureHint.isEmpty()) {
            builder.withError("Your previous response could not be used: "
                    + previousFailureHint
                    + "\nReturn well-formed, compilable JUnit code in the next response.");
        }

        builder.withInstruction("The following coverage goals are still uncovered. "
                + "Use the method groups below as a checklist. Generate as many "
                + "distinct useful JUnit test methods as necessary, specifically "
                + "targeting these uncovered goals. Prefer tests that cover multiple "
                + "uncovered goals at once, and try to cover at least one goal from "
                + "several different method groups when possible. Continue broad "
                + "coverage exploration when it may reach uncovered code indirectly "
                + "through public entry points, constructors, state setup, and "
                + "boundary values. For each test, build realistic object states "
                + "that drive execution toward the uncovered methods or branches. "
                + "Prefer direct public or package-visible APIs over reflection. "
                + "Do not repeat existing tests or trivial variants of them; use "
                + "different inputs, object states, call sequences, or exception "
                + "paths. Assertions should check observable behavior reached by "
                + "the test, but coverage-driving setup and calls are more "
                + "important than exhaustive assertion detail."
                + optionalTestCountGuidance(Properties.LLM_STRATEGY_FOLLOWUP_TARGET_TESTS,
                "focused")
                + LlmAssertionPolicyResolver.instructionSuffix(LLM_STRATEGY_CONTEXT));

        if (currentTests != null && !currentTests.isEmpty()) {
            List<org.evosuite.testcase.TestCase> existing =
                    TestRelevanceRanker.rankByRelevance(currentTests, uncoveredGoals, 3)
                    .stream()
                    .map(TestChromosome::getTestCase)
                    .collect(Collectors.toList());
            builder.withExistingTests(existing);
        }
        PromptResult prompt = builder.buildWithMetadata();
        return queryAndParse(llmService, prompt,
                LlmFeature.ITERATIVE_STRATEGY, deadlineNanos);
    }

    /**
     * Outcome of a single {@code query + parse + repair} round. Beyond the
     * (possibly empty) list of parsed tests, captures a short failure summary
     * so the next iteration can feed it back to the LLM as corrective context.
     */
    private static final class QueryOutcome {
        final List<TestChromosome> tests;
        /** Short, single-line summary of what went wrong; {@code null} on success. */
        final String failureSummary;
        final long latencyMillis;

        QueryOutcome(List<TestChromosome> tests, String failureSummary,
                     long latencyMillis) {
            this.tests = tests;
            this.failureSummary = failureSummary;
            this.latencyMillis = latencyMillis;
        }

        static QueryOutcome success(List<TestChromosome> tests, long latencyMillis) {
            return new QueryOutcome(tests, null, latencyMillis);
        }

        static QueryOutcome failure(String summary) {
            return failure(summary, 0L);
        }

        static QueryOutcome failure(String summary, long latencyMillis) {
            return new QueryOutcome(Collections.<TestChromosome>emptyList(),
                    summary, latencyMillis);
        }
    }

    /**
     * Sends the prompt to {@link LlmService} and runs the response through
     * {@link TestRepairLoop}. Each underlying call is bounded by
     * {@code LLM_TIMEOUT_SECONDS} inside {@code LlmService.query}; on timeout
     * (or any other failure) this method returns a failure outcome and the
     * iterative loop continues with the next iteration, feeding the failure
     * summary back to the LLM. Repair attempts inside {@code TestRepairLoop}
     * likewise share that per-call timeout.
     */
    private QueryOutcome queryAndParse(
            LlmService llmService, PromptResult prompt,
            LlmFeature feature, long deadlineNanos) {
        long startedNanos = System.nanoTime();
        try {
            String response = llmService.query(prompt, feature, deadlineNanos);
            RepairResult result = createRepairLoop(
                    llmService,
                    TestRepairLoop.RepairOptions.forAssertionPolicy(
                            LlmAssertionPolicyResolver.keepAssertions(LLM_STRATEGY_CONTEXT)))
                    .attemptParse(response, prompt.getMessages(), feature,
                            deadlineNanos);
            long latencyMillis = elapsedMillis(startedNanos);
            if (!result.isSuccess()) {
                String hint = topDiagnostic(result.getDiagnostics());
                return QueryOutcome.failure("Parse/repair failed: "
                        + (hint == null ? "no diagnostic" : hint),
                        latencyMillis);
            }
            List<TestChromosome> tests = toChromosomes(result);
            if (tests.isEmpty()) {
                return QueryOutcome.failure(
                        "Parse/repair succeeded but produced zero usable tests",
                        latencyMillis);
            }
            return QueryOutcome.success(tests, latencyMillis);
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM query failed during generation: {}", e.getMessage());
            logger.warn("LLM query failed during generation: {}", e.getMessage());
            return QueryOutcome.failure("LLM call failed: " + e.getMessage(),
                    elapsedMillis(startedNanos));
        } catch (RuntimeException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM query crashed during generation: {}", e.getMessage());
            logger.error("LLM query crashed during generation", e);
            return QueryOutcome.failure(
                    "LLM call crashed: " + e.getClass().getSimpleName(),
                    elapsedMillis(startedNanos));
        }
    }

    /** Returns the first non-blank diagnostic line, truncated to 200 chars, or null. */
    private static String topDiagnostic(List<String> diagnostics) {
        if (diagnostics == null) {
            return null;
        }
        for (String d : diagnostics) {
            if (d != null && !d.trim().isEmpty()) {
                String firstLine = d.trim().split("\\R", 2)[0];
                return firstLine.length() > 200
                        ? firstLine.substring(0, 200) + "…"
                        : firstLine;
            }
        }
        return null;
    }

    private boolean evaluateSuiteAndGoals(
            TestSuiteChromosome suite,
            List<TestChromosome> newlyAddedTests,
            List<TestSuiteFitnessFunction> fitnessFunctions,
            List<TestFitnessFunction> allGoals) {
        // Skip empty suites: not all TestSuiteFitnessFunctions are null-safe on
        // empty input, and there is no useful fitness to compute when there are
        // no tests yet (initial broad query failed, or all candidates were dupes).
        if (suite.size() == 0 || newlyAddedTests == null
                || newlyAddedTests.isEmpty()) {
            return false;
        }
        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
        ensureExecuted(suite);
        measureGoalFitness(newlyAddedTests, allGoals);
        return true;
    }

    /**
     * Stores each active goal's distance for newly admitted tests. The cached
     * execution result is reused by ordinary coverage goals; criteria whose
     * semantics require extra executions (for example strong mutation) may
     * still perform those criterion-specific executions.
     */
    void measureGoalFitness(List<TestChromosome> tests,
                            List<TestFitnessFunction> goals) {
        if (tests == null || tests.isEmpty() || goals == null || goals.isEmpty()) {
            return;
        }
        for (TestChromosome test : tests) {
            ExecutionResult result = test.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            for (TestFitnessFunction goal : goals) {
                try {
                    double fitness = goal.getFitness(test, result);
                    test.setFitness(goal, fitness);
                    if (fitness == 0.0) {
                        test.getTestCase().addCoveredGoal(goal);
                    }
                } catch (RuntimeException e) {
                    logger.debug("Could not measure goal fitness for an LLM-added test", e);
                }
            }
        }
    }

    /**
     * Ensures every chromosome in {@code suite} has a cached
     * {@code getLastExecutionResult()}. Standard suite fitness functions populate
     * this via {@code runTestSuite}, but non-standard configurations might not;
     * {@link TestRelevanceRanker} silently degrades to first-N selection when
     * execution results are missing, so we backfill defensively.
     */
    private void ensureExecuted(TestSuiteChromosome suite) {
        for (TestChromosome tc : suite.getTestChromosomes()) {
            if (tc.getLastExecutionResult() != null) {
                continue;
            }
            try {
                ExecutionResult result = TestCaseExecutor.getInstance().execute(tc.getTestCase());
                if (result != null) {
                    tc.setLastExecutionResult(result);
                    tc.setChanged(false);
                }
            } catch (RuntimeException e) {
                logger.debug("Backfill execution failed for an LLM-added test", e);
            }
        }
    }

    /**
     * Adds chromosomes to {@code suite} skipping any whose rendered code is
     * already represented in {@code seenCodes}. Returns the number of tests
     * actually added.
     */
    private int addUniqueTests(TestSuiteChromosome suite,
                               List<TestChromosome> candidates,
                               Set<String> seenCodes) {
        int added = 0;
        for (TestChromosome tc : candidates) {
            String code;
            try {
                code = tc.getTestCase().toCode();
            } catch (RuntimeException e) {
                // If code rendering fails, fall back to identity-based admission
                // rather than dropping the candidate silently.
                code = null;
            }
            if (code != null && !seenCodes.add(code)) {
                continue;
            }
            suite.addTest(tc);
            added++;
        }
        return added;
    }

    private List<TestChromosome> lastAddedTests(TestSuiteChromosome suite,
                                                int added) {
        if (added <= 0) {
            return Collections.emptyList();
        }
        List<TestChromosome> tests = suite.getTestChromosomes();
        int fromIndex = Math.max(0, tests.size() - added);
        return new ArrayList<>(tests.subList(fromIndex, tests.size()));
    }

    private List<TestChromosome> toChromosomes(RepairResult repairResult) {
        List<TestChromosome> chromosomes = new ArrayList<>();
        repairResult.getTestCases().stream()
                .forEach(testCase -> {
                    TestChromosome tc = new TestChromosome();
                    tc.setTestCase(testCase);
                    chromosomes.add(tc);
                });
        return chromosomes;
    }

    protected TestRepairLoop createRepairLoop(LlmService llmService) {
        return createRepairLoop(llmService, TestRepairLoop.RepairOptions.defaults());
    }

    protected TestRepairLoop createRepairLoop(LlmService llmService,
                                              TestRepairLoop.RepairOptions options) {
        return TestRepairLoop.createDefault(llmService, options);
    }

    protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
        return getFitnessFactories();
    }

    protected LlmSeededPopulationFactory createSeededFactory() {
        return new LlmSeededPopulationFactory(new RandomLengthTestFactory(), true);
    }

    protected LlmService getLlmService() {
        return LlmService.getInstance();
    }

    /**
     * Computes the ratio of LLM-parsed statements to total statements in a suite.
     */
    public static double computeParsedRatio(TestSuiteChromosome suite) {
        int total = 0;
        int parsed = 0;
        for (TestChromosome tc : suite.getTestChromosomes()) {
            for (int i = 0; i < tc.getTestCase().size(); i++) {
                total++;
                if (tc.getTestCase().getStatement(i).isParsedFromLlm()) {
                    parsed++;
                }
            }
        }
        return total > 0 ? (double) parsed / total : 0.0;
    }

    private void emitRatioTimeline(List<Double> timeline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < timeline.size(); i++) {
            if (i > 0) {
                sb.append(";");
            }
            sb.append(String.format(Locale.ROOT, "%.4f", timeline.get(i)));
        }
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline,
                        sb.toString());
    }
}
