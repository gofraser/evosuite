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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent prompt builder for EvoSuite LLM requests.
 */
public class PromptBuilder {

    private final SystemPromptProvider systemPromptProvider;
    private final TestClusterSummarizer testClusterSummarizer;
    private final SourceCodeProvider sourceCodeProvider;
    private final CoverageGoalFormatter coverageGoalFormatter;
    private final TestCaseFormatter testCaseFormatter;
    private final SutContextProviderFactory sutContextProviderFactory;

    private String systemPrompt;
    private final List<String> userSections = new ArrayList<>();
    private Properties.LlmSutContextMode sutContextModeUsed;
    private boolean sutContextUnavailable;
    private boolean sutContextTruncated;
    private boolean sutContextCommentsStripped;
    private boolean sutContextSelectivelyTruncated;
    private boolean clusterSummaryTruncated;
    private int clusterSummaryChars;
    private String clusterSummaryBudgetMode = "none";
    private PromptResult.DependencySummaryMetadata dependencySummaryMetadata =
            PromptResult.DependencySummaryMetadata.empty();
    private boolean dependencyContextAdded;

    private List<TestCase> fewShotExamples;
    private List<String> fewShotSnippets;

    /** Creates a builder with default provider implementations. */
    public PromptBuilder() {
        this(new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                SutContextProviderFactory.getInstance());
    }

    /**
     * Creates a builder using the default {@link SutContextProviderFactory} singleton.
     *
     * <p><b>Note:</b> Each builder instance should be used for a single {@link #build()} or
     * {@link #buildWithMetadata()} call. Do not reuse builders across multiple prompts.
     */
    public PromptBuilder(SystemPromptProvider systemPromptProvider,
                         TestClusterSummarizer testClusterSummarizer,
                         SourceCodeProvider sourceCodeProvider,
                         CoverageGoalFormatter coverageGoalFormatter,
                         TestCaseFormatter testCaseFormatter) {
        this(systemPromptProvider, testClusterSummarizer, sourceCodeProvider,
                coverageGoalFormatter, testCaseFormatter, SutContextProviderFactory.getInstance());
    }

    /** Creates a builder with all providers and factory explicitly specified. */
    public PromptBuilder(SystemPromptProvider systemPromptProvider,
                         TestClusterSummarizer testClusterSummarizer,
                         SourceCodeProvider sourceCodeProvider,
                         CoverageGoalFormatter coverageGoalFormatter,
                         TestCaseFormatter testCaseFormatter,
                         SutContextProviderFactory sutContextProviderFactory) {
        this.systemPromptProvider = systemPromptProvider;
        this.testClusterSummarizer = testClusterSummarizer;
        this.sourceCodeProvider = sourceCodeProvider;
        this.coverageGoalFormatter = coverageGoalFormatter;
        this.testCaseFormatter = testCaseFormatter;
        this.sutContextProviderFactory = sutContextProviderFactory;
    }

    public PromptBuilder withSystemPrompt() {
        this.systemPrompt = systemPromptProvider.getSystemPrompt();
        return this;
    }

    /**
     * Uses the enrichment-specific system prompt (structured-data extraction,
     * no test-code generation). Intended for pre-search pool enrichers; see
     * {@link SystemPromptProvider#getEnrichmentSystemPrompt()}.
     */
    public PromptBuilder withEnrichmentSystemPrompt() {
        this.systemPrompt = systemPromptProvider.getEnrichmentSystemPrompt();
        return this;
    }

    /** Sets a fully custom system prompt. Use sparingly — prefer the named variants above. */
    public PromptBuilder withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    /**
     * Add CUT context using the configured {@code LLM_SUT_CONTEXT_MODE}.
     * This replaces separate {@code withTestClusterContext} and {@code withSourceCode} calls.
     */
    public PromptBuilder withSutContext(String className, TestCluster cluster) {
        return withSutContext(className, cluster, true, true);
    }

