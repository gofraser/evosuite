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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.prompt.SystemPromptProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and validates the bounded assertion-only repair request used by
 * unified post-processing.
 */
final class LlmAssertionRepairer {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private LlmAssertionRepairer() {
        // Utility class.
    }

    static List<RejectedAssertion> collectRepairableRejectedAssertions(
            String rawResponse,
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse) {
        Map<String, RejectedAssertion> byId = rawAssertionEntries(rawResponse);
        if (byId.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> parsedIds = assertionIds(parsedResponse);
        Set<String> acceptedIds = assertionIds(acceptedResponse);
        for (LlmPostProcessingResponse.AssertionProposal proposal : parsedResponse.getAssertions()) {
            if (!acceptedIds.contains(proposal.getAssertionId())) {
                RejectedAssertion candidate = byId.get(proposal.getAssertionId());
                if (candidate != null) {
                    candidate.addDiagnostic("Observed-scope validation rejected the assertion");
                }
            }
        }

        Map<Integer, List<LlmPostProcessingParseResult.Diagnostic>> diagnosticsByIndex =
                diagnosticsByAssertionIndex(parseResult);
        List<String> rawIds = new ArrayList<>(byId.keySet());
        for (Map.Entry<Integer, List<LlmPostProcessingParseResult.Diagnostic>> entry
                : diagnosticsByIndex.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= rawIds.size()) {
                continue;
            }
            String assertionId = rawIds.get(index);
            RejectedAssertion candidate = byId.get(assertionId);
            if (candidate == null || parsedIds.contains(assertionId)) {
                continue;
            }
            boolean repairable = true;
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : entry.getValue()) {
                if (!isRepairableDiagnostic(diagnostic)) {
                    repairable = false;
                }
                candidate.addDiagnostic(diagnostic.getPath() + ": " + diagnostic.getMessage());
            }
            if (!repairable) {
                candidate.markNonRepairable();
            }
        }

        List<RejectedAssertion> result = new ArrayList<>();
        for (RejectedAssertion candidate : byId.values()) {
            if (candidate.isRepairable()) {
                result.add(candidate);
            }
        }
        return result;
    }

    static List<LlmMessage> buildRepairMessages(LlmPostProcessingPromptContext context,
                                                List<RejectedAssertion> candidates) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(new SystemPromptProvider().getPostProcessingSystemPrompt()));
        messages.add(LlmMessage.user(repairPrompt(context, candidates)));
        return messages;
    }

    static LlmPostProcessingResponse parseRepairResponse(String rawResponse,
                                                         LlmPostProcessingResponseParser.ParseContext parseContext,
                                                         Set<String> repairableIds) {
        String response = normalizeRepairResponse(rawResponse);
        LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(response, parseContext);
        if (parseResult.isInfrastructureFailure()) {
            return new LlmPostProcessingResponse(LlmPostProcessingResponse.SUPPORTED_SCHEMA_VERSION);
        }
        LlmPostProcessingResponse filtered = new LlmPostProcessingResponse(
                LlmPostProcessingResponse.SUPPORTED_SCHEMA_VERSION);
        for (LlmPostProcessingResponse.AssertionProposal proposal
                : parseResult.getResponse().getAssertions()) {
            if (repairableIds.contains(proposal.getAssertionId())) {
                filtered.addAssertion(proposal);
            }
        }
        return filtered;
    }

    private static String repairPrompt(LlmPostProcessingPromptContext context,
                                       List<RejectedAssertion> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("Repair only the rejected assertion proposals below.\n");
        builder.append("Return JSON only, either an assertions array or ");
        builder.append("{\"schemaVersion\":1,\"assertions\":[...]}.\n");
        builder.append("Rules:\n");
        builder.append("- Return at most one corrected assertion for each input assertionId.\n");
        builder.append("- Preserve assertionId values exactly and do not introduce new IDs.\n");
        builder.append("- Do not change test names, variable names, comments, or section breaks.\n");
        builder.append("- Use only stable variable IDs and callable members listed below.\n");
        builder.append("- Return [] if no correction is justified.\n\n");
        builder.append("Rejected assertions:\n");
        for (RejectedAssertion candidate : candidates) {
            builder.append("- ").append(candidate.getAssertionId()).append(": ");
            builder.append(candidate.getRawJson()).append('\n');
            for (String diagnostic : candidate.getDiagnostics()) {
                builder.append("  diagnostic: ").append(diagnostic).append('\n');
            }
        }
        builder.append("\nObservations:\n");
        builder.append(context.toObservationText());
        builder.append("\nCallable members:\n");
        builder.append(context.toCallableMemberText());
        builder.append("\nSupported kinds: EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME\n");
        return builder.toString();
    }

    private static String normalizeRepairResponse(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("[")) {
            return "{\"schemaVersion\":1,\"assertions\":" + trimmed + "}";
        }
        return trimmed;
    }

    private static Map<String, RejectedAssertion> rawAssertionEntries(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(rawResponse);
            JsonNode assertions = root == null ? null : root.get("assertions");
            if (assertions == null || !assertions.isArray()) {
                return Collections.emptyMap();
            }
            Map<String, RejectedAssertion> result = new LinkedHashMap<>();
            for (JsonNode assertion : assertions) {
                if (assertion == null || !assertion.isObject()) {
                    continue;
                }
                JsonNode id = assertion.get("assertionId");
                if (id == null || !id.isTextual() || id.asText().trim().isEmpty()) {
                    continue;
                }
                String assertionId = id.asText().trim();
                result.put(assertionId, new RejectedAssertion(assertionId, assertion.toString()));
            }
            return result;
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    private static Set<String> assertionIds(LlmPostProcessingResponse response) {
        if (response == null || response.getAssertions().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
            result.add(proposal.getAssertionId());
        }
        return result;
    }

    private static Map<Integer, List<LlmPostProcessingParseResult.Diagnostic>> diagnosticsByAssertionIndex(
            LlmPostProcessingParseResult parseResult) {
        if (parseResult == null || parseResult.getDiagnostics().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, List<LlmPostProcessingParseResult.Diagnostic>> result = new LinkedHashMap<>();
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : parseResult.getDiagnostics()) {
            int index = assertionIndex(diagnostic.getPath());
            if (index < 0) {
                continue;
            }
            result.computeIfAbsent(index, ignored -> new ArrayList<>()).add(diagnostic);
        }
        return result;
    }

    private static int assertionIndex(String path) {
        if (path == null || !path.startsWith("assertions[")) {
            return -1;
        }
        int start = "assertions[".length();
        int end = path.indexOf(']', start);
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isRepairableDiagnostic(LlmPostProcessingParseResult.Diagnostic diagnostic) {
        return diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD
                || diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.UNSUPPORTED_KIND;
    }

    static final class RejectedAssertion {
        private final String assertionId;
        private final String rawJson;
        private final List<String> diagnostics = new ArrayList<>();
        private boolean nonRepairable;

        private RejectedAssertion(String assertionId, String rawJson) {
            this.assertionId = assertionId;
            this.rawJson = rawJson;
        }

        String getAssertionId() {
            return assertionId;
        }

        String getRawJson() {
            return rawJson;
        }

        List<String> getDiagnostics() {
            return Collections.unmodifiableList(diagnostics);
        }

        void addDiagnostic(String diagnostic) {
            if (diagnostic != null && !diagnostic.trim().isEmpty()) {
                diagnostics.add(diagnostic);
            }
        }

        void markNonRepairable() {
            nonRepairable = true;
        }

        boolean isRepairable() {
            return !nonRepairable && !diagnostics.isEmpty();
        }
    }
}
