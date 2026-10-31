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
import org.evosuite.Properties.LlmSutContextMode;

import java.util.Objects;

/**
 * Immutable snapshot of every setting that decides what text the prompt and context
 * collaborators render for the class under test.
 *
 * <p>{@link PromptBuilder}, {@link SutContextProviderFactory}, {@link TestClusterSummarizer} and
 * {@link ClusterGrowthDelta} read these values from the snapshot they are given and never from
 * {@link Properties}; {@link #fromProperties()} is the only place that does. The process-scoped
 * caches ({@link SutContextProviderFactory}, {@link StaticPromptContext}) include the snapshot in
 * their keys, so two runs in one JVM with different settings never share an entry, and a setting
 * changed mid-JVM cannot serve text rendered under the old value.
 *
 * <p>{@link FewShotExampleProvider}, {@link CoverageGoalFormatter}, {@link DecompiledContextProvider}
 * and {@link SourceCodeProvider} are given the snapshot too, so one prompt is rendered from one
 * set of values. A setting changed while a run is in progress is therefore seen by the next
 * snapshot only: a builder, formatter or context lookup that already holds a snapshot keeps
 * the old value, where these helpers used to re-read {@link Properties} on every call (the
 * few-shot limits and archive strategy, the prompt technique, the goal format, the decompiler
 * timeout and the source path).
 *
 * <p>Value semantics: two snapshots are equal exactly when they would render the same text.
 */
public final class PromptSettings {

    private final LlmSutContextMode sutContextMode;
    private final int contextMaxChars;
    private final String sourcePath;
    private final boolean contextFallbackEnabled;
    private final int decompilerTimeoutSeconds;
    private final boolean promptCacheStaticContext;
    private final int clusterSummaryMaxChars;
    private final boolean clusterSummaryDynamicScaling;
    private final int clusterSummaryDynamicMaxChars;
    private final double clusterSummaryDynamicRatio;
    private final int clusterSummaryDynamicMinChars;
    private final int clusterSummaryPerClassSoftCapChars;
    private final boolean clusterSummaryCompactSignatures;
    private final int existingTestsMaxCharsTotal;
    private final int existingTestsMaxCharsPerTest;
    private final boolean annotateExistingTests;
    private final boolean fewShotUseParsedJunit;
    private final boolean fewShotUseArchive;
    private final int fewShotMaxExamples;
    private final Properties.LlmFewShotArchiveStrategy fewShotArchiveStrategy;
    private final int fewShotMaxCharsTotal;
    private final int fewShotMaxCharsPerExample;
    private final Properties.LlmGoalFormat goalFormat;
    private final Properties.LlmPromptTechnique promptTechnique;

    private PromptSettings(Builder b) {
        this.sutContextMode = b.sutContextMode;
        this.contextMaxChars = b.contextMaxChars;
        this.sourcePath = b.sourcePath == null ? "" : b.sourcePath.trim();
        this.contextFallbackEnabled = b.contextFallbackEnabled;
        this.decompilerTimeoutSeconds = b.decompilerTimeoutSeconds;
        this.promptCacheStaticContext = b.promptCacheStaticContext;
        this.clusterSummaryMaxChars = b.clusterSummaryMaxChars;
        this.clusterSummaryDynamicScaling = b.clusterSummaryDynamicScaling;
        this.clusterSummaryDynamicMaxChars = b.clusterSummaryDynamicMaxChars;
        this.clusterSummaryDynamicRatio = b.clusterSummaryDynamicRatio;
        this.clusterSummaryDynamicMinChars = b.clusterSummaryDynamicMinChars;
        this.clusterSummaryPerClassSoftCapChars = b.clusterSummaryPerClassSoftCapChars;
        this.clusterSummaryCompactSignatures = b.clusterSummaryCompactSignatures;
        this.existingTestsMaxCharsTotal = b.existingTestsMaxCharsTotal;
        this.existingTestsMaxCharsPerTest = b.existingTestsMaxCharsPerTest;
        this.annotateExistingTests = b.annotateExistingTests;
        this.fewShotUseParsedJunit = b.fewShotUseParsedJunit;
        this.fewShotUseArchive = b.fewShotUseArchive;
        this.fewShotMaxExamples = b.fewShotMaxExamples;
        this.fewShotArchiveStrategy = b.fewShotArchiveStrategy;
        this.fewShotMaxCharsTotal = b.fewShotMaxCharsTotal;
        this.fewShotMaxCharsPerExample = b.fewShotMaxCharsPerExample;
        this.goalFormat = b.goalFormat;
        this.promptTechnique = b.promptTechnique;
    }

