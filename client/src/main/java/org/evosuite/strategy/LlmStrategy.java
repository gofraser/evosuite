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
import org.evosuite.ga.stoppingconditions.StoppingCondition;
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
        if (!iterativeEmittedGenerations) {
            ClientServices.getInstance().getClientNode()
                    .trackOutputVariable(RuntimeVariable.Generations, 1);
        }
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
     *   <li>Evaluate tests, compute uncovered goals (with best-known fitness
     *       distances) and the newly-covered delta vs. the previous iteration</li>
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
        TestSuiteChromosome suite = new TestSuiteChromosome();
        List<Double> ratioTimeline = new ArrayList<>();
        List<Integer> newGoalsTimeline = new ArrayList<>();
        List<Integer> coverageTimeline = new ArrayList<>();
        int totalGoals = allGoals.size();
        int parseFailureCount = 0;
        int parseFailStreak = 0;
        int parseFailLimit = Properties.LLM_STRATEGY_PARSE_FAIL_LIMIT;
        ExitReason exitReason = ExitReason.STOPPING_CONDITION;

        // Track uncovered goals for feedback between iterations
        Set<TestFitnessFunction> previousUncovered = new HashSet<>(allGoals);

        // Dedup keys for tests already in the suite (structural equality via toCode()).
        Set<String> suiteTestCodes = new HashSet<>();

        // --- Initial broad-coverage query ---
        QueryOutcome initial = queryForBroadCoverage(llmService);
        addUniqueTests(suite, initial.tests, suiteTestCodes);
        evaluateSuite(suite, fitnessFunctions);
        ensureExecuted(suite);
        ratioTimeline.add(computeParsedRatio(suite));
        Set<TestFitnessFunction> coveredGoals = suite.getCoveredGoals();
        coverageTimeline.add(coveredGoals.size());
        newGoalsTimeline.add(coveredGoals.size());

        String previousFailureHint = null;
        if (initial.failureSummary != null) {
            LoggingUtils.getEvoLogger().info(
                    "* Initial broad-coverage query produced no usable tests: {}",
                    initial.failureSummary);
            previousFailureHint = initial.failureSummary;
            parseFailureCount++;
            parseFailStreak++;
        }

        if (isFinished(suite, stoppingCondition)) {
            exitReason = ExitReason.STOPPING_CONDITION;
            return finishIterative(suite, 0, exitReason, parseFailureCount,
                    ratioTimeline, newGoalsTimeline, coverageTimeline);
        }

        // --- Iterative follow-up queries ---
        int iteration = 0;
        int noProgressStreak = 0;
        int maxIterations = Properties.LLM_STRATEGY_MAX_ITERATIONS;
        int noProgressLimit = Properties.LLM_STRATEGY_NO_PROGRESS_LIMIT;
        while (true) {
            if (isFinished(suite, stoppingCondition)) {
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
                    newlyCovered, totalGoals, coveredCount, previousFailureHint);
            List<TestChromosome> newTests = outcome.tests;

            int coveredBefore = coveredGoals.size();
            int added = addUniqueTests(suite, newTests, suiteTestCodes);
            if (added > 0) {
                evaluateSuite(suite, fitnessFunctions);
                ensureExecuted(suite);
                coveredGoals = suite.getCoveredGoals();

                int duplicates = newTests.size() - added;
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

        return finishIterative(suite, iteration, exitReason, parseFailureCount,
                ratioTimeline, newGoalsTimeline, coverageTimeline);
    }

    /**
     * Emits ITERATIVE_BUDGETED telemetry RuntimeVariables and returns the suite.
     * Centralized so all exit paths emit consistent data.
     */
    private TestSuiteChromosome finishIterative(TestSuiteChromosome suite,
                                                int iteration,
                                                ExitReason exitReason,
                                                int parseFailureCount,
                                                List<Double> ratioTimeline,
                                                List<Integer> newGoalsTimeline,
                                                List<Integer> coverageTimeline) {
        emitRatioTimeline(ratioTimeline);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Iterative_Iterations, iteration);
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
        // Override the strategy-level Generations=1: in iterative mode each
        // follow-up is conceptually a "generation".
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Generations, iteration);
        iterativeEmittedGenerations = true;
        return suite;
    }

    /** Iterative-mode loop exit reasons; surfaced via {@code LLM_Iterative_Exit_Reason}. */
    private enum ExitReason {
        STOPPING_CONDITION,
        ALL_GOALS_COVERED,
        LLM_BUDGET_EXHAUSTED,
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

    /**
     * Initial LLM query: broad coverage of the CUT.
     */
    private QueryOutcome queryForBroadCoverage(
            LlmService llmService) {
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
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(null, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate JUnit test methods that maximize code coverage of the target class. "
                        + "Cover all reachable methods, branches, boundary values, and exception paths."
                        + LlmAssertionPolicyResolver.instructionSuffix(LLM_STRATEGY_CONTEXT))
                .buildWithMetadata();

        return queryAndParse(llmService, prompt,
                LlmFeature.ITERATIVE_STRATEGY);
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
            String previousFailureHint) {
        Map<TestFitnessFunction, Double> bestFitnessPerGoal =
                bestFitnessPerGoal(currentTests, uncoveredGoals);
        // Sort goals by closeness (ascending fitness distance) so the LLM sees
        // the goals most likely to be reachable first. Goals without a known
        // distance sort to the end.
        Collection<TestFitnessFunction> sortedGoals = sortByFitness(uncoveredGoals, bestFitnessPerGoal);
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withCoverageFeedback(newlyCovered, totalGoals, coveredCount)
                .withUncoveredGoals(sortedGoals, bestFitnessPerGoal)
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(uncoveredGoals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);

        if (previousFailureHint != null && !previousFailureHint.isEmpty()) {
            builder.withError("Your previous response could not be used: "
                    + previousFailureHint
                    + "\nReturn well-formed, compilable JUnit code in the next response.");
        }

        builder.withInstruction("The following coverage goals are still uncovered. "
                + "Generate JUnit test methods specifically targeting these uncovered goals."
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
                LlmFeature.ITERATIVE_STRATEGY);
    }

    /**
     * Returns the goals sorted ascending by their best-known fitness distance.
     * Goals absent from {@code fitnessDistances} sort after those that have a
     * known distance, preserving their relative input order.
     */
    private List<TestFitnessFunction> sortByFitness(
            Collection<TestFitnessFunction> goals,
            Map<TestFitnessFunction, Double> fitnessDistances) {
        List<TestFitnessFunction> sorted = new ArrayList<>(goals);
        if (fitnessDistances == null || fitnessDistances.isEmpty()) {
            return sorted;
        }
        sorted.sort((a, b) -> {
            Double da = fitnessDistances.get(a);
            Double db = fitnessDistances.get(b);
            if (da == null && db == null) {
                return 0;
            }
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return Double.compare(da, db);
        });
        return sorted;
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

        QueryOutcome(List<TestChromosome> tests, String failureSummary) {
            this.tests = tests;
            this.failureSummary = failureSummary;
        }

        static QueryOutcome success(List<TestChromosome> tests) {
            return new QueryOutcome(tests, null);
        }

        static QueryOutcome failure(String summary) {
            return new QueryOutcome(Collections.<TestChromosome>emptyList(), summary);
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
            LlmFeature feature) {
        try {
            String response = llmService.query(prompt, feature);
            RepairResult result = createRepairLoop(
                    llmService,
                    TestRepairLoop.RepairOptions.forAssertionPolicy(
                            LlmAssertionPolicyResolver.keepAssertions(LLM_STRATEGY_CONTEXT)))
                    .attemptParse(response, prompt.getMessages(), feature);
            if (!result.isSuccess()) {
                String hint = topDiagnostic(result.getDiagnostics());
                return QueryOutcome.failure("Parse/repair failed: "
                        + (hint == null ? "no diagnostic" : hint));
            }
            List<TestChromosome> tests = toChromosomes(result);
            if (tests.isEmpty()) {
                return QueryOutcome.failure(
                        "Parse/repair succeeded but produced zero usable tests");
            }
            return QueryOutcome.success(tests);
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM query failed during generation: {}", e.getMessage());
            logger.warn("LLM query failed during generation: {}", e.getMessage());
            return QueryOutcome.failure("LLM call failed: " + e.getMessage());
        } catch (RuntimeException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM query crashed during generation: {}", e.getMessage());
            logger.error("LLM query crashed during generation", e);
            return QueryOutcome.failure("LLM call crashed: " + e.getClass().getSimpleName());
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

    private void evaluateSuite(TestSuiteChromosome suite,
                               List<TestSuiteFitnessFunction> fitnessFunctions) {
        // Skip empty suites: not all TestSuiteFitnessFunctions are null-safe on
        // empty input, and there is no useful fitness to compute when there are
        // no tests yet (initial broad query failed, or all candidates were dupes).
        if (suite.size() == 0) {
            return;
        }
        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
    }

    /**
     * Computes, per uncovered goal, the lowest cached fitness across the given
     * tests — i.e. how close the closest existing test is to covering each goal.
     * Used by {@link CoverageGoalFormatter} to annotate "almost covered" goals
     * in follow-up prompts. Values are read via {@code chromosome.getFitness(goal)}
     * which hits the chromosome's fitness cache; uncached goals are skipped.
     */
    private Map<TestFitnessFunction, Double> bestFitnessPerGoal(
            List<TestChromosome> tests,
            Collection<TestFitnessFunction> goals) {
        if (tests == null || tests.isEmpty() || goals == null || goals.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TestFitnessFunction, Double> best = new HashMap<>();
        for (TestFitnessFunction goal : goals) {
            double min = Double.POSITIVE_INFINITY;
            boolean found = false;
            for (TestChromosome tc : tests) {
                if (!tc.getFitnessValues().containsKey(goal)) {
                    continue;
                }
                double f = tc.getFitnessValues().get(goal);
                if (f < min) {
                    min = f;
                    found = true;
                }
            }
            if (found) {
                best.put(goal, min);
            }
        }
        return best;
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
