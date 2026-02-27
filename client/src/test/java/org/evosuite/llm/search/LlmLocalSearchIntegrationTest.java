package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.localsearch.AVMTestCaseLocalSearch;
import org.evosuite.testcase.localsearch.DSETestCaseLocalSearch;
import org.evosuite.testcase.localsearch.TestCaseLocalSearch;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.localsearch.TestSuiteLocalSearch;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LLM local-search mode controls, goal-aware prompting,
 * related-goal filtering, dispatch behavior, and persistence semantics.
 */
class LlmLocalSearchIntegrationTest {

    // Saved property state
    private boolean prevLlmLocalSearch;
    private double prevLlmLocalSearchProbability;
    private Properties.LlmLocalSearchMode prevLlmLocalSearchMode;
    private boolean prevLlmLocalSearchRelatedGoalsOnly;
    private int prevLlmLocalSearchRelatedGoalsMax;
    private double prevDseProbability;
    private long prevSeed;
    private int prevLocalSearchRate;
    private double prevLocalSearchProbability;
    private boolean prevLocalSearchExpandTests;
    private boolean prevLocalSearchEnsureDoubleExecution;
    private boolean prevLocalSearchRestoreCoverage;

    @BeforeEach
    void saveProperties() {
        prevLlmLocalSearch = Properties.LLM_LOCAL_SEARCH;
        prevLlmLocalSearchProbability = Properties.LLM_LOCAL_SEARCH_PROBABILITY;
        prevLlmLocalSearchMode = Properties.LLM_LOCAL_SEARCH_MODE;
        prevLlmLocalSearchRelatedGoalsOnly = Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY;
        prevLlmLocalSearchRelatedGoalsMax = Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX;
        prevDseProbability = Properties.DSE_PROBABILITY;
        prevSeed = Randomness.getSeed();
        prevLocalSearchRate = Properties.LOCAL_SEARCH_RATE;
        prevLocalSearchProbability = Properties.LOCAL_SEARCH_PROBABILITY;
        prevLocalSearchExpandTests = Properties.LOCAL_SEARCH_EXPAND_TESTS;
        prevLocalSearchEnsureDoubleExecution = Properties.LOCAL_SEARCH_ENSURE_DOUBLE_EXECUTION;
        prevLocalSearchRestoreCoverage = Properties.LOCAL_SEARCH_RESTORE_COVERAGE;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_LOCAL_SEARCH = prevLlmLocalSearch;
        Properties.LLM_LOCAL_SEARCH_PROBABILITY = prevLlmLocalSearchProbability;
        Properties.LLM_LOCAL_SEARCH_MODE = prevLlmLocalSearchMode;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = prevLlmLocalSearchRelatedGoalsOnly;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = prevLlmLocalSearchRelatedGoalsMax;
        Properties.DSE_PROBABILITY = prevDseProbability;
        Randomness.setSeed(prevSeed);
        Properties.LOCAL_SEARCH_RATE = prevLocalSearchRate;
        Properties.LOCAL_SEARCH_PROBABILITY = prevLocalSearchProbability;
        Properties.LOCAL_SEARCH_EXPAND_TESTS = prevLocalSearchExpandTests;
        Properties.LOCAL_SEARCH_ENSURE_DOUBLE_EXECUTION = prevLocalSearchEnsureDoubleExecution;
        Properties.LOCAL_SEARCH_RESTORE_COVERAGE = prevLocalSearchRestoreCoverage;
        LlmService.resetInstanceForTesting();
    }

    // -------------------------------------------------------------------------
    // 1. Property defaults
    // -------------------------------------------------------------------------

    @Test
    void defaultModeIsHybrid() {
        assertEquals(Properties.LlmLocalSearchMode.HYBRID, Properties.LLM_LOCAL_SEARCH_MODE);
    }

    @Test
    void defaultRelatedGoalsOnlyIsTrue() {
        assertTrue(Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY);
    }

    @Test
    void defaultRelatedGoalsMaxIsTwenty() {
        assertEquals(20, Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX);
    }

    // -------------------------------------------------------------------------
    // 2. LLM_ONLY dispatch via real selectTestCaseLocalSearch()
    // -------------------------------------------------------------------------