    /** Snapshots the current values of the prompt and context settings in {@link Properties}. */
    public static PromptSettings fromProperties() {
        return builderFromProperties().build();
    }

    /** A builder pre-filled with the current values in {@link Properties}, for varying one setting. */
    public static Builder builderFromProperties() {
        Builder b = new Builder();
        b.sutContextMode = Properties.LLM_SUT_CONTEXT_MODE;
        b.contextMaxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        b.sourcePath = Properties.LLM_SOURCE_PATH;
        b.contextFallbackEnabled = Properties.LLM_CONTEXT_FALLBACK_ENABLED;
        b.decompilerTimeoutSeconds = Properties.LLM_DECOMPILER_TIMEOUT_SECONDS;
        b.promptCacheStaticContext = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;
        b.clusterSummaryMaxChars = Properties.LLM_CLUSTER_SUMMARY_MAX_CHARS;
        b.clusterSummaryDynamicScaling = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING;
        b.clusterSummaryDynamicMaxChars = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS;
        b.clusterSummaryDynamicRatio = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO;
        b.clusterSummaryDynamicMinChars = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS;
        b.clusterSummaryPerClassSoftCapChars = Properties.LLM_CLUSTER_SUMMARY_PER_CLASS_SOFT_CAP_CHARS;
        b.clusterSummaryCompactSignatures = Properties.LLM_CLUSTER_SUMMARY_COMPACT_SIGNATURES;
        b.existingTestsMaxCharsTotal = Properties.LLM_EXISTING_TESTS_MAX_CHARS_TOTAL;
        b.existingTestsMaxCharsPerTest = Properties.LLM_EXISTING_TESTS_MAX_CHARS_PER_TEST;
        b.annotateExistingTests = Properties.LLM_ANNOTATE_EXISTING_TESTS;
        b.fewShotUseParsedJunit = Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT;
        b.fewShotUseArchive = Properties.LLM_FEW_SHOT_USE_ARCHIVE;
        b.fewShotMaxExamples = Properties.LLM_FEW_SHOT_MAX_EXAMPLES;
        b.fewShotArchiveStrategy = Properties.LLM_FEW_SHOT_ARCHIVE_STRATEGY;
        b.fewShotMaxCharsTotal = Properties.LLM_FEW_SHOT_MAX_CHARS_TOTAL;
        b.fewShotMaxCharsPerExample = Properties.LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE;
        b.goalFormat = Properties.LLM_GOAL_FORMAT;
        b.promptTechnique = Properties.LLM_PROMPT_TECHNIQUE;
        return b;
    }

    public LlmSutContextMode sutContextMode() {
        return sutContextMode;
    }

    public int contextMaxChars() {
        return contextMaxChars;
    }

    public boolean contextFallbackEnabled() {
        return contextFallbackEnabled;
    }

    public boolean promptCacheStaticContext() {
        return promptCacheStaticContext;
    }

    int clusterSummaryPerClassSoftCapChars() {
        return clusterSummaryPerClassSoftCapChars;
    }

    boolean clusterSummaryCompactSignatures() {
        return clusterSummaryCompactSignatures;
    }

    int existingTestsMaxCharsTotal() {
        return existingTestsMaxCharsTotal;
    }

