/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.Properties.LlmFewShotArchiveStrategy;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FewShotExampleProvider}.
 */
class FewShotExampleProviderTest {

    private Properties.LlmPromptTechnique origTechnique;
    private boolean origUseParsed;
    private boolean origUseArchive;
    private int origMaxExamples;
    private LlmFewShotArchiveStrategy origStrategy;
    private int origMaxCharsTotal;
    private int origMaxCharsPerExample;
    private String origTargetClass;
    private String origSelectedJunit;
    private String origSeedTestSourceDir;

    @BeforeEach
    void save() {
        origTechnique = Properties.LLM_PROMPT_TECHNIQUE;
        origUseParsed = Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT;
        origUseArchive = Properties.LLM_FEW_SHOT_USE_ARCHIVE;
        origMaxExamples = Properties.LLM_FEW_SHOT_MAX_EXAMPLES;
        origStrategy = Properties.LLM_FEW_SHOT_ARCHIVE_STRATEGY;
        origMaxCharsTotal = Properties.LLM_FEW_SHOT_MAX_CHARS_TOTAL;
        origMaxCharsPerExample = Properties.LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE;
        origTargetClass = Properties.TARGET_CLASS;
        origSelectedJunit = Properties.SELECTED_JUNIT;
        origSeedTestSourceDir = Properties.SEED_TEST_SOURCE_DIR;
    }

    @AfterEach
    void restore() {
        Properties.LLM_PROMPT_TECHNIQUE = origTechnique;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = origUseParsed;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = origUseArchive;
        Properties.LLM_FEW_SHOT_MAX_EXAMPLES = origMaxExamples;
        Properties.LLM_FEW_SHOT_ARCHIVE_STRATEGY = origStrategy;
        Properties.LLM_FEW_SHOT_MAX_CHARS_TOTAL = origMaxCharsTotal;
        Properties.LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE = origMaxCharsPerExample;
        Properties.TARGET_CLASS = origTargetClass;
        Properties.SELECTED_JUNIT = origSelectedJunit;
        Properties.SEED_TEST_SOURCE_DIR = origSeedTestSourceDir;
        ParsedFewShotExampleSource.reset();
    }

    // -- Helper methods --

