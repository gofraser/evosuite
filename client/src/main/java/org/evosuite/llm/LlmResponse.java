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

/**
 * What a provider returned for one call: the text and the token usage.
 */
public final class LlmResponse {
    private final String text;
    private final int inputTokens;
    private final int cachedInputTokens;
    private final int outputTokens;

    /**
     * Constructs an LLM response with text, token usage counts, and the
     * portion of the input tokens served from a provider-side prompt
     * cache (0 when the provider does not report caching).
     */
    public LlmResponse(String text, int inputTokens, int cachedInputTokens, int outputTokens) {
        this.text = text;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
    }

    /**
     * Constructs an LLM response with text and token usage counts, with
     * zero cached input tokens.
     */
    public LlmResponse(String text, int inputTokens, int outputTokens) {
        this(text, inputTokens, 0, outputTokens);
    }

    /**
     * Creates an {@link LlmResponse} with only the response text and zero token counts.
     */
    public static LlmResponse fromText(String text) {
        return new LlmResponse(text, 0, 0, 0);
    }

    public String getText() {
        return text;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getCachedInputTokens() {
        return cachedInputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}
