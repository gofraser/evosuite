/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of structural, expression, compile, and observed-execution
 * validation for one response.
 */
public final class ValidatedEditPlan {

    private final LlmPostProcessingResponse response;
    private final List<LlmPostProcessingParseResult.Diagnostic> diagnostics;

    private ValidatedEditPlan(LlmPostProcessingResponse response,
                              List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
        this.response = response == null ? new LlmPostProcessingResponse(
                LlmPostProcessingResponse.SUPPORTED_SCHEMA_VERSION) : response.copy();
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(
                diagnostics == null
                        ? Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                        : diagnostics));
    }

    static ValidatedEditPlan create(LlmPostProcessingResponse response,
                                    List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
        return new ValidatedEditPlan(response, diagnostics);
    }

    public LlmPostProcessingResponse getResponse() {
        return response.copy();
    }

    public List<LlmPostProcessingParseResult.Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
