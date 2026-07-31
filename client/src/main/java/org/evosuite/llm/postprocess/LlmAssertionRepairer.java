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

    static List<RejectedAssertion> collectRepairableRejectedAssertions(
            String rawResponse,
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse,
            List<LlmPostProcessingParseResult.Diagnostic> validationDiagnostics) {
        return collectRepairableRejectedAssertions(parseResult, parsedResponse, acceptedResponse,
                validationDiagnostics);
    }

    /** Structured-input entry point used by the production repair workflow. */
    static List<RejectedAssertion> collectRepairableRejectedAssertions(
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse,
            List<LlmPostProcessingParseResult.Diagnostic> validationDiagnostics) {
        Map<String, RejectedAssertion> byId = rawAssertionEntries(parseResult);
        if (byId.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> parsedIds = assertionIds(parsedResponse);
        Set<String> acceptedIds = assertionIds(acceptedResponse);
        Map<String, List<LlmPostProcessingParseResult.Diagnostic>> validationById =
                diagnosticsByAssertionId(validationDiagnostics);
        for (LlmPostProcessingResponse.AssertionProposal proposal : parsedResponse.getAssertions()) {
            if (!acceptedIds.contains(proposal.getAssertionId())) {
                RejectedAssertion candidate = byId.get(proposal.getAssertionId());
                if (candidate != null) {
                    List<LlmPostProcessingParseResult.Diagnostic> exactDiagnostics =
                            validationById.get(proposal.getAssertionId());
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
                candidate.addDiagnostic(diagnostic);
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
        return buildRepairMessages(context, candidates, null);
    }

    static List<LlmMessage> buildRepairMessages(LlmPostProcessingPromptContext context,
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
        LlmPostProcessingResponse filtered = new LlmPostProcessingResponse(
                LlmPostProcessingResponse.SUPPORTED_SCHEMA_VERSION);
        for (LlmPostProcessingResponse.AssertionProposal proposal
                : parseResult.getResponse().getAssertions()) {
            if (repairableIds.contains(proposal.getAssertionId())) {
                filtered.addAssertion(proposal);
            }
        }
        return LlmPostProcessingParseResult.success(filtered, parseResult.getDiagnostics(),
                parseResult.getProposedCounts(), parseResult.getRawAssertions());
    }

    private static String repairPrompt(LlmPostProcessingPromptContext context,
                                       List<RejectedAssertion> candidates,
                                       PostProcessingOptions options) {
        org.evosuite.Properties.LlmPostProcessingPromptVariant variant = options == null
                ? org.evosuite.Properties.LLM_POSTPROCESSING_PROMPT_VARIANT
                : org.evosuite.Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
        PromptVariantCapabilities capabilities = PromptVariantCapabilities.forVariant(variant);
        StringBuilder builder = new StringBuilder();
        builder.append("Repair only the rejected assertion proposals below.\n");
        builder.append("Return JSON only, either an assertions array or ");
        builder.append("{\"schemaVersion\":")
                .append(LlmPostProcessingProtocol.responseSchemaVersion())
                .append(",\"assertions\":[...]}.\n");
        builder.append("Rules:\n");
        builder.append("- Return at most one corrected assertion for each input assertionId.\n");
        builder.append("- Preserve assertionId values exactly and do not introduce new IDs.\n");
        builder.append("- Preserve the semantic placement site. If a placement diagnostic identifies its JSON shape or a missing required afterStatementId/exceptionId, correct that representation or required field; otherwise do not change site, afterStatementId, or exceptionId.\n");
        if (capabilities.hasExceptionAdjacentPlacements()) {
            builder.append("- placement must be a JSON object with a site field, never a string or a type field. Valid forms are {\"site\":\"END_OF_TEST\"}, {\"site\":\"BEFORE_TRY\",\"afterStatementId\":\"sN\"}, {\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}, and {\"site\":\"AFTER_CATCH\"}; use only a site advertised for this test.\n");
        }
        builder.append("- Do not change test names, variable names, comments, or section breaks.\n");
        builder.append("- Use only stable variable IDs and callable members listed below.\n");
        builder.append("- Return [] if no correction is justified.\n\n");
        if (capabilities.hasLiteralDiscipline()) {
            builder.append("Literal reminders:\n");
            builder.append("- JSON encodes a Java String expression such as \"text\" as \"\\\"text\\\"\".\n");
            builder.append("- Preserve char escapes, numeric suffixes (L/F/D), NaN/infinity constants, and one-dimensional array syntax.\n\n");
        }
        builder.append("Rejected assertions:\n");
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
        Set<String> relevantVariableIds = new LinkedHashSet<>();
        for (RejectedAssertion candidate : candidates) {
            relevantVariableIds.addAll(candidate.getRelevantVariableIds());
        }
        builder.append("\nObservations:\n");
        builder.append(context.toObservationText(relevantVariableIds));
        builder.append("\nCallable members:\n");
        builder.append(context.toCallableMemberText(relevantVariableIds));
        if (capabilities.hasExceptionAdjacentPlacements()) {
            builder.append("\nSafe assertion sites:\n");
            builder.append(context.toSafeAssertionSiteText());
        }
        builder.append("\nSupported kinds: EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME, ");
        builder.append("CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY, ");
        builder.append("GREATER, LESS, GREATER_EQUALS, LESS_EQUALS\n");
        return builder.toString();
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

    private static Map<String, RejectedAssertion> rawAssertionEntries(
            LlmPostProcessingParseResult parseResult) {
        if (parseResult == null || parseResult.getRawAssertions().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, RejectedAssertion> result = new LinkedHashMap<>();
        for (LlmPostProcessingParseResult.RawAssertion assertion : parseResult.getRawAssertions()) {
            if (assertion != null && assertion.getAssertionId() != null) {
                result.put(assertion.getAssertionId(), new RejectedAssertion(
                        assertion.getAssertionId(), assertion.getRawJson()));
            }
        }
        return result;
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
            int index = diagnostic.getAssertionIndex();
            if (index < 0) {
                continue;
            }
            result.computeIfAbsent(index, ignored -> new ArrayList<>()).add(diagnostic);
        }
        return result;
    }

    private static Map<String, List<LlmPostProcessingParseResult.Diagnostic>> diagnosticsByAssertionId(
            List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<LlmPostProcessingParseResult.Diagnostic>> result = new LinkedHashMap<>();
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.getAssertionId() == null) {
                continue;
            }
            result.computeIfAbsent(diagnostic.getAssertionId(), ignored -> new ArrayList<>())
                    .add(diagnostic);
        }
        return result;
    }

    private static boolean isRepairableDiagnostic(LlmPostProcessingParseResult.Diagnostic diagnostic) {
        if (diagnostic == null
                || diagnostic.getRepairability()
                != LlmPostProcessingParseResult.Repairability.REPAIRABLE) {
            return false;
        }
        return diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD
                || diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.UNSUPPORTED_KIND
                || diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.COMPILE
                || diagnostic.getCode() == LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION;
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
                if (path.contains(".placement")) {
                    return "Encode placement as an object with a valid site field and the required afterStatementId or exceptionId for that same semantic site.";
                }
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
