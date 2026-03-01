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
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Provides concrete test examples for FEW_SHOT prompting.
 *
 * <p>Sources examples from two places (controlled by properties):
 * <ol>
 *   <li><b>Parsed JUnit tests</b> — external tests loaded via
 *       {@link ParsedFewShotExampleSource} from {@code SELECTED_JUNIT} /
 *       {@code SEED_TEST_SOURCE_DIR}.</li>
 *   <li><b>Archive/population tests</b> — successful internal tests from the
 *       GA archive or current population.</li>
 * </ol>
 *
 * <p>Selection is deterministic under a fixed set of inputs (stable ordering,
 * no randomness). The provider never throws; if no examples are available it
 * returns an empty list.
 *
 * <p>Prompt-size guardrails: individual examples exceeding
 * {@code LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE} are truncated, and total example
 * text is capped at {@code LLM_FEW_SHOT_MAX_CHARS_TOTAL}.
 */
public class FewShotExampleProvider {

    private static final Logger logger = LoggerFactory.getLogger(FewShotExampleProvider.class);

    private final List<TestCase> parsedTests;
    private final boolean useParsedJunit;
    private final boolean useArchive;
    private final int maxExamples;
    private final int maxCharsTotal;
    private final int maxCharsPerExample;
    private final Properties.LlmFewShotArchiveStrategy archiveStrategy;

    /**
     * Creates a provider using the current {@link Properties} settings.
     *
     * @param parsedTests externally parsed JUnit tests (may be null or empty)
     */
    public FewShotExampleProvider(List<TestCase> parsedTests) {
        this(parsedTests,
                Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT,
                Properties.LLM_FEW_SHOT_USE_ARCHIVE,
                Properties.LLM_FEW_SHOT_MAX_EXAMPLES,
                Properties.LLM_FEW_SHOT_ARCHIVE_STRATEGY,
                Properties.LLM_FEW_SHOT_MAX_CHARS_TOTAL,
                Properties.LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE);
    }

    /**
     * Creates a provider with explicit configuration (for testing).
     */
    public FewShotExampleProvider(List<TestCase> parsedTests,
                                  boolean useParsedJunit,
                                  boolean useArchive,
                                  int maxExamples,
                                  Properties.LlmFewShotArchiveStrategy archiveStrategy) {
        this(parsedTests, useParsedJunit, useArchive, maxExamples, archiveStrategy,
                Properties.LLM_FEW_SHOT_MAX_CHARS_TOTAL,
                Properties.LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE);
    }

    /**
     * Creates a provider with full explicit configuration (for testing).
     */
    public FewShotExampleProvider(List<TestCase> parsedTests,
                                  boolean useParsedJunit,
                                  boolean useArchive,
                                  int maxExamples,
                                  Properties.LlmFewShotArchiveStrategy archiveStrategy,
                                  int maxCharsTotal,
                                  int maxCharsPerExample) {
        this.parsedTests = parsedTests != null ? parsedTests : Collections.emptyList();
        this.useParsedJunit = useParsedJunit;
        this.useArchive = useArchive;
        this.maxExamples = Math.max(1, maxExamples);
        this.archiveStrategy = archiveStrategy != null
                ? archiveStrategy : Properties.LlmFewShotArchiveStrategy.GOAL_OVERLAP;
        this.maxCharsTotal = maxCharsTotal;
        this.maxCharsPerExample = maxCharsPerExample;
    }

    /**
     * Returns up to {@code maxExamples} example test cases for FEW_SHOT prompting,
     * subject to character-budget constraints (count filtering only — individual
     * examples are not truncated). Prefer {@link #getExampleSnippets} for prompt
     * rendering, which returns already-truncated code strings.
     *
     * @param uncoveredGoals currently uncovered goals (may be null)
     * @param archiveCandidates explicit archive/population candidates (may be null;
     *                          if null the global archive is consulted)
     * @return a list of example {@link TestCase}s, never null
     */
    public List<TestCase> getExamples(
            Collection<TestFitnessFunction> uncoveredGoals,
            Collection<TestChromosome> archiveCandidates) {
        List<TestCase> examples = selectExamples(uncoveredGoals, archiveCandidates);

        // Apply character-budget constraints (count filtering only)
        examples = applyCharBudget(examples);

        logger.debug("FewShotExampleProvider: returning {} examples "
                + "(parsed={}, archive={})", examples.size(), useParsedJunit, useArchive);
        return examples;
    }

