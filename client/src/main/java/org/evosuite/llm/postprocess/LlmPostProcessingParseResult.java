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
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Result of parsing a unified LLM post-processing response.
 */
public final class LlmPostProcessingParseResult {

    private final LlmPostProcessingResponse response;
    private final boolean infrastructureFailure;
    private final String infrastructureFailureReason;
    private final List<Diagnostic> diagnostics;
    private final PostProcessingCounts proposedCounts;
    private final List<RawAssertion> rawAssertions;
    private final DecodedPostProcessingResponse decodedResponse;

    private LlmPostProcessingParseResult(LlmPostProcessingResponse response,
                                         boolean infrastructureFailure,
                                         String infrastructureFailureReason,
                                         List<Diagnostic> diagnostics,
                                         PostProcessingCounts proposedCounts,
                                         List<RawAssertion> rawAssertions) {
        this.response = response;
        this.infrastructureFailure = infrastructureFailure;
        this.infrastructureFailureReason = infrastructureFailureReason;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        this.proposedCounts = proposedCounts == null ? PostProcessingCounts.none() : proposedCounts;
        this.rawAssertions = Collections.unmodifiableList(new ArrayList<>(
                rawAssertions == null ? Collections.<RawAssertion>emptyList() : rawAssertions));
        this.decodedResponse = response == null ? null : new DecodedPostProcessingResponse(
                response, this.proposedCounts, this.rawAssertions);
    }

    public static LlmPostProcessingParseResult success(LlmPostProcessingResponse response,
                                                       List<Diagnostic> diagnostics) {
        return new LlmPostProcessingParseResult(response, false, null, diagnostics,
                PostProcessingCounts.none(), Collections.<RawAssertion>emptyList());
    }

    public static LlmPostProcessingParseResult infrastructureFailure(String reason) {
        return new LlmPostProcessingParseResult(null, true, reason, Collections.<Diagnostic>emptyList(),
                PostProcessingCounts.none(), Collections.<RawAssertion>emptyList());
    }

    public static LlmPostProcessingParseResult success(LlmPostProcessingResponse response,
                                                       List<Diagnostic> diagnostics,
                                                       PostProcessingCounts proposedCounts,
                                                       List<RawAssertion> rawAssertions) {
        return new LlmPostProcessingParseResult(response, false, null, diagnostics,
                proposedCounts, rawAssertions);
    }

    public LlmPostProcessingResponse getResponse() {
        return response;
    }

    /** Return the immutable decoded wire state retained by this parse result. */
    public DecodedPostProcessingResponse getDecodedResponse() {
        return decodedResponse;
    }

    public boolean isInfrastructureFailure() {
        return infrastructureFailure;
    }

    public String getInfrastructureFailureReason() {
        return infrastructureFailureReason;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public PostProcessingCounts getProposedCounts() { return proposedCounts; }

    /** Decoded raw assertion entries retained for diagnostics and repair prompts. */
    public List<RawAssertion> getRawAssertions() { return rawAssertions; }

    public static final class RawAssertion {
        private final String assertionId;
        private final String rawJson;
        private final Map<String, Object> fields;

        public RawAssertion(String assertionId, String rawJson) {
            this(assertionId, rawJson, Collections.<String, Object>emptyMap());
        }

        public RawAssertion(String assertionId, String rawJson, Map<String, Object> fields) {
            this.assertionId = assertionId;
            this.rawJson = rawJson;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                    fields == null ? Collections.<String, Object>emptyMap() : fields));
        }

        public String getAssertionId() { return assertionId; }
        public String getRawJson() { return rawJson; }
        public Map<String, Object> getFields() { return fields; }
    }

    public enum DiagnosticCode {
        UNKNOWN_ID,
        DUPLICATE,
        INVALID_FIELD,
        UNSUPPORTED_KIND,
        LIMIT_EXCEEDED,
        COMPILE,
        OBSERVED_EXECUTION,
        STABILITY_EXECUTION
    }

    /** Typed repair decision attached to a diagnostic at decode/validation time. */
    public enum Repairability {
        REPAIRABLE,
        NON_REPAIRABLE
    }

    public static final class Diagnostic {
        private final DiagnosticCode code;
        private final String path;
        private final String message;
        private final Repairability repairability;

        public Diagnostic(DiagnosticCode code, String path, String message) {
            this(code, path, message, classifyRepairability(code, message));
        }

        public Diagnostic(DiagnosticCode code, String path, String message,
                          Repairability repairability) {
            this.code = code;
            this.path = path;
            this.message = message;
            this.repairability = repairability == null
                    ? classifyRepairability(code, message) : repairability;
        }

        public DiagnosticCode getCode() {
            return code;
        }

        public String getPath() {
            return path;
        }

        public String getMessage() {
            return message;
        }

        public Repairability getRepairability() {
            return repairability;
        }

        private static Repairability classifyRepairability(DiagnosticCode code, String message) {
            if (code == DiagnosticCode.UNKNOWN_ID
                    || code == DiagnosticCode.DUPLICATE
                    || code == DiagnosticCode.LIMIT_EXCEEDED
                    || code == DiagnosticCode.STABILITY_EXECUTION) {
                return Repairability.NON_REPAIRABLE;
            }
            String normalized = message == null ? "" : message.toLowerCase();
            if (normalized.contains("not listed") || normalized.contains("not allowlisted")
                    || normalized.contains("not callable") || normalized.contains("purity")
                    || normalized.contains("unknown variable") || normalized.contains("unknown candidate")
                    || normalized.contains("could not be bound") || normalized.contains("outside the test")
                    || normalized.contains("has no variable") || normalized.contains("final scope")) {
                return Repairability.NON_REPAIRABLE;
            }
            if (code == DiagnosticCode.COMPILE
                    && (normalized.contains("defined in an inaccessible class or interface")
                    || normalized.contains("cannot be accessed from outside package")
                    || (normalized.contains("type class") && normalized.contains("does not take parameters")))) {
                return Repairability.NON_REPAIRABLE;
            }
            return Repairability.REPAIRABLE;
        }
    }
}
