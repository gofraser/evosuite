/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.llm.prompt.PromptResult;

/**
 * Small facade for fresh post-processing prompt rendering.
 *
 * <p>Historical prompt variants are no longer regenerated here. Stored replay
 * prompts and provenance identifiers remain available through their existing
 * trace data.</p>
 */
final class LlmPostProcessingPromptBuilder {

    private LlmPostProcessingPromptBuilder() {
        // Utility class.
    }

    static PromptResult build(LlmPostProcessingPromptContext context, int testIndex,
                              boolean assertionsEnabled, PostProcessingOptions options) {
        if (context == null) {
            throw new IllegalArgumentException("Prompt context must not be null");
        }
        return PostProcessingPromptRenderer.build(
                OracleContext.from(context), assertionsEnabled, options);
    }

    static PromptResult build(OracleContext context, int testIndex,
                              boolean assertionsEnabled, PostProcessingOptions options) {
        return PostProcessingPromptRenderer.build(context, assertionsEnabled, options);
    }
}
