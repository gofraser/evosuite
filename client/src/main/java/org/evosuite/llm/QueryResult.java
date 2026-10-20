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
package org.evosuite.llm;

import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TargetedResponse;

/**
 * Text plus the token usage and latency of the call that produced it.
 * Returned by {@link LlmService#queryWithUsage(PromptResult, LlmFeature)} for
 * callers that need to attribute usage rather than just consume the text.
 */
public final class QueryResult {
    private final String text;
    private final TargetedResponse.CallUsage usage;

    public QueryResult(String text, int inputTokens, int cachedInputTokens, int outputTokens, long latencyMs) {
        this(text, new TargetedResponse.CallUsage(inputTokens, cachedInputTokens, outputTokens, latencyMs));
    }

    public QueryResult(String text, TargetedResponse.CallUsage usage) {
        this.text = text;
        this.usage = usage == null ? TargetedResponse.CallUsage.NONE : usage;
    }

    public String getText() {
        return text;
    }

    /** The usage of the call that produced {@link #getText()}, as one value shared with the response mapping. */
    public TargetedResponse.CallUsage getUsage() {
        return usage;
    }

    public int getInputTokens() {
        return usage.getInputTokens();
    }

    public int getCachedInputTokens() {
        return usage.getCachedInputTokens();
    }

    public int getOutputTokens() {
        return usage.getOutputTokens();
    }

    public long getLatencyMs() {
        return usage.getLatencyMs();
    }
}
