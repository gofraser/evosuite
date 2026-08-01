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

    private final boolean infrastructureFailure;
    private final String infrastructureFailureReason;
    private final List<Diagnostic> diagnostics;
    private final LlmPostProcessingResponse response;
    private final PostProcessingCounts proposedCounts;
    private final List<RawAssertion> rawAssertions;
    private final List<AssertionParseEntry> assertionEntries;

    private LlmPostProcessingParseResult(LlmPostProcessingResponse response,
                                         PostProcessingCounts proposedCounts,
                                         List<RawAssertion> rawAssertions,
                                         List<AssertionParseEntry> assertionEntries,
                                         boolean infrastructureFailure,
                                         String infrastructureFailureReason,
                                         List<Diagnostic> diagnostics) {
        this.infrastructureFailure = infrastructureFailure;
        this.infrastructureFailureReason = infrastructureFailureReason;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        this.response = response;
        this.proposedCounts = proposedCounts == null ? PostProcessingCounts.none() : proposedCounts;
        this.rawAssertions = Collections.unmodifiableList(new ArrayList<>(
                rawAssertions == null ? Collections.<RawAssertion>emptyList() : rawAssertions));
        this.assertionEntries = Collections.unmodifiableList(new ArrayList<>(
                assertionEntries == null ? Collections.<AssertionParseEntry>emptyList() : assertionEntries));
    }

    public static LlmPostProcessingParseResult success(LlmPostProcessingResponse response,
                                                       List<Diagnostic> diagnostics) {
        return success(response, diagnostics, PostProcessingCounts.none(),
                Collections.<RawAssertion>emptyList());
    }

    public static LlmPostProcessingParseResult infrastructureFailure(String reason) {
        return new LlmPostProcessingParseResult(null, PostProcessingCounts.none(),
                Collections.<RawAssertion>emptyList(), Collections.<AssertionParseEntry>emptyList(), true, reason,
                Collections.<Diagnostic>emptyList());
    }

    public static LlmPostProcessingParseResult success(LlmPostProcessingResponse response,
                                                       List<Diagnostic> diagnostics,
                                                       PostProcessingCounts proposedCounts,
                                                       List<RawAssertion> rawAssertions) {
        return new LlmPostProcessingParseResult(response, proposedCounts, rawAssertions,
                entriesFrom(rawAssertions, response, diagnostics), false, null, diagnostics);
    }

    static LlmPostProcessingParseResult successWithEntries(LlmPostProcessingResponse response,
                                                           List<Diagnostic> diagnostics,
                                                           PostProcessingCounts proposedCounts,
                                                           List<AssertionParseEntry> assertionEntries) {
        List<RawAssertion> rawAssertions = new ArrayList<>();
        if (assertionEntries != null) {
            for (AssertionParseEntry entry : assertionEntries) {
                if (entry != null && entry.getRaw() != null) {
                    rawAssertions.add(entry.getRaw());
                }
            }
        }
        return new LlmPostProcessingParseResult(response, proposedCounts, rawAssertions,
                assertionEntries, false, null, diagnostics);
    }

    public LlmPostProcessingResponse getResponse() {
        return response;
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

    public PostProcessingCounts getProposedCounts() {
        return proposedCounts;
    }

    /** Decoded raw assertion entries retained for diagnostics and repair prompts. */
    public List<RawAssertion> getRawAssertions() {
        return rawAssertions;
    }

    /** Per-wire-entry parse state, including accepted proposal and diagnostics. */
    public List<AssertionParseEntry> getAssertionEntries() {
        return assertionEntries;
    }

    private static List<AssertionParseEntry> entriesFrom(
            List<RawAssertion> rawAssertions,
            LlmPostProcessingResponse response,
            List<Diagnostic> diagnostics) {
        if (rawAssertions == null || rawAssertions.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, LlmPostProcessingResponse.AssertionProposal> proposals = new LinkedHashMap<>();
        if (response != null) {
            for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
                if (proposal != null && proposal.getAssertionId() != null) {
                    proposals.put(proposal.getAssertionId(), proposal);
                }
            }
        }
        Map<Integer, List<Diagnostic>> diagnosticsByIndex = new LinkedHashMap<>();
        if (diagnostics != null) {
            for (Diagnostic diagnostic : diagnostics) {
                if (diagnostic != null && diagnostic.getAssertionIndex() >= 0) {
                    diagnosticsByIndex.computeIfAbsent(diagnostic.getAssertionIndex(),
                            ignored -> new ArrayList<>()).add(diagnostic);
                }
            }
        }
        List<AssertionParseEntry> result = new ArrayList<>();
        for (RawAssertion raw : rawAssertions) {
            LlmPostProcessingResponse.AssertionProposal proposal = raw == null
                    ? null : proposals.get(raw.getAssertionId());
            result.add(new AssertionParseEntry(raw, proposal,
                    diagnosticsByIndex.get(raw == null ? -1 : raw.getIndex())));
        }
        return result;
    }

    public static final class RawAssertion {
        private final int index;
        private final String assertionId;
        private final String rawJson;
        private final Map<String, Object> fields;

        public RawAssertion(String assertionId, String rawJson) {
            this(-1, assertionId, rawJson, Collections.<String, Object>emptyMap());
        }

        public RawAssertion(String assertionId, String rawJson, Map<String, Object> fields) {
            this(-1, assertionId, rawJson, fields);
        }

        public RawAssertion(int index, String assertionId, String rawJson,
                            Map<String, Object> fields) {
            this.index = index;
            this.assertionId = assertionId;
            this.rawJson = rawJson;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                    fields == null ? Collections.<String, Object>emptyMap() : fields));
        }

        public int getIndex() { return index; }
        public String getAssertionId() { return assertionId; }
        public String getRawJson() { return rawJson; }
        public Map<String, Object> getFields() { return fields; }
    }

    public static final class AssertionParseEntry {
        private final RawAssertion raw;
        private final LlmPostProcessingResponse.AssertionProposal proposal;
        private final List<Diagnostic> diagnostics;

        public AssertionParseEntry(RawAssertion raw,
                                   LlmPostProcessingResponse.AssertionProposal proposal,
                                   List<Diagnostic> diagnostics) {
            this.raw = raw;
            this.proposal = proposal;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(
                    diagnostics == null ? Collections.<Diagnostic>emptyList() : diagnostics));
        }

        public RawAssertion getRaw() { return raw; }

        public LlmPostProcessingResponse.AssertionProposal getProposal() { return proposal; }

        public List<Diagnostic> getDiagnostics() { return diagnostics; }
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

    /**
     * Stable reason used to decide whether an assertion rejection can be sent
     * through the bounded assertion-repair request.
     *
     * <p>The human-readable diagnostic is presentation data.  Repair policy
     * must not change because wording changes.</p>
     */
    public enum DiagnosticReason {
        STRUCTURAL,
        UNKNOWN_REFERENCE,
        DUPLICATE,
        LIMIT,
        UNSUPPORTED_KIND,
        SAFETY_POLICY,
        COMPILE_FAILURE,
        OBSERVED_EXECUTION_FAILURE,
        STABILITY_EXECUTION_FAILURE;

        private Repairability repairability() {
            switch (this) {
                case UNKNOWN_REFERENCE:
                case DUPLICATE:
                case LIMIT:
                case SAFETY_POLICY:
                case STABILITY_EXECUTION_FAILURE:
                    return Repairability.NON_REPAIRABLE;
                case STRUCTURAL:
                case UNSUPPORTED_KIND:
                case COMPILE_FAILURE:
                case OBSERVED_EXECUTION_FAILURE:
                default:
                    return Repairability.REPAIRABLE;
            }
        }
    }

    public static final class Diagnostic {
        private final DiagnosticCode code;
        private final String path;
        private final String message;
        private final DiagnosticReason reason;
        private final int assertionIndex;
        private final String assertionId;

        public Diagnostic(DiagnosticCode code, String path, String message) {
            this(code, path, message, reasonForCode(code));
        }

        public Diagnostic(DiagnosticCode code, String path, String message,
                          DiagnosticReason reason) {
            this(code, path, message, reason, assertionIndex(path), assertionId(path));
        }

        public static Diagnostic withReason(DiagnosticCode code, String path, String message,
                                            DiagnosticReason reason, int assertionIndex,
                                            String assertionId) {
            return new Diagnostic(code, path, message, reason, assertionIndex, assertionId);
        }

        private Diagnostic(DiagnosticCode code, String path, String message,
                           DiagnosticReason reason, int assertionIndex,
                           String assertionId) {
            this.code = code;
            this.path = path;
            this.message = message;
            this.reason = reason == null ? reasonForCode(code) : reason;
            this.assertionIndex = assertionIndex;
            this.assertionId = assertionId;
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

        public DiagnosticReason getReason() {
            return reason;
        }

        public Repairability getRepairability() {
            return reason.repairability();
        }

        public int getAssertionIndex() {
            return assertionIndex;
        }

        public String getAssertionId() {
            return assertionId;
        }

        private static int assertionIndex(String path) {
            String token = assertionToken(path);
            if (token == null) {
                return -1;
            }
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }

        private static String assertionId(String path) {
            String token = assertionToken(path);
            if (token == null) {
                return null;
            }
            try {
                Integer.parseInt(token);
                return null;
            } catch (NumberFormatException ignored) {
                return token;
            }
        }

        private static String assertionToken(String path) {
            if (path == null || !path.startsWith("assertions[")) {
                return null;
            }
            int start = "assertions[".length();
            int end = path.indexOf(']', start);
            return end <= start ? null : path.substring(start, end);
        }

        private static DiagnosticReason reasonForCode(DiagnosticCode code) {
            if (code == null) {
                return DiagnosticReason.STRUCTURAL;
            }
            switch (code) {
                case UNKNOWN_ID:
                    return DiagnosticReason.UNKNOWN_REFERENCE;
                case DUPLICATE:
                    return DiagnosticReason.DUPLICATE;
                case LIMIT_EXCEEDED:
                    return DiagnosticReason.LIMIT;
                case UNSUPPORTED_KIND:
                    return DiagnosticReason.UNSUPPORTED_KIND;
                case COMPILE:
                    return DiagnosticReason.COMPILE_FAILURE;
                case OBSERVED_EXECUTION:
                    return DiagnosticReason.OBSERVED_EXECUTION_FAILURE;
                case STABILITY_EXECUTION:
                    return DiagnosticReason.STABILITY_EXECUTION_FAILURE;
                case INVALID_FIELD:
                default:
                    return DiagnosticReason.STRUCTURAL;
            }
        }

    }
}
