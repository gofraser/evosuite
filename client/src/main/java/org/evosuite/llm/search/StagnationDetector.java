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

import org.evosuite.Properties;
import org.evosuite.ga.diversity.DefaultSpeciesAssigner;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.CoverageGoalFormatter;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TestRelevanceRanker;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Detects search stagnation and requests targeted LLM assistance.
 *
 * <p>Fires when wall-clock seconds without a fitness/coverage improvement
 * exceed the configured threshold. The window resets on every improvement
 * and on every fired call.
 */
public class StagnationDetector {

    private static final Logger logger = LoggerFactory.getLogger(StagnationDetector.class);
    private static final double EPSILON = 1e-12;
    private static final int EXISTING_TESTS_CONTEXT_MAX = 3;
    private static final int EXISTING_TESTS_RELEVANCE_CANDIDATE_MULTIPLIER = 4;
    private static final int DIAGNOSTIC_PROBLEM_CARDS_MAX = 3;

    private final LlmService llmService;
    private final boolean maximizationObjective;
    private final long stagnationThresholdNanos;
    private final int thresholdSeconds;
    private final int testsPerRequest;
    private final LongSupplier nanoClock;
    private final ProblemCardExtractor problemCardExtractor = new ProblemCardExtractor();
    private final DiagnosticCardSelector diagnosticCardSelector = new DiagnosticCardSelector();
    private final ExceptionBarrierTracker exceptionBarrierTracker = new ExceptionBarrierTracker();
    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final RepeatedInjectionMemory repeatedInjectionMemory;
    /**
     * Volatile: in ASYNC stagnation mode, {@link #consumeWindow()} is called
     * from the background worker thread on call completion (T2), while
     * {@link #peekStagnation} is polled from the GA evolve thread.
     */
    private volatile long windowStartNanos;
    private volatile boolean windowStarted = false;
    private Double bestFitness = null;
    private Integer coveredGoals = null;
    /**
     * Cached repair loop reused across calls. Lazily initialised on first use
     * (so a detector that never fires doesn't allocate one). The
     * {@code keepAssertions} flag is resolved on first use and pinned for the
     * detector's lifetime, matching {@link AsyncLlmTestProducer}'s pattern.
     */
    private transient TestRepairLoop cachedRepairLoop;

    /** Creates a detector with singleton LLM service and Properties-configured thresholds. */
    public StagnationDetector() {
        this(LlmService.getInstance(), false,
                Properties.LLM_STAGNATION_TIMEOUT_SECONDS, Properties.LLM_STAGNATION_TESTS,
                (RepeatedInjectionMemory) null);
    }

    /** Creates a detector with the given maximization flag and Properties-configured thresholds. */
    public StagnationDetector(boolean maximizationObjective) {
        this(LlmService.getInstance(), maximizationObjective,
                Properties.LLM_STAGNATION_TIMEOUT_SECONDS, Properties.LLM_STAGNATION_TESTS,
                (RepeatedInjectionMemory) null);
    }

    /** Creates a detector with explicit LLM service, maximization flag, and threshold settings. */
    public StagnationDetector(LlmService llmService,
                              boolean maximizationObjective,
                              int stagnationTimeoutSeconds,
                              int testsPerRequest) {
        this(llmService, maximizationObjective, stagnationTimeoutSeconds, testsPerRequest,
                (RepeatedInjectionMemory) null);
    }

    public StagnationDetector(LlmService llmService,
                              boolean maximizationObjective,
                              int stagnationTimeoutSeconds,
                              int testsPerRequest,
                              RepeatedInjectionMemory repeatedInjectionMemory) {
        this(llmService, maximizationObjective, stagnationTimeoutSeconds, testsPerRequest,
                System::nanoTime, repeatedInjectionMemory);
    }

    /** Package-private constructor for tests: allows injecting a clock. */
    StagnationDetector(LlmService llmService,
                       boolean maximizationObjective,
                       int stagnationTimeoutSeconds,
                       int testsPerRequest,
                       LongSupplier nanoClock) {
        this(llmService, maximizationObjective, stagnationTimeoutSeconds, testsPerRequest,
                nanoClock, null);
    }

    StagnationDetector(LlmService llmService,
                       boolean maximizationObjective,
                       int stagnationTimeoutSeconds,
                       int testsPerRequest,
                       LongSupplier nanoClock,
                       RepeatedInjectionMemory repeatedInjectionMemory) {
        this.llmService = llmService;
        this.maximizationObjective = maximizationObjective;
        this.thresholdSeconds = Math.max(1, stagnationTimeoutSeconds);
        this.stagnationThresholdNanos = TimeUnit.SECONDS.toNanos(this.thresholdSeconds);
        this.testsPerRequest = Math.max(1, testsPerRequest);
        this.nanoClock = nanoClock;
        this.repeatedInjectionMemory = repeatedInjectionMemory;
    }