    /**
     * Returns up to {@code maxExamples} example code snippets for FEW_SHOT
     * prompting, with per-example and total character budgets enforced on the
     * actual rendered text. This is the recommended API for prompt construction.
     *
     * @param uncoveredGoals currently uncovered goals (may be null)
     * @param archiveCandidates explicit archive/population candidates (may be null)
     * @return a list of code snippet strings, never null
     */
    public List<String> getExampleSnippets(
            Collection<TestFitnessFunction> uncoveredGoals,
            Collection<TestChromosome> archiveCandidates) {
        List<TestCase> examples = selectExamples(uncoveredGoals, archiveCandidates);
        List<String> snippets = applyCharBudgetToSnippets(examples);

        logger.debug("FewShotExampleProvider: returning {} snippets "
                + "(parsed={}, archive={})", snippets.size(), useParsedJunit, useArchive);
        return snippets;
    }

    /** Convenience overload with no explicit archive candidates. */
    public List<String> getExampleSnippets(
            Collection<TestFitnessFunction> uncoveredGoals) {
        return getExampleSnippets(uncoveredGoals, null);
    }

    /** Convenience overload with no goals or candidates. */
    public List<String> getExampleSnippets() {
        return getExampleSnippets(null, null);
    }

    /**
     * Selects examples from parsed and archive sources, capped to maxExamples.
     */
    private List<TestCase> selectExamples(
            Collection<TestFitnessFunction> uncoveredGoals,
            Collection<TestChromosome> archiveCandidates) {
        List<TestCase> examples = new ArrayList<>();
        try {
            if (useParsedJunit) {
                addParsedExamples(examples);
            }
            if (useArchive && examples.size() < maxExamples) {
                addArchiveExamples(examples, uncoveredGoals, archiveCandidates);
            }
        } catch (Exception e) {
            logger.warn("FewShotExampleProvider: error collecting examples, "
                    + "proceeding with {} collected so far", examples.size(), e);
        }

        // Cap to maxExamples
        if (examples.size() > maxExamples) {
            examples = new ArrayList<>(examples.subList(0, maxExamples));
        }
        return examples;
    }

    /**
     * Convenience overload with no explicit archive candidates.
     */
    public List<TestCase> getExamples(
            Collection<TestFitnessFunction> uncoveredGoals) {
        return getExamples(uncoveredGoals, null);
    }

    /**
     * Convenience overload with no goals or candidates.
     */
    public List<TestCase> getExamples() {
        return getExamples(null, null);
    }

    private void addParsedExamples(List<TestCase> examples) {
        if (parsedTests.isEmpty()) {
            return;
        }
        int remaining = maxExamples - examples.size();
        for (int i = 0; i < parsedTests.size() && remaining > 0; i++) {
            TestCase tc = parsedTests.get(i);
            if (tc != null && tc.size() > 0) {
                examples.add(tc);
                remaining--;
            }
        }
    }

    private void addArchiveExamples(List<TestCase> examples,
                                    Collection<TestFitnessFunction> uncoveredGoals,
                                    Collection<TestChromosome> archiveCandidates) {
        Set<TestChromosome> candidates = resolveArchiveCandidates(archiveCandidates);
        if (candidates.isEmpty()) {
            return;
        }

        List<TestChromosome> ranked;
        if (archiveStrategy == Properties.LlmFewShotArchiveStrategy.GOAL_OVERLAP
                && uncoveredGoals != null && !uncoveredGoals.isEmpty()) {
            ranked = rankByGoalOverlap(candidates, uncoveredGoals);
        } else {
            ranked = rankByCoverageBreadth(candidates);
        }

        int remaining = maxExamples - examples.size();
        Set<String> seen = new HashSet<>();
        for (TestCase ex : examples) {
            seen.add(stableKey(ex));
        }
        for (TestChromosome tc : ranked) {
            if (remaining <= 0) break;
            TestCase testCase = tc.getTestCase();
            if (testCase != null && testCase.size() > 0) {
                String key = stableKey(testCase);
                if (seen.add(key)) {
                    examples.add(testCase);
                    remaining--;
                }
            }
        }
    }