    @Test
    void llmOnlyModeAlwaysSelectsLlmLocalSearch() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.LLM_ONLY;
        Properties.DSE_PROBABILITY = 1.0; // would normally trigger DSE

        // Run many iterations to confirm LLM is always selected, never DSE/AVM
        for (int i = 0; i < 50; i++) {
            TestCaseLocalSearch<TestChromosome> ls =
                    TestCaseLocalSearch.selectTestCaseLocalSearch();
            assertInstanceOf(LlmLocalSearch.class, ls,
                    "LLM_ONLY mode must always select LlmLocalSearch, iteration " + i);
        }
    }

    @Test
    void hybridModeSelectsAvmWhenLlmAndDseProbabilityZero() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.HYBRID;
        Properties.LLM_LOCAL_SEARCH_PROBABILITY = 0.0;
        Properties.DSE_PROBABILITY = 0.0;

        for (int i = 0; i < 50; i++) {
            TestCaseLocalSearch<TestChromosome> ls =
                    TestCaseLocalSearch.selectTestCaseLocalSearch();
            assertInstanceOf(AVMTestCaseLocalSearch.class, ls,
                    "HYBRID with LLM prob 0 and DSE prob 0 should select AVM, iteration " + i);
        }
    }

    @Test
    void hybridModeCanSelectLlmOrAvmDse() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.HYBRID;
        Properties.LLM_LOCAL_SEARCH_PROBABILITY = 0.5;
        Properties.DSE_PROBABILITY = 0.0;

        boolean sawLlm = false;
        boolean sawAvm = false;
        for (int i = 0; i < 200; i++) {
            TestCaseLocalSearch<TestChromosome> ls =
                    TestCaseLocalSearch.selectTestCaseLocalSearch();
            if (ls instanceof LlmLocalSearch) sawLlm = true;
            if (ls instanceof AVMTestCaseLocalSearch) sawAvm = true;
        }
        assertTrue(sawLlm, "HYBRID mode should sometimes select LLM");
        assertTrue(sawAvm, "HYBRID mode should sometimes select AVM");
    }

    @Test
    void llmDisabledNeverSelectsLlm() {
        Properties.LLM_LOCAL_SEARCH = false;
        Properties.DSE_PROBABILITY = 0.0;

        for (int i = 0; i < 50; i++) {
            TestCaseLocalSearch<TestChromosome> ls =
                    TestCaseLocalSearch.selectTestCaseLocalSearch();
            assertFalse(ls instanceof LlmLocalSearch,
                    "LLM disabled should never select LlmLocalSearch");
        }
    }

    @Test
    void hybridModeCanSelectDse() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.HYBRID;
        Properties.LLM_LOCAL_SEARCH_PROBABILITY = 0.0;
        Properties.DSE_PROBABILITY = 1.0;

        for (int i = 0; i < 50; i++) {
            TestCaseLocalSearch<TestChromosome> ls =
                    TestCaseLocalSearch.selectTestCaseLocalSearch();
            assertInstanceOf(DSETestCaseLocalSearch.class, ls,
                    "HYBRID with LLM prob 0 and DSE prob 1 should select DSE, iteration " + i);
        }
    }

    // -------------------------------------------------------------------------
    // 3. Goal-aware prompting: selectGoalsForPrompt
    // -------------------------------------------------------------------------

    @Test
    void selectGoalsReturnsAllGoalsWhenRelatedFilteringDisabled() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = false;

        LlmLocalSearch ls = createLlmLocalSearch();
        Set<TestFitnessFunction> uncovered = createMockGoals(10);

        TestChromosome tc = makeChromosome();
        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc, uncovered);

        assertEquals(10, goals.size(), "All uncovered goals should be returned when filtering is off");
    }

    @Test
    void selectGoalsReturnsTopKRelatedGoalsWhenFitnessAvailable() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = 3;

        LlmLocalSearch ls = createLlmLocalSearch();

        List<TestFitnessFunction> goalList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            goalList.add(createGoalWithName("goal-" + i));
        }
        Set<TestFitnessFunction> uncovered = new LinkedHashSet<>(goalList);

        TestChromosome tc = makeChromosome();
        for (int i = 0; i < goalList.size(); i++) {
            tc.setFitness(goalList.get(i), (double) i);
        }

        Collection<TestFitnessFunction> selected = ls.selectGoalsForPrompt(tc, uncovered);

        assertEquals(3, selected.size(), "Should return top-K related goals");
        List<TestFitnessFunction> selectedList = new ArrayList<>(selected);
        assertTrue(selectedList.contains(goalList.get(0)), "Closest goal should be included");
        assertTrue(selectedList.contains(goalList.get(1)), "Second closest should be included");
        assertTrue(selectedList.contains(goalList.get(2)), "Third closest should be included");
    }

    @Test
    void selectGoalsFallsBackToAllWhenNoFitnessValues() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = 3;

        LlmLocalSearch ls = createLlmLocalSearch();
        Set<TestFitnessFunction> uncovered = createMockGoals(10);

        TestChromosome tc = makeChromosome();

        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc, uncovered);

        assertEquals(10, goals.size(),
                "Should fall back to all uncovered goals when fitness values unavailable");
    }

    @Test
    void selectGoalsFallsBackWhenFitnessMapHasNoUncoveredEntries() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = 3;

        LlmLocalSearch ls = createLlmLocalSearch();
        Set<TestFitnessFunction> uncovered = createMockGoals(10);

        TestChromosome tc = makeChromosome();
        // Populate fitness map with goals that are NOT in the uncovered set
        TestFitnessFunction otherGoal = createGoalWithName("covered-goal");
        tc.setFitness(otherGoal, 0.5);

        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc, uncovered);

        assertEquals(10, goals.size(),
                "Should fall back to all uncovered goals when fitness map has no entries for them");
    }

    @Test
    void selectGoalsReturnsEmptyWhenNoUncoveredGoals() {
        LlmLocalSearch ls = createLlmLocalSearch();

        TestChromosome tc = makeChromosome();
        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc, Collections.emptySet());

        assertTrue(goals.isEmpty(), "Should return empty when no uncovered goals");
    }

    @Test
    void selectGoalsRespectsMaxLimit() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = 5;

        LlmLocalSearch ls = createLlmLocalSearch();

        List<TestFitnessFunction> goalList = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            goalList.add(createGoalWithName("goal-" + i));
        }

        TestChromosome tc = makeChromosome();
        for (int i = 0; i < goalList.size(); i++) {
            tc.setFitness(goalList.get(i), (double) i);
        }

        Collection<TestFitnessFunction> selected =
                ls.selectGoalsForPrompt(tc, new LinkedHashSet<>(goalList));

        assertEquals(5, selected.size(), "Should respect the max limit for related goals");
    }

    @Test
    void selectGoalsReturnsAllWhenFewerThanMax() {
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX = 50;

        LlmLocalSearch ls = createLlmLocalSearch();

        List<TestFitnessFunction> goalList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            goalList.add(createGoalWithName("goal-" + i));
        }

        TestChromosome tc = makeChromosome();
        for (int i = 0; i < goalList.size(); i++) {
            tc.setFitness(goalList.get(i), (double) i);
        }

        Collection<TestFitnessFunction> selected =
                ls.selectGoalsForPrompt(tc, new LinkedHashSet<>(goalList));

        assertEquals(5, selected.size(),
                "Should return all goals when fewer than max");
    }

    @Test
    void selectGoalsHandlesNullCollection() {
        LlmLocalSearch ls = createLlmLocalSearch();
        TestChromosome tc = makeChromosome();

        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc, null);

        assertTrue(goals.isEmpty(), "Should return empty for null input");
    }

    // -------------------------------------------------------------------------
    // 4. Real doSearch() path tests
    // -------------------------------------------------------------------------

    /**
     * Exercises the real {@link LlmLocalSearch#doSearch} method with a service
     * that has no model (unavailable). Verifies the guard returns false
     * without invoking the LLM.
     */
    @Test
    void doSearchReturnsFalseWhenServiceUnavailable() {
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.fromProperties();
        LlmConfiguration config = LlmConfiguration.fromProperties();
        // null model → isAvailable() == false
        LlmService unavailableService = new LlmService(null, budget, config,
                new LlmStatistics(), new LlmTraceRecorder(config));
        LlmLocalSearch ls = new LlmLocalSearch(unavailableService);

        TestChromosome tc = makeChromosome();
        // Minimal objective that is non-null (doSearch only null-checks it)
        assertFalse(ls.doSearch(tc, makeTrivialObjective()),
                "doSearch must return false when LLM service is unavailable");
    }

    /**
     * Exercises the real {@link LlmLocalSearch#doSearch} null-guard paths.
     */
    @Test
    void doSearchReturnsFalseForNullInputs() {
        LlmLocalSearch ls = createLlmLocalSearch();
        assertFalse(ls.doSearch(null, makeTrivialObjective()),
                "doSearch must return false for null test");
        assertFalse(ls.doSearch(makeChromosome(), null),
                "doSearch must return false for null objective");
    }

    /**
     * Exercises the real no-argument {@link LlmLocalSearch#selectGoalsForPrompt}
     * which queries the global Archive singleton. When the Archive has no
     * uncovered targets, the method must return a non-null, graceful result.
     */
    @Test
    void selectGoalsFromArchiveGracefulWhenNoTargets() {
        LlmLocalSearch ls = createLlmLocalSearch();
        TestChromosome tc = makeChromosome();

        // Call the no-argument variant that reads from the Archive singleton.
        // In a unit-test context the archive may have zero uncovered targets;
        // this verifies the production path does not throw.
        Collection<TestFitnessFunction> goals = ls.selectGoalsForPrompt(tc);
        assertNotNull(goals, "selectGoalsForPrompt (archive path) must never return null");
    }

    /**
     * Exercises real {@link TestSuiteLocalSearch#doSearch} in LLM_ONLY mode.
     * The suite-level dispatch should route to LLM (not AVM/DSE). With the
     * LLM service set to unavailable, the LLM path returns false for each
     * test, and the suite is restored to its original state.
     *
     * <p>This replaces the previous boolean-expression-only test by actually
     * executing the production dispatch code path.
     */
    @Test
    void testSuiteLocalSearchInLlmOnlyModeExecutesLlmPath() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.LLM_ONLY;
        Properties.DSE_PROBABILITY = 1.0; // would trigger DSE if LLM_ONLY weren't blocking it
        Properties.LOCAL_SEARCH_EXPAND_TESTS = false;
        Properties.LOCAL_SEARCH_ENSURE_DOUBLE_EXECUTION = false;
        Properties.LOCAL_SEARCH_RESTORE_COVERAGE = false;

        // Install an unavailable LLM service so the LLM path returns false
        // (but we still exercise the real dispatch routing)
        LlmService unavailable = new LlmService(null,
                LlmBudgetCoordinator.fromProperties(),
                LlmConfiguration.fromProperties(),
                new LlmStatistics(),
                new LlmTraceRecorder(LlmConfiguration.fromProperties()));
        LlmService.setInstanceForTesting(unavailable);

        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome());

        TestSuiteLocalSearch tsls = TestSuiteLocalSearch.selectTestSuiteLocalSearch();
        LocalSearchObjective<TestSuiteChromosome> objective = makeSuiteObjective();

        // Execute real production path — should not throw
        boolean improved = tsls.doSearch(suite, objective);
        assertFalse(improved, "Suite should not improve when LLM service is unavailable");

        // Suite should be unchanged (original tests restored because no improvement)
        assertEquals(1, suite.getTestChromosomes().size(),
                "Suite should retain its original test count after unsuccessful LS");
    }

    // -------------------------------------------------------------------------
    // 5. MOSA LS re-evaluation gating tests
    // -------------------------------------------------------------------------

    /**
     * Verifies that when {@code LOCAL_SEARCH_RATE <= 0} (LS globally disabled),
     * {@link AbstractMOSA#applyLocalSearch(TestSuiteChromosome)} does not
     * trigger any re-evaluation via {@code calculateFitness}.
     */
    @Test
    void mosaSkipsReEvaluationWhenLocalSearchDisabled() {
        Properties.LOCAL_SEARCH_RATE = -1; // LS disabled

        int[] calcFitnessCount = {0};
        CountingMOSA harness = createCountingMosa(calcFitnessCount);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome());

        harness.applyLocalSearch(suite);
        assertEquals(0, calcFitnessCount[0],
                "calculateFitness should not be called when LS is disabled");
    }

    /**
     * Verifies that when LS is enabled and the probability gate passes,
     * {@link AbstractMOSA#applyLocalSearch(TestSuiteChromosome)} triggers
     * re-evaluation of suite tests via {@code calculateFitness}.
     */
    @Test
    void mosaReEvaluatesWhenLocalSearchRuns() {
        Properties.LOCAL_SEARCH_RATE = 1;      // every generation
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0; // always pass probability gate

        int[] calcFitnessCount = {0};
        CountingMOSA harness = createCountingMosa(calcFitnessCount);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome());

        harness.applyLocalSearch(suite);
        assertTrue(calcFitnessCount[0] > 0,
                "calculateFitness should be called when LS runs");
    }

    // -------------------------------------------------------------------------
    // 6. LlmLocalSearch prompt-content capturing test
    // -------------------------------------------------------------------------

    /**
     * Exercises the real {@link LlmLocalSearch#doSearch} method with a
     * capturing model and verifies that when uncovered goals are present,
     * the prompt sent to the LLM contains "Uncovered goals" text.
     */
    @Test
    void doSearchIncludesUncoveredGoalsInPrompt() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY = false;

        // Capture messages sent to the model
        AtomicReference<List<LlmMessage>> capturedMessages = new AtomicReference<>();
        LlmService.ChatLanguageModel capturingModel = (messages, feature) -> {
            capturedMessages.set(new ArrayList<>(messages));
            return LlmService.LlmResponse.fromText("// no valid test");
        };

        LlmBudgetCoordinator budget = new LlmBudgetCoordinator.Local(10);
        LlmConfiguration config = LlmConfiguration.fromProperties();
        LlmService service = new LlmService(capturingModel, budget, config,
                new LlmStatistics(), new LlmTraceRecorder(config));

        // Provide uncovered goals via the two-arg selectGoalsForPrompt
        Set<TestFitnessFunction> uncovered = createMockGoals(3);

        // Create LlmLocalSearch subclass that injects our goals
        LlmLocalSearch ls = new LlmLocalSearch(service) {
            @Override
            Collection<TestFitnessFunction> selectGoalsForPrompt(TestChromosome test) {
                return uncovered;
            }
        };

        TestChromosome tc = makeChromosome();
        // doSearch will build prompt, call the model, then fail to parse → returns false
        boolean result = ls.doSearch(tc, makeTrivialObjective());
        assertFalse(result, "doSearch should return false when LLM response has no valid test");

        // Verify the model was called and messages contain uncovered-goal context
        assertNotNull(capturedMessages.get(), "Model should have been called");
        String userMessage = capturedMessages.get().stream()
                .filter(m -> m.getRole() == LlmMessage.Role.USER)
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertTrue(userMessage.contains("Uncovered goals"),
                "User message should contain 'Uncovered goals' text; got: " + userMessage);
        assertTrue(userMessage.contains("goal-0"),
                "User message should contain goal descriptions; got: " + userMessage);
    }

    // -------------------------------------------------------------------------
    // 7. MOSA LS persistence: improved tests staged for next generation
    // -------------------------------------------------------------------------

    /**
     * Verifies that after {@link AbstractMOSA#applyLocalSearch} runs,
     * only LS-produced tests (delta: post-LS minus pre-LS suite tests)
     * are staged as pending and drained into the next evolve()'s union
     * via {@code collectExternalCandidates}. Pre-existing archive
     * snapshot tests are not staged.
     */
    @Test
    void mosaLsImprovedTestsAreStagedForNextGeneration() {
        Properties.LOCAL_SEARCH_RATE = 1;
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0;

        int[] calcFitnessCount = {0};
        LsPersistenceMOSA harness = new LsPersistenceMOSA(calcFitnessCount);
        new org.evosuite.ga.metaheuristics.mosa.MOSATestSuiteAdapter(harness);

        // Seed population with 2 existing tests
        harness.seedPopulation(makeChromosome(), makeChromosome());

        // Create suite with a pre-existing archive snapshot test
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome()); // pre-LS snapshot — should NOT be staged

        // Simulate LS producing 2 new tests (added to suite during adapter LS)
        TestChromosome lsTest1 = makeChromosome();
        TestChromosome lsTest2 = makeChromosome();
        harness.lsOutputTests = java.util.Arrays.asList(lsTest1, lsTest2);

        // Apply LS — only the delta (lsTest1, lsTest2) should be staged
        harness.applyLocalSearch(suite);
        assertTrue(calcFitnessCount[0] > 0,
                "LS re-evaluation should have called calculateFitness");

        // Now evolve — should drain only LS-produced tests into union
        harness.evolve();

        assertTrue(harness.lastUnion.contains(lsTest1),
                "LS-produced test 1 should be injected into next generation's union");
        assertTrue(harness.lastUnion.contains(lsTest2),
                "LS-produced test 2 should be injected into next generation's union");
        // Union = population (2) + LS delta (2) = 4
        assertEquals(4, harness.lastUnion.size(),
                "Union should contain population + LS-produced delta tests only");
    }

    /**
     * Verifies that unchanged archive snapshot tests are NOT staged,
     * and that LS-produced tests already in the population are NOT
     * duplicated (deduplication by identity).
     */
    @Test
    void mosaLsPersistenceSkipsDuplicatePopulationMembers() {
        Properties.LOCAL_SEARCH_RATE = 1;
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0;

        int[] calcFitnessCount = {0};
        LsPersistenceMOSA harness = new LsPersistenceMOSA(calcFitnessCount);
        new org.evosuite.ga.metaheuristics.mosa.MOSATestSuiteAdapter(harness);

        TestChromosome popTest1 = makeChromosome();
        TestChromosome popTest2 = makeChromosome();
        harness.seedPopulation(popTest1, popTest2);

        // Suite has a pre-existing archive snapshot test
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome()); // pre-LS snapshot — NOT staged

        // Simulate LS producing 2 tests: one that's already in population
        // (popTest1) and one that's truly new
        TestChromosome newTest = makeChromosome();
        harness.lsOutputTests = java.util.Arrays.asList(popTest1, newTest);

        harness.applyLocalSearch(suite);
        harness.evolve();

        // Union should include population (2) + only the new LS test (1)
        // popTest1 is deduped (already in population), snapshot is not staged
        assertEquals(3, harness.lastUnion.size(),
                "Only non-duplicate LS-produced tests should be injected");
        assertTrue(harness.lastUnion.contains(newTest),
                "New LS-produced test should be in union");
    }

    // -------------------------------------------------------------------------
    // 8. Suite-level LLM_ONLY: explicit AVM/DSE non-invocation proof
    // -------------------------------------------------------------------------

    /**
     * Uses a spy subclass of {@link TestSuiteLocalSearch} to verify that
     * in {@code LLM_ONLY} mode, the LLM path is invoked and the AVM/DSE
     * paths are never called. This provides an explicit invocation proof
     * beyond just checking the end-state.
     */
    @Test
    void testSuiteLocalSearchInLlmOnlyModeNeverInvokesAvmOrDse() {
        Properties.LLM_LOCAL_SEARCH = true;
        Properties.LLM_LOCAL_SEARCH_MODE = Properties.LlmLocalSearchMode.LLM_ONLY;
        Properties.DSE_PROBABILITY = 1.0; // would trigger DSE if not blocked
        Properties.LOCAL_SEARCH_EXPAND_TESTS = false;
        Properties.LOCAL_SEARCH_ENSURE_DOUBLE_EXECUTION = false;
        Properties.LOCAL_SEARCH_RESTORE_COVERAGE = false;

        // Install an unavailable LLM service so the LLM path returns false
        LlmService unavailable = new LlmService(null,
                LlmBudgetCoordinator.fromProperties(),
                LlmConfiguration.fromProperties(),
                new LlmStatistics(),
                new LlmTraceRecorder(LlmConfiguration.fromProperties()));
        LlmService.setInstanceForTesting(unavailable);

        // Initialise LS budget so the time-based check doesn't short-circuit
        org.evosuite.ga.localsearch.LocalSearchBudget.getInstance().localSearchStarted();

        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosome());

        SpyTestSuiteLocalSearch spy = new SpyTestSuiteLocalSearch();
        LocalSearchObjective<TestSuiteChromosome> objective = makeSuiteObjective();

        spy.doSearch(suite, objective);

        assertTrue(spy.llmCalled, "LLM path must be invoked in LLM_ONLY mode");
        assertFalse(spy.avmCalled, "AVM path must NOT be invoked in LLM_ONLY mode");
        assertFalse(spy.dseCalled, "DSE path must NOT be invoked in LLM_ONLY mode");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LlmLocalSearch createLlmLocalSearch() {
        LlmService.ChatLanguageModel noopModel = (messages, feature) -> {
            throw new IllegalStateException("should not be called");
        };
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.fromProperties();
        LlmConfiguration config = LlmConfiguration.fromProperties();
        LlmService service = new LlmService(noopModel, budget, config,
                new LlmStatistics(), new LlmTraceRecorder(config));
        return new LlmLocalSearch(service);
    }

    private TestChromosome makeChromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    /**
     * Creates a minimal {@link LocalSearchObjective} for doSearch() tests.
     * The objective is non-null but does not evaluate fitness (doSearch only
     * null-checks it before the LLM service guards).
     */
    private LocalSearchObjective<TestChromosome> makeTrivialObjective() {
        return new LocalSearchObjective<TestChromosome>() {
            @Override public boolean hasImproved(TestChromosome chromosome) { return false; }
            @Override public boolean hasNotWorsened(TestChromosome chromosome) { return true; }
            @Override public int hasChanged(TestChromosome chromosome) { return 0; }
            @Override public boolean isDone() { return false; }
            @Override public boolean isMaximizationObjective() { return false; }
            @Override public void addFitnessFunction(FitnessFunction<TestChromosome> fitness) { }
            @Override public List<FitnessFunction<TestChromosome>> getFitnessFunctions() {
                return Collections.emptyList();
            }
        };
    }

    /**
     * Creates a minimal suite-level {@link LocalSearchObjective} for
     * {@link TestSuiteLocalSearch#doSearch} tests.
     */
    private LocalSearchObjective<TestSuiteChromosome> makeSuiteObjective() {
        return new LocalSearchObjective<TestSuiteChromosome>() {
            @Override public boolean hasImproved(TestSuiteChromosome chromosome) { return false; }
            @Override public boolean hasNotWorsened(TestSuiteChromosome chromosome) { return true; }
            @Override public int hasChanged(TestSuiteChromosome chromosome) { return 0; }
            @Override public boolean isDone() { return false; }
            @Override public boolean isMaximizationObjective() { return false; }
            @Override public void addFitnessFunction(FitnessFunction<TestSuiteChromosome> fitness) { }
            @Override public List<FitnessFunction<TestSuiteChromosome>> getFitnessFunctions() {
                return Collections.emptyList();
            }
        };
    }

    /**
     * Minimal AbstractMOSA subclass that counts {@code calculateFitness}
     * calls and exposes {@code applyLocalSearch} as public for testing.
     */
    private static class CountingMOSA extends org.evosuite.ga.metaheuristics.mosa.AbstractMOSA {
        final int[] calcFitnessCount;

        CountingMOSA(int[] counter) {
            super(TestChromosome::new);
            this.calcFitnessCount = counter;
        }

        @Override protected void evolve() { }
        @Override public void generateSolution() { }
        @Override protected void calculateFitness(TestChromosome tc) {
            calcFitnessCount[0]++;
        }
        @Override public void applyLocalSearch(TestSuiteChromosome suite) {
            super.applyLocalSearch(suite);
        }
    }

    /**
     * Creates a {@link CountingMOSA} with its adapter wired up,
     * suitable for testing the conditional re-evaluation gating.
     */
    private CountingMOSA createCountingMosa(int[] calcFitnessCount) {
        CountingMOSA mosa = new CountingMOSA(calcFitnessCount);
        new org.evosuite.ga.metaheuristics.mosa.MOSATestSuiteAdapter(mosa);
        return mosa;
    }

    private Set<TestFitnessFunction> createMockGoals(int count) {
        Set<TestFitnessFunction> goals = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            goals.add(createGoalWithName("goal-" + i));
        }
        return goals;
    }

    private TestFitnessFunction createGoalWithName(String name) {
        return new TestFitnessFunction() {
            @Override public double getFitness(TestChromosome individual, org.evosuite.testcase.execution.ExecutionResult result) { return 0; }
            @Override public int compareTo(TestFitnessFunction other) { return name.compareTo(other.toString()); }
            @Override public boolean isMaximizationFunction() { return false; }
            @Override public String getTargetClass() { return "Test"; }
            @Override public String getTargetMethod() { return name; }
            @Override public int hashCode() { return name.hashCode(); }
            @Override public boolean equals(Object o) { return this == o; }
            @Override public String toString() { return name; }
        };
    }

    /**
     * AbstractMOSA subclass that exposes {@code applyLocalSearch} and
     * provides a minimal {@code evolve()} that captures the union for
     * verifying LS persistence. Overrides the no-arg {@code applyLocalSearch()}
     * to skip actual test-level LS on population members (which would
     * require execution results) while still triggering the scheduling flag.
     * <p>
     * To simulate LS producing new tests, set {@link #lsOutputTests} before
     * calling {@code applyLocalSearch(suite)}. The overridden no-arg
     * {@code applyLocalSearch()} will add these tests to the suite, mimicking
     * how real suite-level LS adds improved clones.
     */
    private static class LsPersistenceMOSA extends org.evosuite.ga.metaheuristics.mosa.AbstractMOSA {
        final int[] calcFitnessCount;
        List<TestChromosome> lastUnion;
        /** Tests to add to the suite during the adapter LS, simulating LS output. */
        List<TestChromosome> lsOutputTests = Collections.emptyList();
        private TestSuiteChromosome activeSuite;

        LsPersistenceMOSA(int[] counter) {
            super(TestChromosome::new);
            this.calcFitnessCount = counter;
        }

        void seedPopulation(TestChromosome... tests) {
            population.clear();
            Collections.addAll(population, tests);
        }

        @Override
        protected void evolve() {
            List<TestChromosome> union = new ArrayList<>(this.population);
            collectExternalCandidates(union);
            lastUnion = union;
            currentIteration++;
        }

        @Override public void generateSolution() { }

        /**
         * Skip actual LS on population members; trigger the scheduling flag
         * and inject simulated LS output tests into the active suite.
         */
        @Override
        protected void applyLocalSearch() {
            shouldApplyLocalSearch();
            if (activeSuite != null) {
                for (TestChromosome tc : lsOutputTests) {
                    activeSuite.addTest(tc);
                }
            }
        }

        @Override
        protected void calculateFitness(TestChromosome tc) {
            calcFitnessCount[0]++;
        }

        @Override
        public void applyLocalSearch(TestSuiteChromosome suite) {
            this.activeSuite = suite;
            try {
                super.applyLocalSearch(suite);
            } finally {
                this.activeSuite = null;
            }
        }
    }

    /**
     * Spy subclass of {@link TestSuiteLocalSearch} that records which
     * dispatch paths (AVM, DSE, LLM) are invoked during {@code doSearch}.
     */
    private static class SpyTestSuiteLocalSearch extends TestSuiteLocalSearch {
        boolean avmCalled = false;
        boolean dseCalled = false;
        boolean llmCalled = false;

        @Override
        protected boolean applyAVM(TestSuiteChromosome suite, int testIndex,
                                   TestChromosome test,
                                   org.evosuite.ga.localsearch.LocalSearchObjective<TestSuiteChromosome> objective) {
            avmCalled = true;
            return super.applyAVM(suite, testIndex, test, objective);
        }

        @Override
        protected boolean applyDSE(TestSuiteChromosome suite, int testIndex,
                                   TestChromosome test,
                                   org.evosuite.ga.localsearch.LocalSearchObjective<TestSuiteChromosome> objective) {
            dseCalled = true;
            return super.applyDSE(suite, testIndex, test, objective);
        }

        @Override
        protected boolean applyLLM(TestSuiteChromosome suite, int testIndex,
                                   TestChromosome test,
                                   org.evosuite.ga.localsearch.LocalSearchObjective<TestSuiteChromosome> objective) {
            llmCalled = true;
            return super.applyLLM(suite, testIndex, test, objective);
        }
    }
}
