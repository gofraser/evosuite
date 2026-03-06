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
import org.evosuite.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of {@link PromptBuilder#buildWithMetadata()},
 * carrying both the chat messages and context metadata for trace recording.
 */
public final class PromptResult {

    private final List<LlmMessage> messages;
    private final Properties.LlmSutContextMode sutContextMode;
    private final boolean contextUnavailable;
    private final boolean contextTruncated;
    private final boolean contextCommentsStripped;
    private final boolean contextSelectivelyTruncated;
    private final boolean clusterSummaryTruncated;
    private final int clusterSummaryChars;

    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable) {
        this(messages, sutContextMode, contextUnavailable, false, false, false, false, 0);
    }

    /** Constructs an immutable prompt result with associated context metadata. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable,
                        boolean contextTruncated) {
        this(messages, sutContextMode, contextUnavailable, contextTruncated, false, false, false, 0);
    }

    /** Constructs an immutable prompt result with full context and cluster summary metadata. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable,
                        boolean contextTruncated,
                        boolean clusterSummaryTruncated,
                        int clusterSummaryChars) {
        this(messages, sutContextMode, contextUnavailable, contextTruncated, false, false,
                clusterSummaryTruncated, clusterSummaryChars);
    }

    /** Constructs an immutable prompt result with full context, comment-stripping, and cluster summary metadata. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable,
                        boolean contextTruncated,
                        boolean contextCommentsStripped,
                        boolean clusterSummaryTruncated,
                        int clusterSummaryChars) {
        this(messages, sutContextMode, contextUnavailable, contextTruncated, contextCommentsStripped,
                false, clusterSummaryTruncated, clusterSummaryChars);
    }

    /** Constructs an immutable prompt result with all metadata including selective truncation. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable,
                        boolean contextTruncated,
                        boolean contextCommentsStripped,
                        boolean contextSelectivelyTruncated,
                        boolean clusterSummaryTruncated,
                        int clusterSummaryChars) {
        this.messages = messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        this.sutContextMode = sutContextMode;
        this.contextUnavailable = contextUnavailable;
        this.contextTruncated = contextTruncated;
        this.contextCommentsStripped = contextCommentsStripped;
        this.contextSelectivelyTruncated = contextSelectivelyTruncated;
        this.clusterSummaryTruncated = clusterSummaryTruncated;
        this.clusterSummaryChars = clusterSummaryChars;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    /**
     * Context mode actually used (may differ from configured mode after fallback),
     * or null if withSutContext was not called.
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
}