    /**
     * Reports whether the search has stagnated based on the current best fitness,
     * and consumes the stagnation window if so. Equivalent to
     * {@link #peekStagnation(double)} followed by {@link #consumeWindow()} when
     * the peek returns true.
     */
    public boolean checkStagnation(double currentBestFitness) {
        boolean stagnated = peekStagnation(currentBestFitness);
        if (stagnated) {
            consumeWindow();
        }
        return stagnated;
    }

    /**
     * Reports whether the search has stagnated based on the current covered goals
     * count, and consumes the stagnation window if so. Equivalent to
     * {@link #peekStagnation(int)} followed by {@link #consumeWindow()} when the
     * peek returns true.
     */
    public boolean checkStagnation(int currentCoveredGoals) {
        boolean stagnated = peekStagnation(currentCoveredGoals);
        if (stagnated) {
            consumeWindow();
        }
        return stagnated;
    }

    /**
     * Non-consuming variant of {@link #checkStagnation(double)}: returns true when
     * the stagnation window has elapsed but does NOT reset it. Updates the
     * tracked best-fitness baseline on observed improvement (which always resets
     * the window). Callers that may decide to skip the actual stagnation action
     * (budget guard, in-flight de-dup, etc.) should peek first and only call
     * {@link #consumeWindow()} once they have committed to firing.
     */
    public boolean peekStagnation(double currentBestFitness) {
        if (bestFitness == null) {
            bestFitness = currentBestFitness;
            startWindow();
            return false;
        }
        boolean improved = maximizationObjective
                ? currentBestFitness > (bestFitness + EPSILON)
                : currentBestFitness < (bestFitness - EPSILON);
        if (improved) {
            bestFitness = currentBestFitness;
            startWindow();
            return false;
        }
        return peekTimeWindow();
    }

    /**
     * Non-consuming variant of {@link #checkStagnation(int)}: returns true when
     * the stagnation window has elapsed but does NOT reset it. Updates the
     * tracked covered-goals baseline on observed improvement (which always
     * resets the window). Callers that may decide to skip the actual stagnation
     * action (budget guard, in-flight de-dup, etc.) should peek first and only
     * call {@link #consumeWindow()} once they have committed to firing.
     */
    public boolean peekStagnation(int currentCoveredGoals) {
        if (coveredGoals == null) {
            coveredGoals = currentCoveredGoals;
            startWindow();
            return false;
        }
        if (currentCoveredGoals > coveredGoals) {
            coveredGoals = currentCoveredGoals;
            startWindow();
            return false;
        }
        return peekTimeWindow();
    }

    /**
     * Resets the stagnation window. Call this only after committing to a
     * stagnation action (e.g., dispatching an LLM call). Skip-decisions that
     * do not result in any action should leave the window intact so the next
     * generation still sees stagnation.
     *
     * <p>Safe to call from a background worker thread: in ASYNC stagnation
     * mode, {@code StagnationLlmHelper} re-arms the window from the worker
     * thread once an async call completes, so that the next stagnation
     * cadence is measured from completion rather than submission (T2).
     */
    public void consumeWindow() {
        startWindow();
    }

    private void startWindow() {
        windowStartNanos = nanoClock.getAsLong();
        windowStarted = true;
    }

    private boolean peekTimeWindow() {
        if (!windowStarted) {
            startWindow();
            return false;
        }
        return nanoClock.getAsLong() - windowStartNanos >= stagnationThresholdNanos;
    }

    public int getTestsPerRequest() {
        return testsPerRequest;
    }

    public LlmService getLlmService() {
        return llmService;
    }

