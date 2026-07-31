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
 */
package org.evosuite.llm.postprocess;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.Diagnostic;
import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.DiagnosticCode;

/**
 * Validates assertion placement and the availability of referenced values at
 * that placement.
 */
final class PostProcessingPlacementValidator {

    private static final Set<String> PLACEMENT_FIELDS = allowedFields(
            "site", "afterStatementId", "exceptionId");

    private PostProcessingPlacementValidator() {
        // Utility class.
    }

    static PlacementValue parse(JsonNode node,
                                String path,
                                LlmPostProcessingResponse response,
                                LlmPostProcessingResponseParser.ParseContext context,
                                LlmPostProcessingResponseParser.SelectableCandidate candidate,
                                int assertionIndex,
                                String assertionId,
                                List<Diagnostic> diagnostics) {
        if (node == null || node.isNull()) {
            if (response.getSchemaVersion() >= 3 && context.hasThrowingStatement()) {
                if (candidate != null && candidate.getDefaultSite() != null) {
                    return new PlacementValue(candidate.getDefaultSite(),
                            candidate.getDefaultAfterStatementId(), candidate.getDefaultExceptionId());
                }
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path,
                        "Throwing-test assertions require an advertised placement site",
                        assertionIndex, assertionId);
                return null;
            }
            return PlacementValue.endOfTest();
        }
        if (!node.isObject()) {
            diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path,
                    "Assertion placement must be an object", assertionIndex, assertionId);
            return null;
        }
        reportUnknownFields(node, path, assertionIndex, assertionId, diagnostics);
        if (response.getSchemaVersion() <= 2) {
            String legacyAfter = text(node.get("afterStatementId"));
            if (legacyAfter == null || legacyAfter.trim().isEmpty()) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".afterStatementId",
                        "afterStatementId must be a non-empty string", assertionIndex, assertionId);
                return null;
            }
            if (context.knowsStatementIds() && !context.hasStatementId(legacyAfter.trim())) {
                diagnostic(diagnostics, DiagnosticCode.UNKNOWN_ID, path + ".afterStatementId",
                        "Unknown statement ID: " + legacyAfter.trim(), assertionIndex, assertionId);
                return null;
            }
            return PlacementValue.endOfTest();
        }

        String siteText = text(node.get("site"));
        LlmPostProcessingResponse.AssertionSite site;
        try {
            site = siteText == null ? null
                    : LlmPostProcessingResponse.AssertionSite.valueOf(siteText.trim());
        } catch (IllegalArgumentException error) {
            site = null;
        }
        if (site == null) {
            diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".site",
                    "Unknown or missing assertion site", assertionIndex, assertionId);
            return null;
        }
        String afterStatementId = text(node.get("afterStatementId"));
        String exceptionId = text(node.get("exceptionId"));
        if (!context.hasThrowingStatement()) {
            if (site != LlmPostProcessingResponse.AssertionSite.END_OF_TEST) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".site",
                        "Non-throwing tests only advertise END_OF_TEST", assertionIndex, assertionId);
                return null;
            }
            return PlacementValue.endOfTest();
        }
        if (site == LlmPostProcessingResponse.AssertionSite.END_OF_TEST) {
            diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".site",
                    "END_OF_TEST is unavailable for a throwing test", assertionIndex, assertionId);
            return null;
        }
        if (site == LlmPostProcessingResponse.AssertionSite.BEFORE_TRY) {
            if (afterStatementId == null || afterStatementId.trim().isEmpty()
                    || !context.hasStatementId(afterStatementId.trim())
                    || stableIdIndex(afterStatementId.trim(), 's')
                    >= stableIdIndex(context.throwingStatementId(), 's')) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".afterStatementId",
                        "BEFORE_TRY requires an advertised pre-throw statement",
                        assertionIndex, assertionId);
                return null;
            }
            return new PlacementValue(site, afterStatementId.trim(), null);
        }
        if (site == LlmPostProcessingResponse.AssertionSite.IN_CATCH) {
            if (!"e0".equals(exceptionId)) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".exceptionId",
                        "IN_CATCH requires the advertised exceptionId e0", assertionIndex, assertionId);
                return null;
            }
            return new PlacementValue(site, null, "e0");
        }
        return new PlacementValue(site, null, null);
    }

    static boolean referencesAreAvailable(String expected, String actual, String delta,
                                          PlacementValue placement,
                                          LlmPostProcessingResponseParser.ParseContext context,
                                          String path,
                                          int assertionIndex,
                                          String assertionId,
                                          List<Diagnostic> diagnostics) {
        int statementIndex;
        if (placement.site == LlmPostProcessingResponse.AssertionSite.BEFORE_TRY) {
            statementIndex = stableIdIndex(placement.afterStatementId, 's');
        } else if (placement.site == LlmPostProcessingResponse.AssertionSite.IN_CATCH
                || placement.site == LlmPostProcessingResponse.AssertionSite.AFTER_CATCH) {
            statementIndex = stableIdIndex(context.throwingStatementId(), 's') - 1;
        } else {
            return true;
        }
        if (statementIndex < 0) {
            return true;
        }
        Set<String> variables = new LinkedHashSet<>();
        variables.addAll(LlmPostProcessingExpressionUtils.extractSymbolicVariables(expected));
        variables.addAll(LlmPostProcessingExpressionUtils.extractSymbolicVariables(actual));
        variables.addAll(LlmPostProcessingExpressionUtils.extractSymbolicVariables(delta));
        for (String variableId : variables) {
            if ("e0".equals(variableId)) {
                if (placement.site != LlmPostProcessingResponse.AssertionSite.IN_CATCH) {
                    diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".placement",
                            "Caught exception e0 is available only IN_CATCH",
                            assertionIndex, assertionId);
                    return false;
                }
                continue;
            }
            int variableIndex = stableIdIndex(variableId, 'v');
            if (variableIndex > statementIndex) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD, path + ".placement",
                        "Assertion placement references a variable created after the placement statement",
                        assertionIndex, assertionId);
                return false;
            }
        }
        return true;
    }

    static final class PlacementValue {
        final LlmPostProcessingResponse.AssertionSite site;
        final String afterStatementId;
        final String exceptionId;

        private PlacementValue(LlmPostProcessingResponse.AssertionSite site,
                               String afterStatementId, String exceptionId) {
            this.site = site;
            this.afterStatementId = afterStatementId;
            this.exceptionId = exceptionId;
        }

        private static PlacementValue endOfTest() {
            return new PlacementValue(LlmPostProcessingResponse.AssertionSite.END_OF_TEST,
                    null, null);
        }
    }

    private static Set<String> allowedFields(String... fields) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, fields);
        return Collections.unmodifiableSet(result);
    }

    private static void reportUnknownFields(JsonNode object, String path,
                                            int assertionIndex, String assertionId,
                                            List<Diagnostic> diagnostics) {
        Iterator<String> fieldNames = object.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!PLACEMENT_FIELDS.contains(fieldName)) {
                diagnostic(diagnostics, DiagnosticCode.INVALID_FIELD,
                        path.isEmpty() ? fieldName : path + "." + fieldName,
                        "Unknown field: " + fieldName, assertionIndex, assertionId);
            }
        }
    }

    private static void diagnostic(List<Diagnostic> diagnostics, DiagnosticCode code,
                                   String path, String message,
                                   int assertionIndex, String assertionId) {
        diagnostics.add(new Diagnostic(code, path, message, null,
                assertionIndex, assertionId));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    private static int stableIdIndex(String id, char prefix) {
        if (id == null || id.length() < 2 || id.charAt(0) != prefix) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
