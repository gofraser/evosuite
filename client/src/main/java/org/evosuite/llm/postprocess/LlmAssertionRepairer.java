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

import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.prompt.SystemPromptProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and validates the bounded assertion-only repair request used by
 * unified post-processing.
 */
final class LlmAssertionRepairer {

    private static final Pattern VARIABLE_ID = Pattern.compile("\\bv\\d+\\b");

    private LlmAssertionRepairer() {
        // Utility class.
    }

    /** Structured-input entry point used by the production repair workflow. */
    static List<RejectedAssertion> collectRepairableRejectedAssertions(
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse,
            List<LlmPostProcessingParseResult.Diagnostic> validationDiagnostics) {
        if (parseResult == null || parseResult.getAssertionEntries().isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, RejectedAssertion> byId = new LinkedHashMap<>();
        Set<String> acceptedIds = assertionIds(acceptedResponse);
        for (LlmPostProcessingParseResult.AssertionParseEntry entry
                : parseResult.getAssertionEntries()) {
            LlmPostProcessingParseResult.RawAssertion raw = entry.getRaw();
            if (raw == null || raw.getAssertionId() == null) {
                continue;
            }
            RejectedAssertion candidate = byId.get(raw.getAssertionId());
            if (candidate == null) {
                candidate = new RejectedAssertion(raw.getAssertionId(), raw.getRawJson());
                byId.put(raw.getAssertionId(), candidate);
            } else {
                candidate.markNonRepairable();
            }
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : entry.getDiagnostics()) {
                candidate.addDiagnostic(diagnostic);
                if (!isRepairableDiagnostic(diagnostic)) {
                    candidate.markNonRepairable();
                }
            }
            if (entry.getProposal() != null && !acceptedIds.contains(raw.getAssertionId())) {
                List<LlmPostProcessingParseResult.Diagnostic> exactDiagnostics =
                        validationDiagnosticsFor(raw.getAssertionId(), validationDiagnostics);
                if (exactDiagnostics == null || exactDiagnostics.isEmpty()) {
                    candidate.addDiagnostic("Validation rejected the assertion");
                } else {
                    for (LlmPostProcessingParseResult.Diagnostic diagnostic : exactDiagnostics) {
                        candidate.addDiagnostic(diagnostic);
                        if (!isRepairableDiagnostic(diagnostic)) {
                            candidate.markNonRepairable();
                        }
                    }
                }
            }
        }
        if (parsedResponse != null) {
            for (LlmPostProcessingResponse.AssertionProposal proposal : parsedResponse.getAssertions()) {
                if (proposal == null || acceptedIds.contains(proposal.getAssertionId())
                        || byId.containsKey(proposal.getAssertionId())) {
                    continue;
                }
                RejectedAssertion candidate = new RejectedAssertion(
                        proposal.getAssertionId(), proposal.toString());
                candidate.addDiagnostic("Validation rejected the assertion");
                byId.put(proposal.getAssertionId(), candidate);
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

    static List<LlmMessage> buildRepairMessages(OracleContext context,
                                                List<RejectedAssertion> candidates,
                                                PostProcessingOptions options) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(new SystemPromptProvider().getPostProcessingSystemPrompt()));
        messages.add(LlmMessage.user(repairPrompt(context, candidates, options)));
        return messages;
    }

    static LlmPostProcessingParseResult parseRepairResponse(
            String rawResponse,
            LlmPostProcessingResponseParser.ParseContext parseContext,
            Set<String> repairableIds) {
        String response = normalizeRepairResponse(rawResponse);
        LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(response, parseContext);
        if (parseResult.isInfrastructureFailure()) {
            return parseResult;
        }
        LlmPostProcessingResponse filtered = new LlmPostProcessingResponse();
        for (LlmPostProcessingResponse.AssertionProposal proposal
                : parseResult.getResponse().getAssertions()) {
            if (repairableIds.contains(proposal.getAssertionId())) {
                filtered.addAssertion(proposal);
            }
        }
        List<LlmPostProcessingParseResult.AssertionParseEntry> entries = new ArrayList<>();
        for (LlmPostProcessingParseResult.AssertionParseEntry entry
                : parseResult.getAssertionEntries()) {
            LlmPostProcessingParseResult.RawAssertion raw = entry.getRaw();
            if (raw != null && repairableIds.contains(raw.getAssertionId())) {
                entries.add(new LlmPostProcessingParseResult.AssertionParseEntry(
                        raw, entry.getProposal(), entry.getDiagnostics()));
            }
        }
        return LlmPostProcessingParseResult.successWithEntries(filtered, parseResult.getDiagnostics(),
                parseResult.getProposedCounts(), entries);
    }

    private static String repairPrompt(OracleContext context,
                                       List<RejectedAssertion> candidates,
                                       PostProcessingOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Production repair rendering requires options");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Repair only the rejected assertion proposals below.\n");
        builder.append("Return JSON only, either an assertions array or ");
        builder.append("{\"schemaVersion\":")
                .append(LlmPostProcessingProtocol.responseSchemaVersion())
                .append(",\"assertions\":[...]}.\n");
        builder.append("Rules:\n");
        builder.append("- Return at most one corrected assertion for each input assertionId.\n");
        builder.append("- Preserve assertionId values exactly and do not introduce new IDs.\n");
        builder.append("- Do not change test names, variable names, comments, or section breaks.\n");
        builder.append("- Use only stable variable IDs and callable members listed below.\n");
        builder.append("- Return [] if no correction is justified.\n\n");
        builder.append("Rejected assertions:\n");
        appendRejectedAssertions(builder, candidates);
        Set<String> relevantVariableIds = relevantVariableIds(candidates);
        builder.append("\nObservations:\n");
        builder.append(PostProcessingPromptRenderer.observationText(
                context, relevantVariableIds, options));
        builder.append("\nCallable members:\n");
        builder.append(PostProcessingPromptRenderer.callableMemberText(
                context, relevantVariableIds, options));
        builder.append("\nSupported kinds: EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME, ");
        builder.append("CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY, ");
        builder.append("GREATER, LESS, GREATER_EQUALS, LESS_EQUALS\n");
        return builder.toString();
    }

