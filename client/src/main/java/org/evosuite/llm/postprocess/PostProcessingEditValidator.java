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

import javax.lang.model.SourceVersion;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.Diagnostic;
import static org.evosuite.llm.postprocess.LlmPostProcessingParseResult.DiagnosticCode;

/**
 * Validates edits that refer to the finalized test but do not require Java
 * expression analysis.
 */
final class PostProcessingEditValidator {

    private static final Set<String> COMMENT_FIELDS = allowedFields("afterStatementId", "text");

    private PostProcessingEditValidator() {
        // Utility class.
    }

    static void validate(JsonNode root,
                         LlmPostProcessingResponseParser.ParseContext context,
                         LlmPostProcessingResponse response,
                         List<Diagnostic> diagnostics) {
        Validator validator = new Validator(context, response, diagnostics);
        validator.testName(root == null ? null : root.get("testName"));
        validator.variableNames(root == null ? null : root.get("variableNames"));
        validator.comments(root == null ? null : root.get("comments"));
        validator.sectionBreaks(root == null ? null : root.get("sectionBreaksAfter"));
    }

    private static Set<String> allowedFields(String... fields) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, fields);
        return Collections.unmodifiableSet(result);
    }

    private static final class Validator {
        private final LlmPostProcessingResponseParser.ParseContext context;
        private final LlmPostProcessingResponse response;
        private final List<Diagnostic> diagnostics;
        private final PostProcessingOptions options;

        private Validator(LlmPostProcessingResponseParser.ParseContext context,
                          LlmPostProcessingResponse response,
                          List<Diagnostic> diagnostics) {
            if (context == null) {
                throw new IllegalArgumentException("Post-processing parse context must not be null");
            }
            this.context = context;
            this.response = response;
            this.diagnostics = diagnostics;
            this.options = this.context.options();
        }

        private void testName(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isTextual()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "testName", "testName must be a string");
                return;
            }
            String name = node.asText().trim();
            if (!isValidJavaIdentifier(name)) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "testName",
                        "testName is not a valid Java identifier");
                return;
            }
            response.setTestName(name);
        }

        private void variableNames(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isObject()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "variableNames",
                        "variableNames must be an object");
                return;
            }
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String variableId = fieldNames.next();
                String path = "variableNames." + variableId;
                JsonNode value = node.get(variableId);
                if (context.knowsVariableIds() && !context.hasVariableId(variableId)) {
                    diagnostic(DiagnosticCode.UNKNOWN_ID, path, "Unknown variable ID: " + variableId);
                    continue;
                }
                if (value == null || !value.isTextual()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Variable name must be a string");
                    continue;
                }
                String name = value.asText().trim();
                if (!isValidJavaIdentifier(name)) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path,
                            "Variable name is not a valid Java identifier");
                    continue;
                }
                response.addVariableName(variableId, name);
            }
        }

        private void comments(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isArray()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "comments", "comments must be an array");
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            int accepted = 0;
            for (int i = 0; i < node.size(); i++) {
                String path = "comments[" + i + "]";
                if (accepted >= options.contextLimits().commentsPerTest()) {
                    diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path, "Comment limit exceeded");
                    break;
                }
                JsonNode entry = node.get(i);
                if (entry == null || !entry.isObject()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Comment entry must be an object");
                    continue;
                }
                reportUnknownFields(entry, path, COMMENT_FIELDS);
                String afterStatementId = text(entry.get("afterStatementId"));
                if (afterStatementId == null) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path + ".afterStatementId",
                            "afterStatementId must be a string");
                    continue;
                }
                if (context.knowsStatementIds() && !context.hasStatementId(afterStatementId)) {
                    diagnostic(DiagnosticCode.UNKNOWN_ID, path + ".afterStatementId",
                            "Unknown statement ID: " + afterStatementId);
                    continue;
                }
                String comment = sanitizeComment(text(entry.get("text")));
                if (comment == null || comment.isEmpty()) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path + ".text",
                            "Comment text must be non-empty");
                    continue;
                }
                if (comment.length() > options.contextLimits().commentChars()) {
                    diagnostic(DiagnosticCode.LIMIT_EXCEEDED, path + ".text",
                            "Comment text is too long");
                    continue;
                }
                String key = afterStatementId + "\n" + comment;
                if (!seen.add(key)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path, "Duplicate comment");
                    continue;
                }
                response.addComment(new LlmPostProcessingResponse.CommentProposal(afterStatementId, comment));
                accepted++;
            }
        }

        private void sectionBreaks(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isArray()) {
                diagnostic(DiagnosticCode.INVALID_FIELD, "sectionBreaksAfter",
                        "sectionBreaksAfter must be an array");
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                String path = "sectionBreaksAfter[" + i + "]";
                String statementId = text(node.get(i));
                if (statementId == null) {
                    diagnostic(DiagnosticCode.INVALID_FIELD, path, "Section break ID must be a string");
                    continue;
                }
                if (context.knowsStatementIds() && !context.hasStatementId(statementId)) {
                    diagnostic(DiagnosticCode.UNKNOWN_ID, path, "Unknown statement ID: " + statementId);
                    continue;
                }
                if (response.getSectionBreaksAfter().contains(statementId)) {
                    diagnostic(DiagnosticCode.DUPLICATE, path, "Duplicate section break");
                    continue;
                }
                response.addSectionBreakAfter(statementId);
            }
        }

        private void reportUnknownFields(JsonNode object, String path, Set<String> allowed) {
            Iterator<String> fieldNames = object.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (!allowed.contains(fieldName)) {
                    diagnostic(DiagnosticCode.INVALID_FIELD,
                            path.isEmpty() ? fieldName : path + "." + fieldName,
                            "Unknown field: " + fieldName);
                }
            }
        }

        private void diagnostic(DiagnosticCode code, String path, String message) {
            diagnostics.add(Diagnostic.withReason(code, path, message, null, -1, null));
        }

        private static String text(JsonNode node) {
            if (node == null || node.isNull() || !node.isTextual()) {
                return null;
            }
            return node.asText();
        }

        private static String sanitizeComment(String text) {
            return PostProcessingTextPolicy.sanitizeComment(text);
        }

        private static boolean isValidJavaIdentifier(String value) {
            if (value == null || value.isEmpty() || SourceVersion.isKeyword(value)
                    || !Character.isJavaIdentifierStart(value.charAt(0))) {
                return false;
            }
            for (int i = 1; i < value.length(); i++) {
                if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
