/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.SystemPromptProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the sole fresh-request protocol.
 */
final class PostProcessingPromptRenderer {

    private PostProcessingPromptRenderer() {
        // Utility class.
    }

    static PromptResult build(OracleContext context,
                              boolean assertionsEnabled,
                              PostProcessingOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Production prompt rendering requires options");
        }
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(new SystemPromptProvider().getPostProcessingSystemPrompt()));
        messages.add(LlmMessage.user(userPrompt(context, assertionsEnabled, options)));
        return new PromptResult.Builder().messages(messages).build();
    }

    private static String userPrompt(OracleContext context,
                                     boolean assertionsEnabled,
                                     PostProcessingOptions options) {
        boolean testNames = options.features().testNames();
        boolean variableNames = options.features().variableNames();
        boolean comments = options.features().comments();
        boolean sectionBreaks = options.features().sectionBreaks();
        StringBuilder builder = new StringBuilder();
        builder.append("Prompt version: ").append(options.promptVersion()).append('\n');
        builder.append("Target class: ").append(nullToEmpty(options.targetClass())).append('\n');
        builder.append("Enabled fields:");
        appendEnabledField(builder, "testName", testNames);
        appendEnabledField(builder, "variableNames", variableNames);
        appendEnabledField(builder, "comments", comments);
        appendEnabledField(builder, "sectionBreaksAfter", sectionBreaks);
        appendEnabledField(builder, "assertions", assertionsEnabled);
        builder.append("\n\n");
        builder.append("Return this JSON shape with schemaVersion ")
                .append(options.responseSchemaVersion()).append(":\n");
        appendResponseShape(builder, assertionsEnabled, testNames, variableNames,
                comments, sectionBreaks);
        builder.append("\n\n");
        if (assertionsEnabled) {
            appendAssertionExamples(builder, context);
            builder.append("\n\n");
        }
        builder.append("Rules:\n");
        builder.append("- Use only the statement ids sN and variable ids vN shown below.\n");
        if (testNames || variableNames) {
            builder.append("- Variable and test names must be Java identifiers and not Java keywords.\n");
        }
        if (variableNames) {
            builder.append("- Propose names for every nameable vN whose current rendered name is generic, type-only, or numeric-suffixed.\n");
            builder.append("- Variable names must describe the variable's role in this test, using constructor arguments, method-call receivers/arguments, observations, and later use sites.\n");
            builder.append("- The variableNames object should normally include every current rendered name ending in digits; leave one out only when renaming it would be misleading or unsafe.\n");
            builder.append("- Do not merely remove leading zeroes or keep type-only names such as object0, string1, actionEvent2, map3, list4, or variants with numeric suffixes.\n");
            builder.append("- If no precise domain role is clear, still prefer a suffix-free type/role fallback such as gui, event, source, value, result, input, output, collection, list, map, array, expectedValue, or actualValue over the current numeric EvoSuite name.\n");
            builder.append("- For multiple variables of the same type, choose distinct role names; keep a numeric suffix only as a last resort to avoid a real collision.\n");
        }
        if (comments) {
            builder.append("- Add comments only when they clarify test intent, logical phases, non-obvious setup, or why a strange generated value matters.\n");
            builder.append("- Do not comment obvious constructor calls, primitive assignments, or method calls by restating the code.\n");
            builder.append("- Prefer no comment over a redundant comment.\n");
            builder.append("- Use at most one comment per logical phase; use sectionBreaksAfter for visual grouping without prose.\n");
        }
        if (sectionBreaks) {
            builder.append("- Add sectionBreaksAfter only between logical phases such as setup, action, and verification.\n");
            builder.append("- Add a section break only when it would noticeably improve readability; if unsure, omit it.\n");
            builder.append("- Prefer section breaks over comments when visual grouping is enough.\n");
            builder.append("- Do not add section breaks after every statement or inside a short linear setup.\n");
            builder.append("- Do not add a trailing section break after the final meaningful statement.\n");
            builder.append("- Omit sectionBreaksAfter for very short tests unless there is a clear phase boundary.\n");
        }
        if (assertionsEnabled) {
            appendAssertionRules(builder, options);
        } else {
            builder.append("- Assertions are disabled for this test; omit the assertions field.\n");
        }
        builder.append("- Omit fields you do not want to change; empty arrays/objects are allowed.\n\n");
        builder.append("Generated test statements:\n");
        builder.append(annotatedText(context));
        builder.append("\nExceptions:\n");
        builder.append(exceptionText(context));
        builder.append("\nEvoSuite-observed candidate facts:\n");
        builder.append(candidateFactText(context, true, options));
        builder.append("\nObservations:\n");
        builder.append(observationText(context, null, options));
        builder.append("\nCallable members:\n");
        builder.append(callableMemberText(context, null, options));
        return builder.toString();
    }

    static String annotatedText(OracleContext context) {
        StringBuilder builder = new StringBuilder();
        for (OracleContext.StatementContext statement : context.getStatements()) {
            builder.append(statement.getStatementId());
            if (statement.getVariableId() != null) {
                builder.append(" ").append(statement.getVariableId());
            }
            if (statement.getDeclaredType() != null) {
                builder.append(" : ").append(statement.getDeclaredType());
            }
            if (statement.getRuntimeType() != null
                    && !statement.getRuntimeType().equals(statement.getDeclaredType())) {
                builder.append(" runtime=").append(statement.getRuntimeType());
            }
            builder.append(" | ").append(statement.getCode());
            if (!statement.getCode().endsWith("\n")) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    static String observationText(OracleContext context,
                                  Set<String> relevantVariableIds,
                                  PostProcessingOptions options) {
        if (context.getObservations().isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = options.contextLimits().observationChars();
        for (OracleContext.Observation observation : context.getObservations()) {
            if (relevantVariableIds != null
                    && (observation.getVariableId() == null
                    || !relevantVariableIds.contains(observation.getVariableId()))) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(observation.getStatementId());
            if (observation.getVariableId() != null) {
                line.append(" ").append(observation.getVariableId());
            }
            line.append(" provenance=").append(observation.getProvenance());
            line.append(" complete=").append(observation.isComplete());
            line.append(" value=").append(observation.getValue());
            if (!observation.isComplete()) {
                line.append(" note=truncated");
            }
            line.append('\n');
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                String truncationLine = "truncated=true\n";
                if (builder.length() + truncationLine.length() <= maxChars) {
                    builder.append(truncationLine);
                }
                break;
            }
            builder.append(line);
        }
        return builder.length() == 0 ? "none\n" : builder.toString();
    }

    static String exceptionText(OracleContext context) {
        if (context.getExceptions().isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (OracleContext.ExceptionContext exception : context.getExceptions()) {
            builder.append(exception.getStatementId());
            builder.append(" type=").append(exception.getType());
            builder.append(" explicit=").append(exception.isExplicit());
            if (exception.getMessage() != null) {
                builder.append(" message=").append(exception.getMessage());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    static String candidateFactText(OracleContext context,
                                    boolean includeCandidateIds,
                                    PostProcessingOptions options) {
        if (context.getCandidateFacts().isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = options.contextLimits().candidateChars();
        int emitted = 0;
        for (OracleContext.CandidateFact fact : context.getCandidateFacts()) {
            String line = candidateFactLine(fact, includeCandidateIds);
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                appendCandidateTruncation(builder, context.getCandidateFacts().size() - emitted, maxChars);
                break;
            }
            builder.append(line);
            emitted++;
        }
        return builder.toString();
    }

    static String callableMemberText(OracleContext context,
                                     Set<String> relevantVariableIds,
                                     PostProcessingOptions options) {
        return renderCallableMembers(context, relevantVariableIds, options).text;
    }

    private static CallableMemberRendering renderCallableMembers(
            OracleContext context,
            Set<String> relevantVariableIds,
        PostProcessingOptions options) {
        if (context.getCallableMembers().isEmpty()) {
            return new CallableMemberRendering("none\n");
        }
        Map<String, LinkedHashSet<String>> membersByType = new LinkedHashMap<>();
        LinkedHashSet<String> receiverBindings = new LinkedHashSet<>();
        for (OracleContext.CallableMember member : context.getCallableMembers()) {
            if (relevantVariableIds != null
                    && (member.getReceiverId() == null
                    || !relevantVariableIds.contains(member.getReceiverId()))) {
                continue;
            }
            LinkedHashSet<String> typeMembers = membersByType.get(member.getOwnerType());
            if (typeMembers == null) {
                typeMembers = new LinkedHashSet<>();
                membersByType.put(member.getOwnerType(), typeMembers);
            }
            typeMembers.add(member.getSignature() + "->" + member.getReturnType());
            if (member.getReceiverId() != null) {
                receiverBindings.add(member.getReceiverId() + "->" + member.getOwnerType());
            }
        }
        int maxChars = options.contextLimits().callableChars();
        StringBuilder builder = new StringBuilder();
        if (!receiverBindings.isEmpty()) {
            appendCapped(builder, "receivers: " + String.join(", ", receiverBindings) + "\n", maxChars);
        }
        int truncatedTypes = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : membersByType.entrySet()) {
            String line = "owner=" + entry.getKey() + " members=" + String.join(", ", entry.getValue()) + "\n";
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                truncatedTypes++;
                continue;
            }
            builder.append(line);
        }
        if (truncatedTypes > 0) {
            appendCapped(builder, "truncatedCallableTypes=" + truncatedTypes + "\n", maxChars);
        }
        return new CallableMemberRendering(builder.length() == 0 ? "none\n" : builder.toString());
    }

    static final class CallableMemberRendering {
        final String text;

        private CallableMemberRendering(String text) {
            this.text = text;
        }
    }

    private static String candidateFactLine(OracleContext.CandidateFact fact,
                                            boolean includeCandidateIds) {
        StringBuilder builder = new StringBuilder();
        boolean selectable = OracleContext.isCandidateSelectable(fact);
        if (includeCandidateIds && selectable) {
            builder.append("candidateId=").append(fact.getCandidateId()).append(' ');
        }
        builder.append(fact.getStatementId());
        if (fact.getSourceId() != null) {
            builder.append(" source=").append(fact.getSourceId());
        }
        if (!fact.getReferencedIds().isEmpty()) {
            builder.append(" refs=").append(String.join(",", fact.getReferencedIds()));
        }
        builder.append(" kind=").append(fact.getKind());
        if (fact.getObservedValue() != null) {
            builder.append(" observed=").append(fact.getObservedValue());
        }
        builder.append('\n');
        return builder.toString();
    }

    private static void appendCapped(StringBuilder builder, String line, int maxChars) {
        if (maxChars <= 0 || builder.length() + line.length() <= maxChars) {
            builder.append(line);
        }
    }

    private static void appendCandidateTruncation(StringBuilder builder, int truncatedCount, int maxChars) {
        String truncationLine = "truncatedCandidates=" + Math.max(0, truncatedCount) + "\n";
        if (maxChars <= 0 || builder.length() + truncationLine.length() <= maxChars) {
            builder.append(truncationLine);
        }
    }

    private static void appendAssertionExamples(StringBuilder builder,
                                                OracleContext context) {
        builder.append("Assertion object forms:\n");
        builder.append("- TRUE/FALSE/NULL/NOT_NULL: assertionId, kind, actual; never expected.\n");
        builder.append("- EQUALS/NOT_EQUALS/SAME/NOT_SAME and comparisons: assertionId, kind, expected, actual.\n");
        builder.append("- Floating EQUALS/NOT_EQUALS additionally accepts delta; scalar JSON values are normalized to expression strings.\n");
        for (OracleContext.CandidateFact fact : context.getCandidateFacts()) {
            if (fact.getCandidateId() != null) {
                builder.append("- Context-valid candidate selection: {\"assertionId\":\"a0\",\"candidateId\":\"")
                        .append(fact.getCandidateId()).append("\"}\n");
                break;
            }
        }
    }

    private static void appendAssertionRules(StringBuilder builder,
                                             PostProcessingOptions options) {
        int maximum = options.assertionPolicy().maxAssertions();
        int preferred = Math.min(3, maximum);
        builder.append("- Return up to ").append(maximum).append(" grounded assertions");
        if (preferred > 0) {
            builder.append("; prefer 1-").append(preferred)
                    .append(" high-value assertions over verbosity");
        }
        builder.append(".\n");
        builder.append("- When a stable non-input observation or selectable candidate supports a useful oracle, normally return at least one.\n");
        builder.append("- Observations with provenance INPUT are setup values; do not assert them directly.\n");
        builder.append("- Candidate facts with candidateId are validated EvoSuite assertions. Select one with {\"assertionId\":\"aN\",\"candidateId\":\"cN\"}; do not copy or reconstruct it.\n");
        builder.append("- You may select useful candidates and propose assertions that add semantic value.\n");
        builder.append("- Observations with complete=false are truncated; do not use them for exact equality, exact size, or exact content assertions.\n");
        builder.append("- Call only callable members listed in the Callable members section. Those entries are the purity allowlist; if it says none, do not call instance methods.\n");
        builder.append("- Assertion kind must be one of EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME, CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY, GREATER, LESS, GREATER_EQUALS, LESS_EQUALS.\n");
        builder.append("- Prefer the highest-value grounded oracles, whether selected candidates or novel assertions. Novel assertions are especially useful for relationships between two observed SUT results, constructed expected values via allowlisted immutable constructors or pure static factories, and meaningful object/collection state.\n");
        builder.append("- GREATER/LESS/GREATER_EQUALS/LESS_EQUALS assert that actual compares to expected (GREATER means actual > expected) and require numeric operands.\n");
        builder.append("- Assertion intent is optional and currently must be REGRESSION; do not infer specification-expected behavior beyond the observed SUT behavior.\n");
        builder.append("- Assertions are attached after the final test statement; do not emit placement or afterStatementId for assertions.\n");
        builder.append("- Collection assertion aliases are allowed: CONTAINS/NOT_CONTAINS may use container+element, SIZE_EQUALS may use target+size, MAP_CONTAINS_KEY may use map+key, and IS_EMPTY may use target.\n");
        builder.append("- Assertion expressions must use only the canonical Java expression dialect accepted by the parser: literals, stable variable ids, listed callable members, simple field/array access, pure arithmetic/boolean/comparison operators, and observed-literal one-dimensional arrays.\n");
        builder.append("- Even when proposing variableNames, assertion expressions must use the stable vN ids, not the proposed names.\n");
        builder.append("- Chained calls are allowed only when every call in the chain is listed for the receiver variable or receiver type in Callable members.\n");
        builder.append("- Assertion expressions must be side-effect-free Java expressions and must not end with semicolons.\n");
        builder.append("- expected and actual are Java expression strings: preserve Java quoting and escaping for String and char literals (for example, use \"\\\"text\\\"\" as the JSON value for the Java expression \"text\").\n");
        builder.append("- Use CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, or IS_EMPTY only when the corresponding contains, size, containsKey, or isEmpty method is listed for that receiver/type.\n");
        builder.append("- Floating-point EQUALS assertions require a non-negative numeric delta; non-floating equality must omit delta.\n");
        builder.append("- Array EQUALS assertions require compatible one-dimensional array operands; do not use NOT_EQUALS on arrays.\n");
        builder.append("- If assertions is non-empty, set assertionDecision to PROPOSED and omit noAssertionReason.\n");
        builder.append("- If assertions is empty, set assertionDecision to NO_SAFE_ORACLE and choose noAssertionReason from NO_STABLE_OBSERVATION, NO_LEGAL_CALLABLE, THROWING_TEST, CANDIDATE_REDUNDANCY, ONLY_SETUP_VALUES, TRUNCATED_OBSERVATION, OTHER.\n");
    }

    private static void appendEnabledField(StringBuilder builder, String field, boolean enabled) {
        if (enabled) {
            builder.append(' ').append(field);
        }
    }

    private static void appendResponseShape(StringBuilder builder, boolean assertionsEnabled,
                                            boolean testNames, boolean variableNames,
                                            boolean comments, boolean sectionBreaks) {
        builder.append("{\"schemaVersion\":")
                .append(LlmPostProcessingProtocol.RESPONSE_SCHEMA_VERSION);
        if (testNames) {
            builder.append(",\"testName\":\"optionalJavaIdentifier\"");
        }
        if (variableNames) {
            builder.append(",\"variableNames\":{\"v0\":\"optionalJavaIdentifier\"}");
        }
        if (comments) {
            builder.append(",\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"optional short comment\"}]");
        }
        if (sectionBreaks) {
            builder.append(",\"sectionBreaksAfter\":[\"s0\"]");
        }
        if (assertionsEnabled) {
            builder.append(",\"assertionDecision\":\"PROPOSED_or_NO_SAFE_ORACLE\"");
            builder.append(",\"noAssertionReason\":\"required only when assertions is empty\"");
            builder.append(",\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",");
            builder.append("\"actual\":\"optionalExpression\",");
            builder.append("\"intent\":\"REGRESSION\",");
            builder.append("\"purpose\":\"optional purpose\"}]");
        }
        builder.append("}");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
