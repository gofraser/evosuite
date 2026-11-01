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
        this.response = response == null ? new LlmPostProcessingResponse() : response.copy();
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(
                diagnostics == null
                        ? Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                        : diagnostics));
    }

    static ValidatedEditPlan create(LlmPostProcessingResponse response,
                                    List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
        return new ValidatedEditPlan(response, diagnostics);
    }

    static ValidatedEditPlan success(LlmPostProcessingResponse response) {
        return create(response, Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList());
    }

    static ValidatedEditPlan rejectedAll(
            LlmPostProcessingResponse response,
            LlmPostProcessingParseResult.DiagnosticCode code,
            String message) {
        List<LlmPostProcessingParseResult.Diagnostic> diagnostics = new ArrayList<>();
        for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
            LlmPostProcessingParseResult.DiagnosticReason reason =
                    code == LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION
                            ? LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY : null;
            diagnostics.add(new LlmPostProcessingParseResult.Diagnostic(code,
                    "assertions[" + proposal.getAssertionId() + "]", message, reason));
        }
        return create(response.withoutAssertions(), diagnostics);
    }

    public LlmPostProcessingResponse getResponse() {
        return response.copy();
    }

    public List<LlmPostProcessingParseResult.Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