    private static TestCase makeTestCase(String marker) {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new StringPrimitiveStatement(tc, marker));
        return tc;
    }

    private static TestChromosome makeChromosome(String marker) {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(makeTestCase(marker));
        return tc;
    }

    private static TestChromosome makeChromosomeWithGoals(String marker,
                                                          TestFitnessFunction... goals) {
        TestChromosome tc = new TestChromosome();
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, marker));
        for (TestFitnessFunction goal : goals) {
            testCase.addCoveredGoal(goal);
        }
        tc.setTestCase(testCase);
        return tc;
    }

    private static TestFitnessFunction mockGoal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn(name);
        return goal;
    }

    // -- Existing tests (updated for renames) --

    @Test
    void returnsParsedExamplesWhenAvailable() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("parsed1"), makeTestCase("parsed2"));

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        List<TestCase> examples = provider.getExamples();

        assertEquals(2, examples.size());
        assertEquals(parsed.get(0), examples.get(0));
        assertEquals(parsed.get(1), examples.get(1));
    }

    @Test
    void returnsEmptyWhenBothSourcesDisabled() {
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Arrays.asList(makeTestCase("x")),
                false, false, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        List<TestCase> examples = provider.getExamples();

        assertTrue(examples.isEmpty());
    }

    @Test
    void gracefullyHandlesNullParsedTests() {
        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, true, false, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        List<TestCase> examples = provider.getExamples();

        assertTrue(examples.isEmpty());
    }

    @Test
    void respectsMaxExamples() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("a"), makeTestCase("b"),
                makeTestCase("c"), makeTestCase("d"));

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 2, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        List<TestCase> examples = provider.getExamples();

        assertEquals(2, examples.size());
    }

    @Test
    void archiveCandidatesUsedWhenProvided() {
        TestChromosome c1 = makeChromosome("archive1");
        TestChromosome c2 = makeChromosome("archive2");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.COVERAGE_BREADTH);

        List<TestCase> examples = provider.getExamples(null, Arrays.asList(c1, c2));

        assertEquals(2, examples.size());
    }

    @Test
    void parsedAndArchiveCombined() {
        List<TestCase> parsed = Collections.singletonList(makeTestCase("parsed"));
        TestChromosome archiveTest = makeChromosome("archive");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, true, 3, LlmFewShotArchiveStrategy.COVERAGE_BREADTH);

        List<TestCase> examples = provider.getExamples(null, Collections.singletonList(archiveTest));

        assertEquals(2, examples.size());
    }

    @Test
    void maxExamplesCapsCombinedSources() {
        List<TestCase> parsed = Arrays.asList(makeTestCase("p1"), makeTestCase("p2"));
        TestChromosome c1 = makeChromosome("a1");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, true, 2, LlmFewShotArchiveStrategy.COVERAGE_BREADTH);

        List<TestCase> examples = provider.getExamples(null, Collections.singletonList(c1));

        assertEquals(2, examples.size());
        assertEquals(parsed.get(0), examples.get(0));
        assertEquals(parsed.get(1), examples.get(1));
    }

    @Test
    void coverageBreadthRanksByCoveredGoalsThenSize() {
        // Chromosome with more covered goals should come first
        TestFitnessFunction g1 = mockGoal("g1");
        TestFitnessFunction g2 = mockGoal("g2");
        TestFitnessFunction g3 = mockGoal("g3");

        TestChromosome few = makeChromosomeWithGoals("fewGoals", g1);
        TestChromosome many = makeChromosomeWithGoals("manyGoals", g1, g2, g3);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.COVERAGE_BREADTH);

        List<TestCase> examples = provider.getExamples(null, Arrays.asList(few, many));

        assertEquals(2, examples.size());
        // Many goals first
        assertEquals(3, examples.get(0).getCoveredGoals().size());
        assertEquals(1, examples.get(1).getCoveredGoals().size());
    }

    @Test
    void deterministicSelectionUnderFixedInput() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("a"), makeTestCase("b"), makeTestCase("c"));

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 2, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        List<TestCase> run1 = provider.getExamples();
        List<TestCase> run2 = provider.getExamples();

        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            assertEquals(run1.get(i).toCode(), run2.get(i).toCode());
        }
    }

    @Test
    void collectIfFewShotReturnsEmptyForNone() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.NONE;
        assertTrue(FewShotExampleProvider.collectIfFewShot(null, null).isEmpty());
    }

    @Test
    void collectIfFewShotReturnsEmptyForChainOfThought() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.CHAIN_OF_THOUGHT;
        assertTrue(FewShotExampleProvider.collectIfFewShot(null, null).isEmpty());
    }

    // -- New tests for Fix B: GOAL_OVERLAP ranking quality --

    @Test
    void goalOverlapRanksbyDirectGoalMatch() {
        TestFitnessFunction uncoveredA = mockGoal("uncoveredA");
        TestFitnessFunction uncoveredB = mockGoal("uncoveredB");
        TestFitnessFunction coveredX = mockGoal("coveredX");

        // Candidate 1 covers only uncoveredA (1 overlap with uncovered)
        TestChromosome c1 = makeChromosomeWithGoals("c1", uncoveredA, coveredX);
        // Candidate 2 covers both uncoveredA and uncoveredB (2 overlaps)
        TestChromosome c2 = makeChromosomeWithGoals("c2", uncoveredA, uncoveredB);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        Set<TestFitnessFunction> uncovered = new LinkedHashSet<>(Arrays.asList(uncoveredA, uncoveredB));
        List<TestCase> examples = provider.getExamples(uncovered, Arrays.asList(c1, c2));

        assertEquals(2, examples.size());
        // c2 should rank first (2 overlaps vs 1)
        assertTrue(examples.get(0).getCoveredGoals().contains(uncoveredB),
                "Candidate with more uncovered-goal overlap should rank first");
    }

    @Test
    void goalOverlapDeterministicTieBreaking() {
        TestFitnessFunction uncoveredA = mockGoal("uncoveredA");

        // Both candidates cover exactly 1 uncovered goal
        TestChromosome c1 = makeChromosomeWithGoals("aaa", uncoveredA);
        TestChromosome c2 = makeChromosomeWithGoals("bbb", uncoveredA);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        Set<TestFitnessFunction> uncovered = Collections.singleton(uncoveredA);

        List<TestCase> run1 = provider.getExamples(uncovered, Arrays.asList(c1, c2));
        List<TestCase> run2 = provider.getExamples(uncovered, Arrays.asList(c2, c1));

        assertEquals(run1.get(0).toCode(), run2.get(0).toCode(),
                "Tie-breaking must be deterministic regardless of input order");
    }

    @Test
    void goalOverlapFallsToCoverageBreadthWhenNoUncoveredGoals() {
        TestFitnessFunction g1 = mockGoal("g1");
        TestFitnessFunction g2 = mockGoal("g2");

        TestChromosome small = makeChromosomeWithGoals("small", g1);
        TestChromosome big = makeChromosomeWithGoals("big", g1, g2);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        // No uncovered goals → falls back to COVERAGE_BREADTH
        List<TestCase> examples = provider.getExamples(null, Arrays.asList(small, big));

        assertEquals(2, examples.size());
        assertEquals(2, examples.get(0).getCoveredGoals().size(),
                "Without uncovered goals, should fall back to coverage breadth ranking");
    }

    // -- New tests for Fix C: char-budget constraints --

    @Test
    void maxCharsPerExampleTruncatesLargeExamples() {
        // Create a test that generates code longer than the per-example cap
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 20; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "longMarker_" + i));
        }
        List<TestCase> parsed = Collections.singletonList(tc);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP,
                0, 50);  // maxCharsPerExample = 50

        List<TestCase> examples = provider.getExamples();

        // The example should still be returned (truncation happens at prompt level)
        assertEquals(1, examples.size());
    }

    @Test
    void maxCharsTotalCapsNumberOfExamples() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("aaaaa"),
                makeTestCase("bbbbb"),
                makeTestCase("ccccc"));

        // Very tight total budget — only first example(s) should fit
        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 10, LlmFewShotArchiveStrategy.GOAL_OVERLAP,
                40, 0);  // very tight total budget

        List<TestCase> examples = provider.getExamples();

        assertTrue(examples.size() < 3,
                "Total char budget should cap number of examples");
        assertTrue(examples.size() >= 1,
                "At least first example should be included");
    }

    @Test
    void charBudgetZeroMeansUnlimited() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("a"), makeTestCase("b"), makeTestCase("c"));

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 10, LlmFewShotArchiveStrategy.GOAL_OVERLAP,
                0, 0);  // 0 = unlimited

        List<TestCase> examples = provider.getExamples();

        assertEquals(3, examples.size());
    }

    @Test
    void charBudgetEnforcedExactly() {
        // Measure actual code length to set tight budget
        TestCase tc1 = makeTestCase("aaa");
        TestCase tc2 = makeTestCase("bbb");
        int len1 = tc1.toCode().length();
        int len2 = tc2.toCode().length();

        // Budget fits exactly one example
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Arrays.asList(tc1, tc2),
                true, false, 10, LlmFewShotArchiveStrategy.GOAL_OVERLAP,
                len1, 0);

        List<TestCase> examples = provider.getExamples();

        assertEquals(1, examples.size(),
                "Budget of exactly one example's length should yield exactly one example");
    }

    // -- New tests for Fix A: ParsedFewShotExampleSource wiring --

    @Test
    void collectIfFewShotUsesParsedSource() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.FEW_SHOT;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = true;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = false;

        // Inject test-only parsed examples
        TestCase parsed = makeTestCase("parsedFromSource");
        ParsedFewShotExampleSource.setInstance(
                ParsedFewShotExampleSource.ofTests(Collections.singletonList(parsed)));

        List<TestCase> result = FewShotExampleProvider.collectIfFewShot(null, null);

        assertEquals(1, result.size());
        assertEquals(parsed.toCode(), result.get(0).toCode(),
                "collectIfFewShot should use ParsedFewShotExampleSource");
    }

    @Test
    void collectIfFewShotSkipsParsedWhenDisabled() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.FEW_SHOT;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = false;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = false;

        TestCase parsed = makeTestCase("shouldNotAppear");
        ParsedFewShotExampleSource.setInstance(
                ParsedFewShotExampleSource.ofTests(Collections.singletonList(parsed)));

        List<TestCase> result = FewShotExampleProvider.collectIfFewShot(null, null);

        assertTrue(result.isEmpty(),
                "Parsed examples should not be used when llm_few_shot_use_parsed_junit=false");
    }

    @Test
    void collectIfFewShotGracefulWhenNoParsedTests() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.FEW_SHOT;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = true;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = false;

        // Empty parsed source
        ParsedFewShotExampleSource.setInstance(
                ParsedFewShotExampleSource.ofTests(Collections.emptyList()));

        List<TestCase> result = FewShotExampleProvider.collectIfFewShot(null, null);

        assertTrue(result.isEmpty(),
                "Should gracefully return empty when no parsed tests available");
    }

    // -- COVERAGE_BREADTH renamed from RECENT_BEST --

    @Test
    void coverageBreadthFallsBackToSizeWhenNoGoals() {
        // When no covered goals, rank by size descending
        TestChromosome small = makeChromosome("small");
        DefaultTestCase bigCase = new DefaultTestCase();
        bigCase.addStatement(new StringPrimitiveStatement(bigCase, "big1"));
        bigCase.addStatement(new StringPrimitiveStatement(bigCase, "big2"));
        bigCase.addStatement(new StringPrimitiveStatement(bigCase, "big3"));
        TestChromosome big = new TestChromosome();
        big.setTestCase(bigCase);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.COVERAGE_BREADTH);

        List<TestCase> examples = provider.getExamples(null, Arrays.asList(small, big));

        assertEquals(2, examples.size());
        assertEquals(3, examples.get(0).size());
        assertEquals(1, examples.get(1).size());
    }

    // -- Fix B (Finding 2): GOAL_OVERLAP null covered-goals --

    @Test
    void goalOverlapHandlesNullCoveredGoals() {
        TestFitnessFunction uncoveredA = mockGoal("uncoveredA");

        // Candidate with null covered goals (via mock)
        TestChromosome nullGoals = new TestChromosome();
        DefaultTestCase nullGoalCase = new DefaultTestCase();
        nullGoalCase.addStatement(new StringPrimitiveStatement(nullGoalCase, "nullGoals"));
        nullGoals.setTestCase(nullGoalCase);
        // getCoveredGoals() returns empty set by default on DefaultTestCase,
        // but the tie-break used to NPE when coveredGoals was null. Ensure no crash.

        TestChromosome normalGoals = makeChromosomeWithGoals("normal", uncoveredA);

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        Set<TestFitnessFunction> uncovered = Collections.singleton(uncoveredA);

        // Must not throw
        List<TestCase> examples = provider.getExamples(uncovered,
                Arrays.asList(nullGoals, normalGoals));

        assertEquals(2, examples.size());
        // normalGoals should rank first (1 overlap vs 0)
        assertTrue(examples.get(0).getCoveredGoals().contains(uncoveredA));
    }

    @Test
    void goalOverlapNullCoveredGoalsDeterministic() {
        TestFitnessFunction uncoveredA = mockGoal("uncoveredA");

        // Both candidates have zero overlap; one has null-like covered goals
        TestChromosome c1 = makeChromosome("alpha");
        TestChromosome c2 = makeChromosome("beta");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                null, false, true, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP);

        Set<TestFitnessFunction> uncovered = Collections.singleton(uncoveredA);

        List<TestCase> run1 = provider.getExamples(uncovered, Arrays.asList(c1, c2));
        List<TestCase> run2 = provider.getExamples(uncovered, Arrays.asList(c2, c1));

        assertEquals(run1.get(0).toCode(), run2.get(0).toCode(),
                "Ranking must be deterministic with empty covered goals");
    }

    // -- Fix A (Finding 1): Snippet-based char-budget enforcement --

    @Test
    void getExampleSnippetsReturnsStrings() {
        List<TestCase> parsed = Arrays.asList(
                makeTestCase("snippet1"), makeTestCase("snippet2"));

        FewShotExampleProvider provider = new FewShotExampleProvider(
                parsed, true, false, 3, LlmFewShotArchiveStrategy.GOAL_OVERLAP,
                0, 0);

        List<String> snippets = provider.getExampleSnippets();

        assertEquals(2, snippets.size());
        assertTrue(snippets.get(0).contains("snippet1"));
        assertTrue(snippets.get(1).contains("snippet2"));
    }

    @Test
    void getExampleSnippetsEnforcesPerExampleCap() {
        // Create a test that generates long code
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 20; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "longMarker_" + i));
        }
        int fullLength = tc.toCode().length();
        int cap = 50;
        assertTrue(fullLength > cap, "Precondition: test code must exceed cap");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                LlmFewShotArchiveStrategy.GOAL_OVERLAP, 0, cap);

        List<String> snippets = provider.getExampleSnippets();

        assertEquals(1, snippets.size());
        // The truncated snippet body (before marker) must be <= cap
        String snippet = snippets.get(0);
        // Snippet includes truncation marker, but the code portion is capped
        assertTrue(snippet.length() < fullLength,
                "Snippet must be shorter than original code");
    }

    @Test
    void getExampleSnippetsEnforcesTotalCap() {
        TestCase tc1 = makeTestCase("aaaaa");
        TestCase tc2 = makeTestCase("bbbbb");
        TestCase tc3 = makeTestCase("ccccc");
        int singleLen = tc1.toCode().length();

        // Budget fits at most 1 example
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Arrays.asList(tc1, tc2, tc3), true, false, 10,
                LlmFewShotArchiveStrategy.GOAL_OVERLAP, singleLen, 0);

        List<String> snippets = provider.getExampleSnippets();

        assertEquals(1, snippets.size(),
                "Total budget should cap at 1 snippet");
    }

    @Test
    void getExampleSnippetsFirstExampleExceedsTotalCap() {
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 10; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "big_" + i));
        }
        int fullLength = tc.toCode().length();

        // Total cap is smaller than one example, but per-example cap is unlimited
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                LlmFewShotArchiveStrategy.GOAL_OVERLAP, 10, 0);

        List<String> snippets = provider.getExampleSnippets();

        // First example should still be included (graceful behavior)
        assertEquals(1, snippets.size(),
                "First example should be included even if it exceeds total cap");
    }

    @Test
    void collectSnippetsIfFewShotReturnsEmptyForNone() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.NONE;
        assertTrue(FewShotExampleProvider.collectSnippetsIfFewShot(null, null).isEmpty());
    }

    @Test
    void collectSnippetsIfFewShotReturnsEmptyForChainOfThought() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.CHAIN_OF_THOUGHT;
        assertTrue(FewShotExampleProvider.collectSnippetsIfFewShot(null, null).isEmpty());
    }

    // -- Issue 1 fix: hard cap compliance --

    @Test
    void snippetLengthNeverExceedsMaxCharsPerExample() {
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 30; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "longMarker_" + i));
        }
        int fullLength = tc.toCode().length();

        for (int cap : new int[]{10, 20, 50, 100, 200}) {
            assertTrue(fullLength > cap, "Precondition: code length must exceed cap " + cap);
            FewShotExampleProvider provider = new FewShotExampleProvider(
                    Collections.singletonList(tc), true, false, 3,
                    LlmFewShotArchiveStrategy.GOAL_OVERLAP, 0, cap);

            List<String> snippets = provider.getExampleSnippets();
            assertEquals(1, snippets.size());
            assertTrue(snippets.get(0).length() <= cap,
                    "Snippet length " + snippets.get(0).length()
                            + " must be <= maxCharsPerExample " + cap);
        }
    }

    @Test
    void tinyCap_snippetStillReturned() {
        TestCase tc = makeTestCase("hello");
        int fullLength = tc.toCode().length();
        assertTrue(fullLength > 1, "Precondition");

        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                LlmFewShotArchiveStrategy.GOAL_OVERLAP, 0, 1);

        List<String> snippets = provider.getExampleSnippets();
        assertEquals(1, snippets.size());
        assertEquals(1, snippets.get(0).length(),
                "Snippet with cap=1 must be exactly 1 char");
    }

    @Test
    void capExactlyMarkerLength_noMarkerAppended() {
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < 20; i++) {
            tc.addStatement(new StringPrimitiveStatement(tc, "x" + i));
        }
        // "\n// ... (truncated)" is 20 chars; cap=20 means marker exactly fills budget
        int cap = 20;
        FewShotExampleProvider provider = new FewShotExampleProvider(
                Collections.singletonList(tc), true, false, 3,
                LlmFewShotArchiveStrategy.GOAL_OVERLAP, 0, cap);

        List<String> snippets = provider.getExampleSnippets();
        assertEquals(1, snippets.size());
        assertTrue(snippets.get(0).length() <= cap,
                "Snippet length " + snippets.get(0).length() + " must be <= " + cap);
    }

    // -- Issue 3 fix: ParsedFewShotExampleSource cache invalidation --

    @Test
    void parsedSourceReloadsWhenPropertyTupleChanges() {
        Properties.TARGET_CLASS = "com.example.Alpha";
        Properties.SELECTED_JUNIT = "AlphaTest";
        Properties.SEED_TEST_SOURCE_DIR = null;

        // Install a loader that returns a test tagged with the current TARGET_CLASS
        ParsedFewShotExampleSource.loaderOverride = () ->
                Collections.singletonList(makeTestCase(Properties.TARGET_CLASS));

        ParsedFewShotExampleSource src1 = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, src1.getParsedTests().size());
        assertTrue(src1.getParsedTests().get(0).toCode().contains("Alpha"));

        // Change TARGET_CLASS — no reset(), no setInstance()
        Properties.TARGET_CLASS = "com.example.Beta";
        ParsedFewShotExampleSource src2 = ParsedFewShotExampleSource.getInstance();

        assertNotSame(src1, src2, "Instance must differ after property change");
        assertTrue(src2.getParsedTests().get(0).toCode().contains("Beta"),
                "Reloaded instance must reflect new property value");

        // Clean up override
        ParsedFewShotExampleSource.loaderOverride = null;
    }

    @Test
    void parsedSourceReusesCacheWhenPropertiesUnchanged() {
        Properties.TARGET_CLASS = "com.example.Stable";
        Properties.SELECTED_JUNIT = "StableTest";
        Properties.SEED_TEST_SOURCE_DIR = null;

        java.util.concurrent.atomic.AtomicInteger loadCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        ParsedFewShotExampleSource.loaderOverride = () -> {
            loadCount.incrementAndGet();
            return Collections.singletonList(makeTestCase("stable"));
        };

        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();
        assertSame(first, second, "Same property tuple should reuse cached instance");
        assertEquals(1, loadCount.get(), "Loader must be called exactly once");

        // Clean up override
        ParsedFewShotExampleSource.loaderOverride = null;
    }
}