    int existingTestsMaxCharsPerTest() {
        return existingTestsMaxCharsPerTest;
    }

    boolean annotateExistingTests() {
        return annotateExistingTests;
    }

    boolean fewShotUseParsedJunit() {
        return fewShotUseParsedJunit;
    }

    boolean fewShotUseArchive() {
        return fewShotUseArchive;
    }

    int fewShotMaxExamples() {
        return fewShotMaxExamples;
    }

    Properties.LlmFewShotArchiveStrategy fewShotArchiveStrategy() {
        return fewShotArchiveStrategy;
    }

    int fewShotMaxCharsTotal() {
        return fewShotMaxCharsTotal;
    }

    int fewShotMaxCharsPerExample() {
        return fewShotMaxCharsPerExample;
    }

    Properties.LlmGoalFormat goalFormat() {
        return goalFormat;
    }

    Properties.LlmPromptTechnique promptTechnique() {
        return promptTechnique;
    }

    int decompilerTimeoutSeconds() {
        return decompilerTimeoutSeconds;
    }

    String sourcePath() {
        return sourcePath;
    }

    /**
     * The character budget of the dependency summary and how it was derived. With dynamic
     * scaling the budget follows the context limit; an unlimited context falls back to the
     * dynamic cap, then to the static budget.
     */
    BudgetDecision dependencySummaryBudget() {
        if (!clusterSummaryDynamicScaling) {
            return new BudgetDecision(clusterSummaryMaxChars, "legacy_static");
        }
        if (contextMaxChars <= 0) {
            // Context is unlimited/unknown: use conservative dynamic guardrail if configured.
            if (clusterSummaryDynamicMaxChars > 0) {
                return new BudgetDecision(clusterSummaryDynamicMaxChars, "dynamic_max_for_unbounded_context");
            }
            return new BudgetDecision(clusterSummaryMaxChars, "legacy_static_for_unbounded_context");
        }

        int scaled = (int) Math.round(contextMaxChars * clusterSummaryDynamicRatio);
        int bounded = Math.max(scaled, clusterSummaryDynamicMinChars);
        if (clusterSummaryDynamicMaxChars > 0) {
            bounded = Math.min(bounded, clusterSummaryDynamicMaxChars);
        }
        return new BudgetDecision(bounded, "dynamic_scaled");
    }

    /** A dependency-summary budget and the rule that produced it. */
    static final class BudgetDecision {
        final int budgetChars;
        final String mode;