    /**
     * Add CUT context with explicit control over the test-generation reminders
     * and dependency cluster summary. Both are useful for code generation but
     * are noise (and a test-code bias signal) for structured-data extraction
     * tasks like constant-pool enrichment.
     *
     * @param includeTestGenerationReminders emit the two "IMPORTANT" reminders
     *     about generic-type fidelity and visibility (only meaningful when the
     *     model is asked to produce Java test code)
     * @param includeDependencyContext add the test-cluster dependency summary
     *     (constructors / factories / modifiers); useful for test code, not
     *     for picking literal constants
     */
    public PromptBuilder withSutContext(String className, TestCluster cluster,
                                        boolean includeTestGenerationReminders,
                                        boolean includeDependencyContext) {
        SutContextProviderFactory.ContextResult result =
                sutContextProviderFactory.getContext(className, cluster);
        this.sutContextModeUsed = result.getModeUsed();
        this.sutContextUnavailable = result.isContextUnavailable();
        this.sutContextTruncated = result.isContextTruncated();
        this.sutContextCommentsStripped = result.isCommentsStripped();
        this.sutContextSelectivelyTruncated = result.isSelectivelyTruncated();
        String text = result.getText();
        if (text != null && !text.trim().isEmpty()) {
            userSections.add("CLASS UNDER TEST (SUT): " + className + "\n\n" + result.getModeUsed().name() + " context:\n```\n" + text + "\n```");
            if (includeTestGenerationReminders) {
                userSections.add("IMPORTANT: Ensure that all generic types "
                        + "(e.g., Vector<Character>, List<String>) match the "
                        + "class definition exactly. Do not use generic types like Vector<String> if the class "
                        + "expects Vector<Character>.");
                userSections.add("IMPORTANT: Do NOT access private or protected fields or methods. "
                        + "Only use public and package-private members in the generated tests.");
            }
        }
        if (includeDependencyContext) {
            addDependencyContext(className, cluster);
        }
        return this;
    }

    /** Adds test cluster context (available API signatures) to the user prompt. */
    public PromptBuilder withTestClusterContext(TestCluster cluster) {
        addDependencyContext(Properties.TARGET_CLASS, cluster);
        return this;
    }

    /**
     * Adds a compact dependency summary (constructors, enum constants) to the user prompt.
     * The CUT itself is excluded since it's already covered by {@link #withSutContext}.
     */
    public PromptBuilder withTestClusterContext(String className, TestCluster cluster) {
        addDependencyContext(className, cluster);
        return this;
    }

    private void addDependencyContext(String className, TestCluster cluster) {
        if (dependencyContextAdded) {
            return;
        }
        if (cluster == null) {
            return;
        }
        String targetClassName = className == null ? "" : className;
        BudgetDecision budgetDecision = resolveDependencySummaryBudget();
        int dependencyBudget = budgetDecision.budgetChars;
        this.clusterSummaryBudgetMode = budgetDecision.mode;
        TestClusterSummarizer.DependencySummaryResult result =
                testClusterSummarizer.summarizeDependencies(
                        cluster, targetClassName, dependencyBudget);
        this.clusterSummaryTruncated = result.isTruncated();
        this.clusterSummaryChars = result.getTotalCharsBeforeTruncation();
        this.dependencySummaryMetadata = new PromptResult.DependencySummaryMetadata(
                result.getBudgetCharsUsed(),
                result.getPerClassSoftCapUsed(),
                result.isPerClassSoftCapAuto(),
                result.isCompactSignaturesUsed(),
                clusterSummaryBudgetMode,
                result.getCandidateClasses(),
                result.getEmittedClasses(),
                result.getEmittedTier1Classes(),
                result.getEmittedTier2Classes(),
                result.getEmittedTier3Classes(),
                result.getEmittedInstantiators(),
                result.getEmittedModifiers(),
                result.getDroppedByPerClassCap(),
                result.getDroppedByGlobalBudget());
        String summary = result.getText();
        if (summary != null && !summary.trim().isEmpty()) {
            userSections.add("Available dependency types (standard Java library "
                    + "classes may also be used freely):\n" + summary);
            userSections.add("IMPORTANT: Instantiate only concrete classes. "
                    + "Do NOT use new on abstract classes or interfaces; use concrete subtypes listed in dependency context.");
            dependencyContextAdded = true;
        }
    }

    private BudgetDecision resolveDependencySummaryBudget() {
        int absoluteOverride = Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS;
        if (absoluteOverride > 0) {
            return new BudgetDecision(absoluteOverride, "absolute_override");
        }

        if (!Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING) {
            return new BudgetDecision(Properties.LLM_CLUSTER_SUMMARY_MAX_CHARS, "legacy_static");
        }

        int contextMaxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        int dynamicMax = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS;
        if (contextMaxChars <= 0) {
            // Context is unlimited/unknown: use conservative dynamic guardrail if configured.
            if (dynamicMax > 0) {
                return new BudgetDecision(dynamicMax, "dynamic_max_for_unbounded_context");
            }
            return new BudgetDecision(Properties.LLM_CLUSTER_SUMMARY_MAX_CHARS, "legacy_static_for_unbounded_context");
        }

        int scaled = (int) Math.round(contextMaxChars * Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO);
        int bounded = Math.max(scaled, Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS);
        if (dynamicMax > 0) {
            bounded = Math.min(bounded, dynamicMax);
        }
        return new BudgetDecision(bounded, "dynamic_scaled");
    }

    private static class BudgetDecision {
        final int budgetChars;
        final String mode;

