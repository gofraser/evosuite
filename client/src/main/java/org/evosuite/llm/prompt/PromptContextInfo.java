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

/**
 * Immutable description of the SUT context and dependency summary a prompt was built with, as
 * recorded in the LLM trace. It travels unchanged from the context factory and the dependency
 * summary through the {@link PromptBuilder} and {@link PromptResult} to the trace recorder.
 */
public final class PromptContextInfo {

    private static final PromptContextInfo EMPTY = new PromptContextInfo(null, false, false, false, false,
            false, 0, PromptResult.DependencySummaryMetadata.empty());

    private final Properties.LlmSutContextMode sutContextMode;
    private final boolean contextUnavailable;
    private final boolean contextTruncated;
    private final boolean contextCommentsStripped;
    private final boolean contextSelectivelyTruncated;
    private final boolean clusterSummaryTruncated;
    private final int clusterSummaryChars;
    private final PromptResult.DependencySummaryMetadata dependencySummaryMetadata;

    private PromptContextInfo(Properties.LlmSutContextMode sutContextMode, boolean contextUnavailable,
                              boolean contextTruncated, boolean contextCommentsStripped,
                              boolean contextSelectivelyTruncated, boolean clusterSummaryTruncated,
                              int clusterSummaryChars,
                              PromptResult.DependencySummaryMetadata dependencySummaryMetadata) {
        this.sutContextMode = sutContextMode;
        this.contextUnavailable = contextUnavailable;
        this.contextTruncated = contextTruncated;
        this.contextCommentsStripped = contextCommentsStripped;
        this.contextSelectivelyTruncated = contextSelectivelyTruncated;
        this.clusterSummaryTruncated = clusterSummaryTruncated;
        this.clusterSummaryChars = clusterSummaryChars;
        this.dependencySummaryMetadata = dependencySummaryMetadata == null
                ? PromptResult.DependencySummaryMetadata.empty() : dependencySummaryMetadata;
    }

    /** The info of a prompt without SUT context or dependency summary. */
    public static PromptContextInfo empty() {
        return EMPTY;
    }

    /**
     * Describes the SUT context only.
     *
     * @param sutContextMode mode actually used (may differ from the configured one after fallback)
     * @param contextUnavailable true if extraction failed and no fallback was available
     * @param contextTruncated true if the context was cut to {@code LLM_CONTEXT_MAX_CHARS}
     * @param contextCommentsStripped true if comments were stripped to fit the budget
     * @param contextSelectivelyTruncated true if selective method truncation was applied
     */
    public static PromptContextInfo ofSutContext(Properties.LlmSutContextMode sutContextMode,
                                                 boolean contextUnavailable, boolean contextTruncated,
                                                 boolean contextCommentsStripped,
                                                 boolean contextSelectivelyTruncated) {
        return new PromptContextInfo(sutContextMode, contextUnavailable, contextTruncated,
                contextCommentsStripped, contextSelectivelyTruncated, false, 0, null);
    }

    /**
     * Describes the dependency summary only.
     *
     * @param clusterSummaryTruncated true if the summary was cut to its character budget
     * @param clusterSummaryChars total characters before truncation
     * @param metadata budget and emission telemetry of the summary
     */
    public static PromptContextInfo ofDependencySummary(boolean clusterSummaryTruncated, int clusterSummaryChars,
                                                        PromptResult.DependencySummaryMetadata metadata) {
        return new PromptContextInfo(null, false, false, false, false, clusterSummaryTruncated,
                clusterSummaryChars, metadata);
    }

    /** This info with the SUT context fields of {@code sut}. */
    public PromptContextInfo withSutContextOf(PromptContextInfo sut) {
        return new PromptContextInfo(sut.sutContextMode, sut.contextUnavailable, sut.contextTruncated,
                sut.contextCommentsStripped, sut.contextSelectivelyTruncated, clusterSummaryTruncated,
                clusterSummaryChars, dependencySummaryMetadata);
    }

    /** This info with the dependency summary fields of {@code dependencies}. */
    public PromptContextInfo withDependencySummaryOf(PromptContextInfo dependencies) {
        return new PromptContextInfo(sutContextMode, contextUnavailable, contextTruncated,
                contextCommentsStripped, contextSelectivelyTruncated, dependencies.clusterSummaryTruncated,
                dependencies.clusterSummaryChars, dependencies.dependencySummaryMetadata);
    }

    /**
     * Context mode actually used (may differ from configured mode after fallback),
     * or null if no SUT context was requested.
     */
    public Properties.LlmSutContextMode getSutContextMode() {
        return sutContextMode;
    }

    /** True if context extraction failed and no fallback was available. */
    public boolean isContextUnavailable() {
        return contextUnavailable;
    }

    /** True if the SUT context was truncated to fit within {@code LLM_CONTEXT_MAX_CHARS}. */
    public boolean isContextTruncated() {
        return contextTruncated;
    }

    /** True if comments were stripped from the SUT context to fit within the char budget. */
    public boolean isContextCommentsStripped() {
        return contextCommentsStripped;
    }

    /** True if selective method truncation was applied to the SUT context. */
    public boolean isContextSelectivelyTruncated() {
        return contextSelectivelyTruncated;
    }

    /** True if the cluster dependency summary was truncated to fit within the char budget. */
    public boolean isClusterSummaryTruncated() {
        return clusterSummaryTruncated;
    }

    /** Total characters of the cluster summary before truncation (0 if not computed). */
    public int getClusterSummaryChars() {
        return clusterSummaryChars;
    }

    public PromptResult.DependencySummaryMetadata getDependencySummaryMetadata() {
        return dependencySummaryMetadata;
    }
}
