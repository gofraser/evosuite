/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable boundary object for one decoder pass.
 *
 * <p>The response contained here is decoded wire data. It is intentionally
 * separate from the execution-validated plan used by the processor, while
 * the legacy parse-result getters remain available to replay callers.</p>
 */
public final class DecodedPostProcessingResponse {

    private final LlmPostProcessingResponse response;
    private final PostProcessingCounts proposedCounts;
    private final List<LlmPostProcessingParseResult.RawAssertion> rawAssertions;

    DecodedPostProcessingResponse(LlmPostProcessingResponse response,
                                   PostProcessingCounts proposedCounts,
                                   List<LlmPostProcessingParseResult.RawAssertion> rawAssertions) {
        this.response = response;
        this.proposedCounts = proposedCounts == null ? PostProcessingCounts.none() : proposedCounts;
        this.rawAssertions = Collections.unmodifiableList(new ArrayList<>(
                rawAssertions == null
                        ? Collections.<LlmPostProcessingParseResult.RawAssertion>emptyList()
                        : rawAssertions));
    }

    public LlmPostProcessingResponse getResponse() {
        return response;
    }

    public PostProcessingCounts getProposedCounts() {
        return proposedCounts;
    }

    public List<LlmPostProcessingParseResult.RawAssertion> getRawAssertions() {
        return rawAssertions;
    }
}