        BudgetDecision(int budgetChars, String mode) {
            this.budgetChars = budgetChars;
            this.mode = mode;
        }
    }

    /** Adds the source code of the given class to the user prompt when available. */
    public PromptBuilder withSourceCode(String className) {
        Optional<String> sourceCode = sourceCodeProvider.getSourceCode(className);
        if (sourceCode.isPresent()) {
            userSections.add("Source code:\n```java\n" + sourceCode.get() + "\n```");
        }
        return this;
    }

    /** Adds the given uncovered coverage goals as a section in the user prompt. */
    public PromptBuilder withUncoveredGoals(Collection<TestFitnessFunction> goals) {
        return withUncoveredGoals(goals, null);
    }

    /**
     * Adds the given uncovered coverage goals as a section in the user prompt,
     * with optional fitness-distance annotations. Goals with low non-zero fitness
     * are highlighted as "almost covered" by {@link CoverageGoalFormatter}.
     *
     * @param goals             the uncovered goals
     * @param fitnessDistances  best-known fitness distance per goal (lower = closer to covered);
     *                          may be {@code null} or empty
     */
    public PromptBuilder withUncoveredGoals(Collection<TestFitnessFunction> goals,
                                            Map<TestFitnessFunction, Double> fitnessDistances) {
        userSections.add("Uncovered goals:\n"
                + coverageGoalFormatter.format(goals, fitnessDistances));
        return this;
    }

    /** Adds an existing test case to the user prompt as context. */
    public PromptBuilder withExistingTest(TestCase test) {
        String formatted = Properties.LLM_ANNOTATE_EXISTING_TESTS
                ? testCaseFormatter.formatWithCoverage(test)
                : testCaseFormatter.format(test);
        userSections.add("Existing test:\n```java\n" + formatted + "\n```");
        return this;
    }

    /**
     * Adds multiple existing test cases to the user prompt as context,
     * enforcing per-test and total character budgets from
     * {@code LLM_EXISTING_TESTS_MAX_CHARS_PER_TEST} and
     * {@code LLM_EXISTING_TESTS_MAX_CHARS_TOTAL}. If
     * {@code LLM_ANNOTATE_EXISTING_TESTS} is enabled, each test is
     * annotated with its covered goals.
     */
    public PromptBuilder withExistingTests(List<TestCase> tests) {
        if (tests == null || tests.isEmpty()) {
            return this;
        }
        int maxTotal = Properties.LLM_EXISTING_TESTS_MAX_CHARS_TOTAL;
        int maxPerTest = Properties.LLM_EXISTING_TESTS_MAX_CHARS_PER_TEST;
        int remainingTotal = maxTotal;
        int remainingTests = tests.size();
        boolean annotate = Properties.LLM_ANNOTATE_EXISTING_TESTS;
        StringBuilder builder = new StringBuilder("Existing tests:\n");

        int testIndex = 1;
        for (TestCase test : tests) {
            String code = annotate
                    ? testCaseFormatter.formatWithCoverage(test)
                    : testCaseFormatter.format(test);

            int effectiveLimit = maxPerTest;
            if (maxTotal > 0) {
                int fairShare = remainingTotal / Math.max(1, remainingTests);
                effectiveLimit = maxPerTest > 0 ? Math.min(maxPerTest, fairShare) : fairShare;
            }
            boolean bounded = maxTotal > 0 || maxPerTest > 0;
            if (bounded && code.length() > effectiveLimit) {
                String marker = "\n// ... (truncated)";
                if (effectiveLimit > marker.length()) {
                    code = code.substring(0, effectiveLimit - marker.length()) + marker;
                } else {
                    code = code.substring(0, effectiveLimit);
                }
            }

            builder.append("Existing test #").append(testIndex++).append(":\n")
                    .append("```java\n").append(code).append("\n```\n");
            if (maxTotal > 0) {
                remainingTotal = Math.max(0, remainingTotal - code.length());
                remainingTests--;
            }
        }
        userSections.add(builder.toString());
        return this;
    }