    /** Requests LLM-generated tests targeting uncovered goals when the search has stagnated. */
    public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> currentPopulation) {
        return requestHelp(uncoveredGoals, currentPopulation, 0, 0, null);
    }

    /**
     * Executes the stagnation LLM call for a pre-built prompt: query, parse, and
     * repair. Returns the tests produced (possibly empty). Performs no
     * thread-unsafe reads of the population — safe to invoke from a background
     * worker.
     */
    public List<TestChromosome> executeWithPrompt(PromptResult prompt) {
        return executeWithPrompt(prompt, Long.MAX_VALUE);
    }

    /**
     * Executes the stagnation LLM call with a soft parse/repair deadline. The
     * deadline is intentionally enforced before launching additional repair
     * requests so already parsed or salvaged tests can be returned to the SYNC
     * caller before the outer future timeout cancels the worker.
     */
    public List<TestChromosome> executeWithPrompt(PromptResult prompt, long repairDeadlineNanos) {
        if (prompt == null) {
            return Collections.emptyList();
        }
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = repairLoop()
                    .attemptParse(response, prompt.getMessages(), LlmFeature.STAGNATION,
                            repairDeadlineNanos);
            if (!result.isSuccess()) {
                return Collections.emptyList();
            }
            return result.toChromosomes();
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("Stagnation LLM request failed: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.debug("Stagnation LLM request crashed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Lazily constructs and caches one {@link TestRepairLoop} for the lifetime
     * of this detector. The {@code keepAssertions} flag is resolved on first
     * use; subsequent property changes are intentionally ignored to keep the
     * detector's behaviour stable across a single search (and to match
     * {@link AsyncLlmTestProducer}'s pattern of building the loop once outside
     * its main loop).
     *
     * <p>Thread-safe enough for our use: a benign race could initialise the
     * field twice if SYNC and ASYNC callers were to race; both instances are
     * functionally equivalent and {@link TestRepairLoop#attemptParse} resets
     * its per-call state at the top of each invocation. In practice
     * {@code maybeSubmit} dispatches at most one call at a time.
     */
    private TestRepairLoop repairLoop() {
        TestRepairLoop loop = cachedRepairLoop;
        if (loop == null) {
            boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
            loop = TestRepairLoop.createDefault(llmService,
                    TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions));
            cachedRepairLoop = loop;
        }
        return loop;
    }

    /**
     * Requests LLM-generated tests with enriched diagnostic context.
     *
     * @param uncoveredGoals     remaining uncovered goals
     * @param currentPopulation  current best tests
     * @param totalGoals         total number of coverage goals (0 to omit from prompt)
     * @param coveredGoalCount   number of goals already covered
     * @param bestFitnessPerGoal optional fitness-distance map for "almost covered" annotations
     */
    public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> currentPopulation,
                                            int totalGoals, int coveredGoalCount,
                                            Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return Collections.emptyList();
        }

        PromptResult prompt = buildPrompt(uncoveredGoals, currentPopulation,
                totalGoals, coveredGoalCount, bestFitnessPerGoal, false);
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                registerRepeatedAttempt(prompt, false);
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = repairLoop()
                    .attemptParse(response, prompt.getMessages(), LlmFeature.STAGNATION);
            if (!result.isSuccess()) {
                if (registration != null && !registration.isEmpty()) {
                    repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                }
                return Collections.emptyList();
            }
            if (registration != null && !registration.isEmpty()) {
                repeatedInjectionMemory.clearAttempt(registration.getAttemptId());
            }
            return result.toChromosomes();
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("Stagnation LLM request failed: {}", e.getMessage());
            if (registration != null && !registration.isEmpty()) {
                repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
            }
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.debug("Stagnation LLM request crashed: {}", e.getMessage());
            if (registration != null && !registration.isEmpty()) {
                repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
            }
            return Collections.emptyList();
        }
    }

    public void reset() {
        windowStarted = false;
        exceptionBarrierTracker.reset();
    }

    /**
     * Returns the per-goal best fitness over the given population. Computed on
     * the caller's thread because it iterates the live population and computes
     * fitness against each goal.
     */
    public static Map<TestFitnessFunction, Double> computeBestFitnessPerGoal(
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> population) {
        Map<TestFitnessFunction, Double> bestFitness = new LinkedHashMap<>();
        if (uncoveredGoals == null) {
            return bestFitness;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            double best = Double.MAX_VALUE;
            if (population != null) {
                for (TestChromosome tc : population) {
                    double f = goal.getFitness(tc);
                    if (f < best) {
                        best = f;
                    }
                }
            }
            bestFitness.put(goal, best);
        }
        return bestFitness;
    }

    /**
     * Builds the stagnation prompt for the given snapshot. Public so async
     * callers can build the prompt on the search thread and then submit the
     * LLM call on a background worker.
     *
     * <p>Branches on {@link Properties#LLM_STAGNATION_PROMPT}: {@code POOL}
     * sends the full uncovered-goal set plus top-3 relevant tests with
     * population-wide fitness annotations; {@code DIAGNOSTIC} adds a ranked
     * set of inferred blocker cards. Signal-sparse {@code DIAGNOSTIC} calls
     * fall back to {@code POOL}.
     */
    public PromptResult buildPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                    List<TestChromosome> currentPopulation,
                                    int totalGoals, int coveredGoalCount,
                                    Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        return buildPrompt(uncoveredGoals, currentPopulation, totalGoals, coveredGoalCount,
                bestFitnessPerGoal, false);
    }

    public PromptResult buildPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                    List<TestChromosome> currentPopulation,
                                    int totalGoals, int coveredGoalCount,
                                    Map<TestFitnessFunction, Double> bestFitnessPerGoal,
                                    boolean suppressInFlightRepeats) {
        Properties.LlmStagnationPromptMode requestedMode = Properties.LLM_STAGNATION_PROMPT;
        if (requestedMode == Properties.LlmStagnationPromptMode.DIAGNOSTIC) {
            PromptResult diagnostic = buildDiagnosticPrompt(uncoveredGoals, currentPopulation,
                    totalGoals, coveredGoalCount, bestFitnessPerGoal, suppressInFlightRepeats);
            if (diagnostic != null) {
                int selectedCards = diagnostic.getDiagnosticCardTypes() == null
                        ? 0 : diagnostic.getDiagnosticCardTypes().size();
                logPromptSelection(requestedMode, Properties.LlmStagnationPromptMode.DIAGNOSTIC,
                        selectedCards, "none");
                return diagnostic;
            }
            logger.debug("Diagnostic prompt unavailable (no usable problem cards); "
                    + "falling back to pool prompt for this call.");
            logPromptSelection(requestedMode, Properties.LlmStagnationPromptMode.POOL,
                    0, "no_usable_problem_cards");
        } else {
            logPromptSelection(requestedMode, Properties.LlmStagnationPromptMode.POOL,
                    0, "none");
        }
        return buildPoolPrompt(uncoveredGoals, currentPopulation,
                totalGoals, coveredGoalCount, bestFitnessPerGoal);
    }

    private PromptResult buildDiagnosticPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                               List<TestChromosome> currentPopulation,
                                               int totalGoals, int coveredGoalCount,
                                               Map<TestFitnessFunction, Double> bestFitnessPerGoal,
                                               boolean suppressInFlightRepeats) {
        Set<String> goalMethods = collectGoalMethodKeys(uncoveredGoals);
        exceptionBarrierTracker.observe(currentPopulation, goalMethods);
        Map<String, ExceptionBarrierTracker.AggregatedStats> historicalStats =
                exceptionBarrierTracker.snapshot(goalMethods);
        ProblemCardExtractor.ExtractionResult extraction = problemCardExtractor.extractWithTelemetry(
                uncoveredGoals, currentPopulation, Integer.MAX_VALUE, historicalStats);
        List<ProblemCard> extractedCards = extraction.getCards();
        LlmStatistics.recordDiagnosticExtractorCandidates(extraction.getCandidateCounts());
        LlmStatistics.recordDiagnosticExtractorRejects(extraction.getRejectCounts());
        if (extractedCards.isEmpty()) {
            logDiagnosticExtractionTelemetry(extraction.getRejectCounts(), extraction.getCandidateCounts());
            return null;
        }
        DiagnosticCardSelector.SelectionResult selection = diagnosticCardSelector.select(
                extractedCards, DIAGNOSTIC_PROBLEM_CARDS_MAX,
                repeatedInjectionMemory, suppressInFlightRepeats);
        logDiagnosticCardSelection(extractedCards, selection,
                extraction.getRejectCounts(), extraction.getCandidateCounts());
        List<ProblemCard> cards = selection.getSelectedCards();
        if (cards.isEmpty()) {
            return null;
        }
        cards = attachClosestBranchExamples(cards, currentPopulation, bestFitnessPerGoal);
        LlmStatistics.recordDiagnosticCardsExtracted(extractedCards);
        LlmStatistics.recordDiagnosticCardsSelected(cards);
        LlmStatistics.recordDiagnosticCardsDiscarded(selection.getDiscardedCards());
        List<TestChromosome> popCandidates = currentPopulation != null
                ? currentPopulation : Collections.emptyList();
        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        String goalsSection = goalFormatter.format(uncoveredGoals, bestFitnessPerGoal);

        List<TestCase> existingTests = selectExistingTestsForPoolPrompt(
                currentPopulation, uncoveredGoals, cards);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(
                        uncoveredGoals, new ArrayList<>(popCandidates)))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction(buildDiagnosticInstruction(totalGoals, coveredGoalCount, cards.size()))
                .withInstruction("Priority problem cards:\n"
                        + ProblemCardFormatter.format(cards, existingTests))
                .withInstruction("Uncovered goals:\n" + goalsSection);

        if (!existingTests.isEmpty()) {
            builder.withExistingTests(existingTests);
        }
        PromptResult prompt = builder.buildWithMetadata();
        return new PromptResult.Builder()
                .messages(prompt.getMessages())
                .sutContextMode(prompt.getSutContextMode())
                .contextUnavailable(prompt.isContextUnavailable())
                .contextTruncated(prompt.isContextTruncated())
                .contextCommentsStripped(prompt.isContextCommentsStripped())
                .contextSelectivelyTruncated(prompt.isContextSelectivelyTruncated())
                .clusterSummaryTruncated(prompt.isClusterSummaryTruncated())
                .clusterSummaryChars(prompt.getClusterSummaryChars())
                .dependencySummaryMetadata(prompt.getDependencySummaryMetadata())
                .diagnosticCardTypes(extractCardTypes(cards))
                .repeatedInjectionTargets(extractRepeatedTargets(cards))
                .build();
    }

    private List<ProblemCardType> extractCardTypes(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProblemCardType> types = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            if (card != null && card.getType() != null) {
                types.add(card.getType());
            }
        }
        return types;
    }

    private List<RepeatedInjectionTarget> extractRepeatedTargets(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        List<RepeatedInjectionTarget> targets = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            if (card != null) {
                targets.add(card.toRepeatedInjectionTarget());
            }
        }
        return targets;
    }

    public RepeatedInjectionMemory.PromptAttemptRegistration registerRepeatedAttempt(PromptResult prompt,
                                                                                     boolean asyncInFlight) {
        if (repeatedInjectionMemory == null || prompt == null
                || prompt.getRepeatedInjectionTargets() == null
                || prompt.getRepeatedInjectionTargets().isEmpty()) {
            return RepeatedInjectionMemory.PromptAttemptRegistration.empty();
        }
        return repeatedInjectionMemory.registerAttempt(prompt.getRepeatedInjectionTargets(), asyncInFlight);
    }

    public void recordRepeatedAttemptOutcome(String attemptId, int gainedGoals) {
        if (repeatedInjectionMemory == null || attemptId == null || attemptId.trim().isEmpty()) {
            return;
        }
        repeatedInjectionMemory.recordAttemptOutcome(attemptId, gainedGoals);
    }

    /**
     * Release an attempt that never completed (timeout, cancellation, interrupt) so
     * the repeated-injection cooldown does not treat it as a "no gain" outcome.
     */
    public void releaseUndeliveredRepeatedAttempt(String attemptId) {
        if (repeatedInjectionMemory == null || attemptId == null || attemptId.trim().isEmpty()) {
            return;
        }
        repeatedInjectionMemory.releaseUndeliveredAttempt(attemptId);
    }

    private Set<String> collectGoalMethodKeys(Collection<TestFitnessFunction> uncoveredGoals) {
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (TestFitnessFunction goal : uncoveredGoals) {
            if (goal == null) {
                continue;
            }
            String key = goalDescriptionMapper.describeMethodOperation(goal).getExecutionKey();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private PromptResult buildPoolPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                         List<TestChromosome> currentPopulation,
                                         int totalGoals, int coveredGoalCount,
                                         Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        List<TestChromosome> popCandidates = currentPopulation != null
                ? currentPopulation : Collections.emptyList();

        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        String goalsSection = goalFormatter.format(uncoveredGoals, bestFitnessPerGoal);

        String instruction = buildPoolInstruction(totalGoals, coveredGoalCount);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(
                        uncoveredGoals, new ArrayList<>(popCandidates)))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction(instruction);

        // Add pre-formatted goals (bypasses PromptBuilder.withUncoveredGoals to
        // include fitness annotations)
        builder.withInstruction("Uncovered goals:\n" + goalsSection);

        List<TestCase> existingTests = selectExistingTestsForPoolPrompt(currentPopulation, uncoveredGoals);
        if (!existingTests.isEmpty()) {
            builder.withExistingTests(existingTests);
        }
        return builder.buildWithMetadata();
    }

    private List<TestCase> selectExistingTestsForPoolPrompt(
            List<TestChromosome> currentPopulation,
            Collection<TestFitnessFunction> uncoveredGoals) {
        return selectExistingTestsForPoolPrompt(
                currentPopulation, uncoveredGoals, Collections.<ProblemCard>emptyList());
    }

    private List<TestCase> selectExistingTestsForPoolPrompt(
            List<TestChromosome> currentPopulation,
            Collection<TestFitnessFunction> uncoveredGoals,
            List<ProblemCard> cards) {
        if (currentPopulation == null || currentPopulation.isEmpty()) {
            return collectPinnedTests(cards, EXISTING_TESTS_CONTEXT_MAX);
        }
        int candidateCount = Math.max(
                EXISTING_TESTS_CONTEXT_MAX,
                EXISTING_TESTS_CONTEXT_MAX * EXISTING_TESTS_RELEVANCE_CANDIDATE_MULTIPLIER);
        List<TestChromosome> ranked = TestRelevanceRanker.rankByRelevance(
                currentPopulation, uncoveredGoals, candidateCount);
        List<TestChromosome> deduped = deduplicateByRenderedCode(ranked);
        List<TestChromosome> selected = shouldDiversifyExistingTestsBySpecies()
                ? diversifyAcrossSpecies(deduped, EXISTING_TESTS_CONTEXT_MAX)
                : deduped.subList(0, Math.min(EXISTING_TESTS_CONTEXT_MAX, deduped.size()));
        List<TestCase> existingTests = collectPinnedTests(cards, EXISTING_TESTS_CONTEXT_MAX);
        Set<String> seen = new LinkedHashSet<>();
        for (TestCase pinned : existingTests) {
            seen.add(canonicalTestKey(pinned));
        }
        if (existingTests.size() >= EXISTING_TESTS_CONTEXT_MAX) {
            return existingTests;
        }
        for (TestChromosome selectedChromosome : selected) {
            if (selectedChromosome != null && selectedChromosome.getTestCase() != null) {
                TestCase test = selectedChromosome.getTestCase();
                if (seen.add(canonicalTestKey(test))) {
                    existingTests.add(test);
                    if (existingTests.size() >= EXISTING_TESTS_CONTEXT_MAX) {
                        break;
                    }
                }
            }
        }
        return existingTests;
    }

    private List<TestCase> collectPinnedTests(List<ProblemCard> cards, int maxCount) {
        if (cards == null || cards.isEmpty() || maxCount <= 0) {
            return new ArrayList<>();
        }
        List<TestCase> pinned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProblemCard card : cards) {
            if (card == null) {
                continue;
            }
            for (ProblemCard.ConcreteExample example : card.getConcreteExamples()) {
                TestCase test = example.getTestCase();
                if (test != null && seen.add(canonicalTestKey(test))) {
                    pinned.add(test);
                    if (pinned.size() >= maxCount) {
                        return pinned;
                    }
                }
            }
        }
        return pinned;
    }

    private List<ProblemCard> attachClosestBranchExamples(
            List<ProblemCard> cards,
            List<TestChromosome> population,
            Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (cards == null || cards.isEmpty() || population == null || population.isEmpty()
                || bestFitnessPerGoal == null || bestFitnessPerGoal.isEmpty()) {
            return cards;
        }
        List<ProblemCard> enriched = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            if (card == null || card.getType() != ProblemCardType.BRANCH_POLARITY_GAP) {
                enriched.add(card);
                continue;
            }
            TestFitnessFunction closestGoal = null;
            double closestDistance = Double.POSITIVE_INFINITY;
            for (TestFitnessFunction goal : card.getRelatedGoals()) {
                Double distance = bestFitnessPerGoal.get(goal);
                if (distance != null && Double.isFinite(distance) && distance > 0.0
                        && distance < closestDistance) {
                    closestGoal = goal;
                    closestDistance = distance;
                }
            }
            TestCase closestTest = findClosestTest(population, closestGoal, closestDistance);
            if (closestTest == null) {
                enriched.add(card);
                continue;
            }
            String description = String.format(Locale.ROOT,
                    "Closest near-miss for %s has branch distance %.4f; flip the condition",
                    goalDescriptionMapper.describe(closestGoal), closestDistance);
            enriched.add(card.withConcreteExample(closestTest, description));
        }
        return enriched;
    }

    private TestCase findClosestTest(List<TestChromosome> population,
                                     TestFitnessFunction goal,
                                     double expectedDistance) {
        if (goal == null) {
            return null;
        }
        TestCase closest = null;
        double smallestDelta = Double.POSITIVE_INFINITY;
        for (TestChromosome chromosome : population) {
            if (chromosome == null || chromosome.getTestCase() == null) {
                continue;
            }
            Double distance = chromosome.getFitnessValues().get(goal);
            if (distance == null || !Double.isFinite(distance)) {
                continue;
            }
            double delta = Math.abs(distance - expectedDistance);
            if (delta < smallestDelta) {
                smallestDelta = delta;
                closest = chromosome.getTestCase();
            }
        }
        return closest;
    }

    private boolean shouldDiversifyExistingTestsBySpecies() {
        return Properties.SPECIATION_ENABLED || Properties.SPECIES_TRACK_WHEN_SPECIATION_DISABLED;
    }

    private List<TestChromosome> deduplicateByRenderedCode(List<TestChromosome> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestChromosome> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TestChromosome chromosome : ranked) {
            if (chromosome == null || chromosome.getTestCase() == null) {
                continue;
            }
            String key = canonicalTestKey(chromosome.getTestCase());
            if (seen.add(key)) {
                unique.add(chromosome);
            }
        }
        return unique;
    }

    private List<TestChromosome> diversifyAcrossSpecies(List<TestChromosome> ranked, int maxCount) {
        if (ranked == null || ranked.isEmpty() || maxCount <= 0) {
            return Collections.emptyList();
        }
        if (ranked.size() <= maxCount) {
            return ranked;
        }
        Map<Integer, List<TestChromosome>> speciesMap =
                new DefaultSpeciesAssigner().groupBySpecies(ranked);
        if (speciesMap.size() <= 1) {
            return new ArrayList<>(ranked.subList(0, maxCount));
        }
        List<TestChromosome> diversified = new ArrayList<>(maxCount);
        Map<Integer, Integer> nextIndexBySpecies = new LinkedHashMap<>();
        for (Integer species : speciesMap.keySet()) {
            nextIndexBySpecies.put(species, 0);
        }
        while (diversified.size() < maxCount) {
            boolean addedThisRound = false;
            for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
                Integer species = entry.getKey();
                List<TestChromosome> members = entry.getValue();
                int next = nextIndexBySpecies.get(species);
                if (next >= members.size()) {
                    continue;
                }
                diversified.add(members.get(next));
                nextIndexBySpecies.put(species, next + 1);
                addedThisRound = true;
                if (diversified.size() >= maxCount) {
                    break;
                }
            }
            if (!addedThisRound) {
                break;
            }
        }
        if (diversified.size() < maxCount) {
            Set<TestChromosome> included = new HashSet<>(diversified);
            for (TestChromosome chromosome : ranked) {
                if (included.add(chromosome)) {
                    diversified.add(chromosome);
                    if (diversified.size() >= maxCount) {
                        break;
                    }
                }
            }
        }
        return diversified;
    }

    private String canonicalTestKey(TestCase testCase) {
        try {
            return testCase.toCode().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return "id_" + System.identityHashCode(testCase);
        }
    }

    private String buildPoolInstruction(int totalGoals, int coveredGoalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("The evolutionary search stagnated for at least ")
                .append(thresholdSeconds)
                .append(" seconds with no fitness improvement.");
        if (totalGoals > 0) {
            double pct = 100.0 * coveredGoalCount / totalGoals;
            sb.append(String.format(Locale.ROOT, " Current coverage: %d/%d goals (%.1f%%).",
                    coveredGoalCount, totalGoals, pct));
        }
        sb.append(" Goals marked [almost covered] were close to being reached — focus on those first.");
        sb.append(" Generate ").append(testsPerRequest)
                .append(" JUnit tests targeting the uncovered goals.")
                .append(LlmAssertionPolicyResolver.instructionSuffix(false));
        return sb.toString();
    }

    private String buildDiagnosticInstruction(int totalGoals, int coveredGoalCount,
                                              int cardCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("The evolutionary search stagnated for at least ")
                .append(thresholdSeconds)
                .append(" seconds with no fitness improvement.");
        if (totalGoals > 0) {
            double pct = 100.0 * coveredGoalCount / totalGoals;
            sb.append(String.format(Locale.ROOT, " Current coverage: %d/%d goals (%.1f%%).",
                    coveredGoalCount, totalGoals, pct));
        }
        sb.append(" Below is a ranked list of ")
                .append(cardCount)
                .append(" diagnostic problem cards inferred from the current population.");
        sb.append(" Generate focused, executable JUnit tests that address as many of these cards as feasible "
                + "while targeting uncovered goals. Prefer compact, clean tests, and if not all cards can be "
                + "addressed cleanly, prioritize the highest-ranked ones. A single test may address multiple cards.");
        sb.append(" Generate the smallest useful exploration matrix needed to cover distinct regimes. Prefer a few "
                + "short variants that each change exactly one axis. Stop when additional tests would only duplicate "
                + "an already-covered regime.");
        sb.append(" These injected tests are for coverage guidance during search, so focus on executable call "
                + "sequences, branch-driving inputs, and reaching the target methods; assertions are unnecessary.")
                .append(LlmAssertionPolicyResolver.instructionSuffix(false));
        return sb.toString();
    }

    private void logDiagnosticCardSelection(List<ProblemCard> extractedCards,
                                            DiagnosticCardSelector.SelectionResult selection,
                                            Map<ExtractorRejectReason, Integer> rejectCounts,
                                            Map<ExtractorCandidateMetric, Integer> candidateCounts) {
        List<ProblemCard> selectedCards = selection == null
                ? Collections.<ProblemCard>emptyList()
                : selection.getSelectedCards();
        List<DiagnosticCardSelector.DiscardedCard> discardedCards = selection == null
                ? Collections.<DiagnosticCardSelector.DiscardedCard>emptyList()
                : selection.getDiscardedCards();
        int selectedCount = selectedCards == null ? 0 : selectedCards.size();
        logDiagnosticTrace("diagnostic_problem_cards",
                "extracted_count={} selected_count={} extracted_by_type={} selected_by_type={} "
                        + "extracted_top={} selected_top={} extractor_candidates={} extractor_rejects={} "
                        + "discarded_by_reason={} discarded_top={}",
                extractedCards == null ? 0 : extractedCards.size(),
                selectedCount,
                summarizeCardTypes(extractedCards),
                summarizeCardTypes(selectedCards),
                summarizeCards(extractedCards),
                summarizeCards(selectedCards),
                candidateCounts == null || candidateCounts.isEmpty() ? "{}" : candidateCounts.toString(),
                rejectCounts == null || rejectCounts.isEmpty() ? "{}" : rejectCounts.toString(),
                selection == null ? "{}" : selection.summarizeDiscardReasons(),
                summarizeDiscardedCards(discardedCards));
    }

    private void logDiagnosticExtractionTelemetry(Map<ExtractorRejectReason, Integer> rejectCounts,
                                                  Map<ExtractorCandidateMetric, Integer> candidateCounts) {
        if ((rejectCounts == null || rejectCounts.isEmpty())
                && (candidateCounts == null || candidateCounts.isEmpty())) {
            return;
        }
        logDiagnosticTrace("diagnostic_problem_cards",
                "extracted_count=0 selected_count=0 extracted_by_type={} selected_by_type={} "
                        + "extracted_top=[] selected_top=[] extractor_candidates={} extractor_rejects={} "
                        + "discarded_by_reason={} discarded_top=[]",
                "{}",
                "{}",
                candidateCounts == null || candidateCounts.isEmpty() ? "{}" : candidateCounts.toString(),
                rejectCounts == null || rejectCounts.isEmpty() ? "{}" : rejectCounts.toString(),
                "{}");
    }

    private String summarizeCardTypes(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "{}";
        }
        java.util.EnumMap<ProblemCardType, Integer> counts = new java.util.EnumMap<>(ProblemCardType.class);
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            counts.put(card.getType(), counts.getOrDefault(card.getType(), 0) + 1);
        }
        return counts.toString();
    }

    private String summarizeCards(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            ProblemCard card = cards.get(i);
            if (card == null) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(card.getType())
                    .append("@")
                    .append(String.format(Locale.ROOT, "%.3f", card.getPriority()));
        }
        sb.append("]");
        return sb.toString();
    }

    private String summarizeDiscardedCards(List<DiagnosticCardSelector.DiscardedCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int emitted = 0;
        for (DiagnosticCardSelector.DiscardedCard discarded : cards) {
            if (discarded == null || discarded.getCard() == null) {
                continue;
            }
            if (emitted++ >= 3) {
                break;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(discarded.getCard().getType())
                    .append("@")
                    .append(String.format(Locale.ROOT, "%.3f", discarded.getCard().getPriority()))
                    .append(":")
                    .append(discarded.getReason());
        }
        sb.append("]");
        return sb.toString();
    }

    private void logPromptSelection(Properties.LlmStagnationPromptMode requestedMode,
                                    Properties.LlmStagnationPromptMode selectedMode,
                                    int selectedCards,
                                    String fallbackReason) {
        logDiagnosticTrace("stagnation_prompt",
                "requested_mode={} selected_mode={} selected_cards={} fallback_reason={}",
                requestedMode, selectedMode, Math.max(0, selectedCards),
                fallbackReason == null ? "none" : fallbackReason);
    }

    private void logDiagnosticTrace(String prefix, String message, Object... args) {
        if (DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger)) {
            Object[] prefixed = new Object[args.length + 1];
            prefixed[0] = DiagnosticTraceSupport.currentTargetClass();
            System.arraycopy(args, 0, prefixed, 1, args.length);
            logger.warn(prefix + " target={} " + message, prefixed);
            return;
        }
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(prefix + " " + message, args);
    }

}