    private static void appendRejectedAssertions(StringBuilder builder,
                                                 List<RejectedAssertion> candidates) {
        for (RejectedAssertion candidate : candidates) {
            builder.append("- ").append(candidate.getAssertionId()).append(": ");
            builder.append(candidate.getRawJson()).append('\n');
            for (String diagnostic : candidate.getDiagnostics()) {
                builder.append("  diagnostic: ").append(diagnostic).append('\n');
            }
            for (String correction : candidate.getCorrections()) {
                builder.append("  correction: ").append(correction).append('\n');
            }
        }
    }

    private static Set<String> relevantVariableIds(List<RejectedAssertion> candidates) {
        Set<String> relevantVariableIds = new LinkedHashSet<>();
        for (RejectedAssertion candidate : candidates) {
            relevantVariableIds.addAll(candidate.getRelevantVariableIds());
        }
        return relevantVariableIds;
    }

    private static String normalizeRepairResponse(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        String trimmed = LlmPostProcessingResponseParser.normalizeJsonResponse(rawResponse).trim();
        if (trimmed.startsWith("[")) {
            return "{\"schemaVersion\":" + LlmPostProcessingProtocol.responseSchemaVersion()
                    + ",\"assertions\":" + trimmed + "}";
        }
        return trimmed;
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

    private static List<LlmPostProcessingParseResult.Diagnostic> validationDiagnosticsFor(
            String assertionId, List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
        if (assertionId == null || diagnostics == null || diagnostics.isEmpty()) {
            return Collections.emptyList();
        }
        List<LlmPostProcessingParseResult.Diagnostic> result = new ArrayList<>();
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
            if (diagnostic != null && assertionId.equals(diagnostic.getAssertionId())) {
                result.add(diagnostic);
            }
        }
        return result;
    }

    private static boolean isRepairableDiagnostic(LlmPostProcessingParseResult.Diagnostic diagnostic) {
        return diagnostic != null
                && diagnostic.getRepairability()
                == LlmPostProcessingParseResult.Repairability.REPAIRABLE;
    }

    static final class RejectedAssertion {
        private final String assertionId;
        private final String rawJson;
        private final List<String> diagnostics = new ArrayList<>();
        private final Set<String> corrections = new LinkedHashSet<>();
        private final Set<String> relevantVariableIds = new LinkedHashSet<>();
        private boolean nonRepairable;

        private RejectedAssertion(String assertionId, String rawJson) {
            this.assertionId = assertionId;
            this.rawJson = rawJson;
            collectVariableIds(rawJson, relevantVariableIds);
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

        Set<String> getCorrections() {
            return Collections.unmodifiableSet(corrections);
        }

        Set<String> getRelevantVariableIds() {
            return Collections.unmodifiableSet(relevantVariableIds);
        }

        void addDiagnostic(String diagnostic) {
            if (diagnostic != null && !diagnostic.trim().isEmpty()) {
                diagnostics.add(diagnostic);
                collectVariableIds(diagnostic, relevantVariableIds);
            }
        }

        void addDiagnostic(LlmPostProcessingParseResult.Diagnostic diagnostic) {
            if (diagnostic == null) {
                return;
            }
            addDiagnostic(diagnostic.getPath() + ": " + diagnostic.getMessage());
            corrections.add(correctionTemplate(diagnostic));
        }

        void markNonRepairable() {
            nonRepairable = true;
        }

        boolean isRepairable() {
            return !nonRepairable && !diagnostics.isEmpty();
        }
    }

    private static void collectVariableIds(String text, Set<String> destination) {
        if (text == null) {
            return;
        }
        Matcher matcher = VARIABLE_ID.matcher(text);
        while (matcher.find()) {
            destination.add(matcher.group());
        }
    }

    private static String correctionTemplate(LlmPostProcessingParseResult.Diagnostic diagnostic) {
        String message = diagnostic.getMessage() == null ? "" : diagnostic.getMessage().toLowerCase();
        String path = diagnostic.getPath() == null ? "" : diagnostic.getPath();
        switch (diagnostic.getCode()) {
            case UNSUPPORTED_KIND:
                return "Replace kind with one supported by the listed operand forms; preserve assertionId.";
            case COMPILE:
                return "Rewrite only the expression named by the compiler diagnostic using listed variables and exact Java types.";
            case OBSERVED_EXECUTION:
                return "Correct the expected value from the stable observation, or return no repair if the observation does not justify it.";
            case INVALID_FIELD:
                if (message.contains("type") || message.contains("compatible") || message.contains("numeric")
                        || message.contains("boolean") || message.contains("reference")) {
                    return "Replace the named operand with an expression of the exact required type shown in observations.";
                }
                if (message.contains("delta")) {
                    return "Use a non-negative numeric delta only for floating-point equality; otherwise omit delta.";
                }
                if (message.contains("expression") || message.contains("parse")) {
                    return "Replace the named field with one side-effect-free Java expression string and no semicolon.";
                }
                return "Correct exactly the field named by the diagnostic and keep all valid fields unchanged.";
            default:
                return "Correct exactly the field named by the diagnostic; do not alter unrelated fields.";
        }
    }
}