    /**
     * Adds a coverage feedback section summarising what the previous LLM
     * batch achieved, helping the LLM understand what worked.
     *
     * @param newlyCovered goals covered by the previous batch (may be null/empty)
     * @param totalGoals   total number of coverage goals
     * @param coveredCount number of goals now covered
     */
    public PromptBuilder withCoverageFeedback(
            Collection<TestFitnessFunction> newlyCovered,
            int totalGoals, int coveredCount) {
        if (newlyCovered == null || newlyCovered.isEmpty()) {
            return this;
        }
        double pct = totalGoals > 0 ? (100.0 * coveredCount / totalGoals) : 0.0;
        GoalDescriptionMapper mapper = new GoalDescriptionMapper();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Progress: Your previous tests covered %d new goal(s) "
                        + "(total: %d/%d, %.1f%%). Newly covered:\n",
                newlyCovered.size(), coveredCount, totalGoals, pct));
        int count = 0;
        for (TestFitnessFunction goal : newlyCovered) {
            if (count >= 10) {
                sb.append("  ... and ").append(newlyCovered.size() - count).append(" more\n");
                break;
            }
            sb.append("- ").append(mapper.describe(goal)).append('\n');
            count++;
        }
        sb.append("Focus on the remaining uncovered goals below.");
        userSections.add(sb.toString());
        return this;
    }

    public PromptBuilder withError(String errorMessage) {
        userSections.add("Repair error context:\n" + errorMessage);
        return this;
    }

    /** Adds a free-form instruction string as the final user prompt section. */
    public PromptBuilder withInstruction(String instruction) {
        userSections.add(instruction);
        return this;
    }

    /**
     * Sets FEW_SHOT example tests to be injected when the prompt technique is
     * {@link Properties.LlmPromptTechnique#FEW_SHOT}.
     *
     * @param examples concrete test examples (may be null or empty)
     * @return this builder
     * @deprecated Use {@link #withFewShotSnippets(List)} for char-budget enforcement.
     */
    @Deprecated
    public PromptBuilder withFewShotExamples(List<TestCase> examples) {
        this.fewShotExamples = examples;
        return this;
    }

    /**
     * Sets FEW_SHOT example code snippets (already truncated per char budgets)
     * to be injected when the prompt technique is
     * {@link Properties.LlmPromptTechnique#FEW_SHOT}. This is the recommended
     * API — use with {@link FewShotExampleProvider#collectSnippetsIfFewShot}.
     *
     * @param snippets pre-rendered and budget-bounded code snippets (may be null or empty)
     * @return this builder
     */
    public PromptBuilder withFewShotSnippets(List<String> snippets) {
        this.fewShotSnippets = snippets;
        return this;
    }

    /** Adds pre-rendered code snippets to the user prompt as example test context. */
    public PromptBuilder withExistingTestSnippets(List<String> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return this;
        }
        StringBuilder builder = new StringBuilder("Existing tests:\n");
        for (String snippet : snippets) {
            builder.append("```java\n")
                    .append(snippet)
                    .append("\n```\n");
        }
        userSections.add(builder.toString());
        return this;
    }

    /** Applies the specified prompt technique to the user prompt. */
    public PromptBuilder withPromptTechnique(Properties.LlmPromptTechnique technique) {
        if (technique == null || technique == Properties.LlmPromptTechnique.NONE) {
            return this;
        }
        if (technique == Properties.LlmPromptTechnique.CHAIN_OF_THOUGHT) {
            userSections.add("Think step by step and then provide only final Java code.");
        }
        if (technique == Properties.LlmPromptTechnique.FEW_SHOT) {
            if (fewShotSnippets != null && !fewShotSnippets.isEmpty()) {
                withExistingTestSnippets(fewShotSnippets);
            } else if (fewShotExamples != null && !fewShotExamples.isEmpty()) {
                withExistingTests(fewShotExamples);
            }
        }
        return this;
    }

    public List<LlmMessage> build() {
        return buildWithMetadata().getMessages();
    }

    /**
     * Builds the prompt messages along with context metadata for trace recording.
     * Call sites that need to propagate context mode to LlmService should use this
     * instead of {@link #build()}.
     */
    public PromptResult buildWithMetadata() {
        String resolvedSystem = systemPrompt == null ? systemPromptProvider.getSystemPrompt() : systemPrompt;
        String userPrompt = String.join("\n\n", userSections);
        if (userPrompt.trim().isEmpty()) {
            userPrompt = "Generate one valid Java test method.";
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(resolvedSystem));
        messages.add(LlmMessage.user(userPrompt));
        return new PromptResult.Builder()
                .messages(messages)
                .sutContextMode(sutContextModeUsed)
                .contextUnavailable(sutContextUnavailable)
                .contextTruncated(sutContextTruncated)
                .contextCommentsStripped(sutContextCommentsStripped)
                .contextSelectivelyTruncated(sutContextSelectivelyTruncated)
                .clusterSummaryTruncated(clusterSummaryTruncated)
                .clusterSummaryChars(clusterSummaryChars)
                .dependencySummaryMetadata(dependencySummaryMetadata)
                .build();
    }

    /**
     * Returns the context mode used by the last {@link #withSutContext} call, or null if not called.
     */
    public Properties.LlmSutContextMode getSutContextModeUsed() {
        return sutContextModeUsed;
    }

    /** Returns true if context was unavailable (strict mode, no fallback). */
    public boolean isSutContextUnavailable() {
        return sutContextUnavailable;
    }
}