    private Set<TestChromosome> resolveArchiveCandidates(
            Collection<TestChromosome> explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return new LinkedHashSet<>(explicit);
        }
        try {
            Archive archive = Archive.getArchiveInstance();
            if (archive != null) {
                Set<TestChromosome> solutions = archive.getSolutions();
                if (solutions != null) {
                    return solutions;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not access archive for FEW_SHOT examples", e);
        }
        return Collections.emptySet();
    }

    /**
     * GOAL_OVERLAP: rank candidates by how many of the uncovered goals they
     * directly cover. Uses identity-based comparison via
     * {@link TestCase#getCoveredGoals()}, not string heuristics.
     * Ties broken by covered-goal count (descending), then stable key.
     */
    List<TestChromosome> rankByGoalOverlap(
            Set<TestChromosome> candidates,
            Collection<TestFitnessFunction> uncoveredGoals) {

        // Build an identity-safe set of uncovered goals for fast lookup
        Set<TestFitnessFunction> uncoveredSet = new LinkedHashSet<>(uncoveredGoals);

        Map<TestChromosome, Integer> overlapScores = new LinkedHashMap<>();
        for (TestChromosome tc : candidates) {
            int score = 0;
            TestCase testCase = tc.getTestCase();
            if (testCase != null) {
                Set<TestFitnessFunction> covered = testCase.getCoveredGoals();
                if (covered != null) {
                    for (TestFitnessFunction coveredGoal : covered) {
                        if (uncoveredSet.contains(coveredGoal)) {
                            score++;
                        }
                    }
                }
            }
            overlapScores.put(tc, score);
        }

        List<TestChromosome> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .<TestChromosome, Integer>comparing(
                        tc -> overlapScores.getOrDefault(tc, 0))
                .reversed()
                .thenComparing(tc -> {
                    TestCase t = tc.getTestCase();
                    if (t == null) return 0;
                    Set<TestFitnessFunction> goals = t.getCoveredGoals();
                    return goals != null ? goals.size() : 0;
                }, Comparator.reverseOrder())
                .thenComparing(tc -> stableKey(tc.getTestCase())));
        return sorted;
    }