        BudgetDecision(int budgetChars, String mode) {
            this.budgetChars = budgetChars;
            this.mode = mode;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PromptSettings)) {
            return false;
        }
        PromptSettings that = (PromptSettings) o;
        return sutContextMode == that.sutContextMode
                && contextMaxChars == that.contextMaxChars
                && sourcePath.equals(that.sourcePath)
                && contextFallbackEnabled == that.contextFallbackEnabled
                && decompilerTimeoutSeconds == that.decompilerTimeoutSeconds
                && promptCacheStaticContext == that.promptCacheStaticContext
                && clusterSummaryMaxChars == that.clusterSummaryMaxChars
                && clusterSummaryDynamicScaling == that.clusterSummaryDynamicScaling
                && clusterSummaryDynamicMaxChars == that.clusterSummaryDynamicMaxChars
                && Double.compare(clusterSummaryDynamicRatio, that.clusterSummaryDynamicRatio) == 0
                && clusterSummaryDynamicMinChars == that.clusterSummaryDynamicMinChars
                && clusterSummaryPerClassSoftCapChars == that.clusterSummaryPerClassSoftCapChars
                && clusterSummaryCompactSignatures == that.clusterSummaryCompactSignatures
                && existingTestsMaxCharsTotal == that.existingTestsMaxCharsTotal
                && existingTestsMaxCharsPerTest == that.existingTestsMaxCharsPerTest
                && annotateExistingTests == that.annotateExistingTests
                && fewShotUseParsedJunit == that.fewShotUseParsedJunit
                && fewShotUseArchive == that.fewShotUseArchive
                && fewShotMaxExamples == that.fewShotMaxExamples
                && fewShotArchiveStrategy == that.fewShotArchiveStrategy
                && fewShotMaxCharsTotal == that.fewShotMaxCharsTotal
                && fewShotMaxCharsPerExample == that.fewShotMaxCharsPerExample
                && goalFormat == that.goalFormat
                && promptTechnique == that.promptTechnique;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sutContextMode, contextMaxChars, sourcePath, contextFallbackEnabled,
                decompilerTimeoutSeconds, promptCacheStaticContext, clusterSummaryMaxChars,
                clusterSummaryDynamicScaling, clusterSummaryDynamicMaxChars, clusterSummaryDynamicRatio,
                clusterSummaryDynamicMinChars, clusterSummaryPerClassSoftCapChars,
                clusterSummaryCompactSignatures, existingTestsMaxCharsTotal, existingTestsMaxCharsPerTest,
                annotateExistingTests, fewShotUseParsedJunit, fewShotUseArchive, fewShotMaxExamples,
                fewShotArchiveStrategy, fewShotMaxCharsTotal, fewShotMaxCharsPerExample, goalFormat,
                promptTechnique);
    }

    /** Fluent builder; start from {@link #builderFromProperties()}. */
    public static final class Builder {
        private LlmSutContextMode sutContextMode;
        private int contextMaxChars;
        private String sourcePath;
        private boolean contextFallbackEnabled;
        private int decompilerTimeoutSeconds;
        private boolean promptCacheStaticContext;
        private int clusterSummaryMaxChars;
        private boolean clusterSummaryDynamicScaling;
        private int clusterSummaryDynamicMaxChars;
        private double clusterSummaryDynamicRatio;
        private int clusterSummaryDynamicMinChars;
        private int clusterSummaryPerClassSoftCapChars;
        private boolean clusterSummaryCompactSignatures;
        private int existingTestsMaxCharsTotal;
        private int existingTestsMaxCharsPerTest;
        private boolean annotateExistingTests;
        private boolean fewShotUseParsedJunit;
        private boolean fewShotUseArchive;
        private int fewShotMaxExamples;
        private Properties.LlmFewShotArchiveStrategy fewShotArchiveStrategy;
        private int fewShotMaxCharsTotal;
        private int fewShotMaxCharsPerExample;
        private Properties.LlmGoalFormat goalFormat;
        private Properties.LlmPromptTechnique promptTechnique;

        private Builder() {
        }

        public Builder sutContextMode(LlmSutContextMode value) {
            this.sutContextMode = value;
            return this;
        }

        public Builder contextMaxChars(int value) {
            this.contextMaxChars = value;
            return this;
        }

        public Builder contextFallbackEnabled(boolean value) {
            this.contextFallbackEnabled = value;
            return this;
        }

        public Builder promptCacheStaticContext(boolean value) {
            this.promptCacheStaticContext = value;
            return this;
        }

        public Builder clusterSummaryCompactSignatures(boolean value) {
            this.clusterSummaryCompactSignatures = value;
            return this;
        }

        public Builder clusterSummaryMaxChars(int value) {
            this.clusterSummaryMaxChars = value;
            return this;
        }

        public Builder sourcePath(String value) {
            this.sourcePath = value;
            return this;
        }

        public Builder decompilerTimeoutSeconds(int value) {
            this.decompilerTimeoutSeconds = value;
            return this;
        }

        public Builder goalFormat(Properties.LlmGoalFormat value) {
            this.goalFormat = value;
            return this;
        }

        public Builder promptTechnique(Properties.LlmPromptTechnique value) {
            this.promptTechnique = value;
            return this;
        }

        public PromptSettings build() {
            return new PromptSettings(this);
        }
    }
}