    /**
     * COVERAGE_BREADTH: rank candidates by number of covered goals (descending),
     * then by test size (descending), then by stable key for determinism.
     */
    List<TestChromosome> rankByCoverageBreadth(Set<TestChromosome> candidates) {
        List<TestChromosome> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .<TestChromosome, Integer>comparing(tc -> {
                    TestCase t = tc.getTestCase();
                    if (t == null) return 0;
                    Set<TestFitnessFunction> goals = t.getCoveredGoals();
                    return goals != null ? goals.size() : 0;
                })
                .reversed()
                .thenComparing(tc -> tc.getTestCase() != null
                        ? tc.getTestCase().size() : 0, Comparator.reverseOrder())
                .thenComparing(tc -> stableKey(tc.getTestCase())));
        return sorted;
    }

    /**
     * Applies per-example and total character budget constraints, returning
     * already-truncated code snippets. This is the method that enforces caps
     * on actual rendered text.
     */
    List<String> applyCharBudgetToSnippets(List<TestCase> examples) {
        List<String> result = new ArrayList<>();
        int totalChars = 0;

        for (TestCase tc : examples) {
            String code;
            try {
                code = tc.toCode();
            } catch (Exception e) {
                code = "";
            }

            // Per-example cap: truncate if needed, final length always <= maxCharsPerExample
            if (maxCharsPerExample > 0 && code.length() > maxCharsPerExample) {
                String marker = "\n// ... (truncated)";
                if (maxCharsPerExample > marker.length()) {
                    code = code.substring(0, maxCharsPerExample - marker.length()) + marker;
                } else {
                    code = code.substring(0, maxCharsPerExample);
                }
            }

            // Total budget check
            if (maxCharsTotal > 0 && totalChars + code.length() > maxCharsTotal
                    && !result.isEmpty()) {
                break;
            }

            totalChars += code.length();
            result.add(code);
        }

        return result;
    }

    /**
     * Applies per-example and total character budget constraints (count
     * filtering only — returned TestCases are not truncated).
     */
    List<TestCase> applyCharBudget(List<TestCase> examples) {
        if (maxCharsTotal <= 0 && maxCharsPerExample <= 0) {
            return examples;
        }

        List<TestCase> result = new ArrayList<>();
        int totalChars = 0;

        for (TestCase tc : examples) {
            String code;
            try {
                code = tc.toCode();
            } catch (Exception e) {
                code = "";
            }

            // Per-example cap: truncate if needed
            if (maxCharsPerExample > 0 && code.length() > maxCharsPerExample) {
                code = code.substring(0, maxCharsPerExample);
            }

            // Total budget check
            if (maxCharsTotal > 0 && totalChars + code.length() > maxCharsTotal
                    && !result.isEmpty()) {
                break;
            }

            totalChars += code.length();
            result.add(tc);
        }

        return result;
    }

    static String stableKey(TestCase tc) {
        if (tc == null) return "";
        try {
            return String.valueOf(tc.hashCode()) + ":" + tc.size();
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(tc));
        }
    }

    /**
     * Convenience method for call sites: collects FEW_SHOT example snippets
     * (already truncated per char budgets) only when the technique is
     * {@link Properties.LlmPromptTechnique#FEW_SHOT}. Returns an empty list
     * otherwise. This is the recommended API for prompt construction.
     *
     * @param uncoveredGoals currently uncovered goals (may be null)
     * @param archiveCandidates explicit candidates (may be null)
     * @return truncated code snippets, never null
     */
    public static List<String> collectSnippetsIfFewShot(
            Collection<TestFitnessFunction> uncoveredGoals,
            Collection<TestChromosome> archiveCandidates) {
        if (Properties.LLM_PROMPT_TECHNIQUE != Properties.LlmPromptTechnique.FEW_SHOT) {
            return Collections.emptyList();
        }
        List<TestCase> parsedTests = Collections.emptyList();
        if (Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT) {
            try {
                parsedTests = ParsedFewShotExampleSource.getInstance().getParsedTests();
            } catch (Exception e) {
                logger.debug("Could not load parsed tests for FEW_SHOT", e);
            }
        }
        return new FewShotExampleProvider(parsedTests)
                .getExampleSnippets(uncoveredGoals, archiveCandidates);
    }

    /**
     * Convenience method for call sites: collects FEW_SHOT examples only when the
     * technique is {@link Properties.LlmPromptTechnique#FEW_SHOT}. Returns an
     * empty list otherwise.
     *
     * <p>Automatically obtains parsed examples from
     * {@link ParsedFewShotExampleSource} when {@code llm_few_shot_use_parsed_junit}
     * is enabled.
     *
     * @param uncoveredGoals currently uncovered goals (may be null)
     * @param archiveCandidates explicit candidates (may be null)
     * @return example tests, never null
     * @deprecated Use {@link #collectSnippetsIfFewShot} for prompt rendering.
     */
    @Deprecated
    public static List<TestCase> collectIfFewShot(
            Collection<TestFitnessFunction> uncoveredGoals,
            Collection<TestChromosome> archiveCandidates) {
        if (Properties.LLM_PROMPT_TECHNIQUE != Properties.LlmPromptTechnique.FEW_SHOT) {
            return Collections.emptyList();
        }
        List<TestCase> parsedTests = Collections.emptyList();
        if (Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT) {
            try {
                parsedTests = ParsedFewShotExampleSource.getInstance().getParsedTests();
            } catch (Exception e) {
                logger.debug("Could not load parsed tests for FEW_SHOT", e);
            }
        }
        return new FewShotExampleProvider(parsedTests)
                .getExamples(uncoveredGoals, archiveCandidates);
    }
}
