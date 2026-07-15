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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.SystemPromptProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the single unified post-processing request for one generated test.
 */
final class LlmPostProcessingPromptBuilder {

    private LlmPostProcessingPromptBuilder() {
        // Utility class.
    }

    static PromptResult build(LlmPostProcessingPromptContext context, int testIndex) {
        return build(context, testIndex, Properties.LLM_POSTPROCESSING_ASSERTIONS);
    }

    static PromptResult build(LlmPostProcessingPromptContext context, int testIndex, boolean assertionsEnabled) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(new SystemPromptProvider().getPostProcessingSystemPrompt()));
        messages.add(LlmMessage.user(userPrompt(context, testIndex, assertionsEnabled)));
        return new PromptResult.Builder().messages(messages).build();
    }

    private static String userPrompt(LlmPostProcessingPromptContext context, int testIndex,
                                     boolean assertionsEnabled) {
        // Everything from here down to "Generated test statements:" is invariant
        // across tests of the same category in a run, so it forms a byte-identical
        // cacheable prefix. Per-test content (the test index, statements,
        // observations, candidates, callable members) is emitted afterwards; the
        // test index is intentionally not in the prompt (it is only trace metadata).
        StringBuilder builder = new StringBuilder();
        builder.append("Target class: ").append(nullToEmpty(Properties.TARGET_CLASS)).append('\n');
        builder.append("Enabled fields:");
        appendEnabledField(builder, "testName", Properties.LLM_POSTPROCESSING_TEST_NAMES);
        appendEnabledField(builder, "variableNames", Properties.LLM_POSTPROCESSING_VARIABLE_NAMES);
        appendEnabledField(builder, "comments", Properties.LLM_POSTPROCESSING_COMMENTS);
        appendEnabledField(builder, "sectionBreaksAfter", Properties.LLM_POSTPROCESSING_SECTION_BREAKS);
        appendEnabledField(builder, "assertions", assertionsEnabled);
        builder.append("\n\n");
        builder.append("Return this JSON shape with schemaVersion 1:\n");
        appendResponseShape(builder, assertionsEnabled);
        builder.append("\n\n");
        if (assertionsEnabled) {
            appendAssertionExamples(builder);
            builder.append("\n\n");
        }
        builder.append("Rules:\n");
        builder.append("- Use only the statement ids sN and variable ids vN shown below.\n");
        if (Properties.LLM_POSTPROCESSING_TEST_NAMES || Properties.LLM_POSTPROCESSING_VARIABLE_NAMES) {
            builder.append("- Variable and test names must be Java identifiers and not Java keywords.\n");
        }
        if (Properties.LLM_POSTPROCESSING_VARIABLE_NAMES) {
            builder.append("- Propose names for every nameable vN whose current rendered name is generic, type-only, or numeric-suffixed.\n");
            builder.append("- Variable names must describe the variable's role in this test, using constructor arguments, method-call receivers/arguments, observations, and later use sites.\n");
            builder.append("- The variableNames object should normally include every current rendered name ending in digits; leave one out only when renaming it would be misleading or unsafe.\n");
            builder.append("- Do not merely remove leading zeroes or keep type-only names such as object0, string1, actionEvent2, map3, list4, or variants with numeric suffixes.\n");
            builder.append("- If no precise domain role is clear, still prefer a suffix-free type/role fallback such as gui, event, source, value, result, input, output, collection, list, map, array, expectedValue, or actualValue over the current numeric EvoSuite name.\n");
            builder.append("- For multiple variables of the same type, choose distinct role names; keep a numeric suffix only as a last resort to avoid a real collision.\n");
        }
        if (Properties.LLM_POSTPROCESSING_COMMENTS) {
            builder.append("- Add comments only when they clarify test intent, logical phases, non-obvious setup, or why a strange generated value matters.\n");
            builder.append("- Do not comment obvious constructor calls, primitive assignments, or method calls by restating the code.\n");
            builder.append("- Prefer no comment over a redundant comment.\n");
            builder.append("- Use at most one comment per logical phase; use sectionBreaksAfter for visual grouping without prose.\n");
        }
        if (Properties.LLM_POSTPROCESSING_SECTION_BREAKS) {
            builder.append("- Add sectionBreaksAfter only between logical phases such as setup, action, and verification.\n");
            builder.append("- Add a section break only when it would noticeably improve readability; if unsure, omit it.\n");
            builder.append("- Prefer section breaks over comments when visual grouping is enough.\n");
            builder.append("- Do not add section breaks after every statement or inside a short linear setup.\n");
            builder.append("- Do not add a trailing section break after the final meaningful statement.\n");
            builder.append("- Omit sectionBreaksAfter for very short tests unless there is a clear phase boundary.\n");
        }
        if (assertionsEnabled) {
            builder.append("- Observations with provenance INPUT are setup values; do not assert them directly.\n");
            builder.append("- EvoSuite-observed candidate facts are existing filtered observations, not required assertions; propose only assertions that add semantic value beyond them.\n");
            builder.append("- Candidate facts deliberately contain no reusable Java code. Build expressions only from stable ids and the callable allowlist.\n");
            builder.append("- Observations with relationalOnly=true may only be used in relational assertions.\n");
            builder.append("- Observations with complete=false are truncated; do not use them for exact equality, exact size, or exact content assertions.\n");
            builder.append("- Call only callable members listed in the Callable members section. Those entries are the purity allowlist; if it says none, do not call instance methods.\n");
            builder.append("- Assertion kind must be one of EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME, CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY, GREATER, LESS, GREATER_EQUALS, LESS_EQUALS.\n");
            builder.append("- Prefer assertions that add semantic value beyond the candidate facts: relationships between two observed SUT results, constructed expected values via allowlisted immutable constructors or pure static factories, and meaningful object/collection state.\n");
            builder.append("- GREATER/LESS/GREATER_EQUALS/LESS_EQUALS assert that actual compares to expected (GREATER means actual > expected) and require numeric operands.\n");
            builder.append("- Assertion intent is optional and currently must be REGRESSION; do not infer specification-expected behavior beyond the observed SUT behavior.\n");
            builder.append("- Assertion placement is optional; when used, provide {\"afterStatementId\":\"sN\"} and reference only variables available at that statement.\n");
            builder.append("- Collection assertion aliases are allowed: CONTAINS/NOT_CONTAINS may use container+element, SIZE_EQUALS may use target+size, MAP_CONTAINS_KEY may use map+key, and IS_EMPTY may use target.\n");
            builder.append("- Assertion expressions must use only the canonical Java expression dialect accepted by the parser: literals, stable variable ids, listed callable members, simple field/array access, pure arithmetic/boolean/comparison operators, and observed-literal one-dimensional arrays.\n");
            builder.append("- Even when proposing variableNames, assertion expressions must use the stable vN ids, not the proposed names.\n");
            builder.append("- Chained calls are allowed only when every call in the chain is listed for the receiver variable or receiver type in Callable members.\n");
            builder.append("- Assertion expressions must be side-effect-free Java expressions and must not end with semicolons.\n");
            builder.append("- expected and actual are Java expression strings: preserve Java quoting and escaping for String and char literals (for example, use \"\\\"text\\\"\" as the JSON value for the Java expression \"text\").\n");
            builder.append("- Use CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, or IS_EMPTY only when the corresponding contains, size, containsKey, or isEmpty method is listed for that receiver/type.\n");
            builder.append("- Floating-point EQUALS assertions require a non-negative numeric delta; non-floating equality must omit delta.\n");
            builder.append("- Array EQUALS assertions require compatible one-dimensional array operands; do not use NOT_EQUALS on arrays.\n");
        } else {
            builder.append("- Assertions are disabled for this test; omit the assertions field.\n");
        }
        builder.append("- Omit fields you do not want to change; empty arrays/objects are allowed.\n\n");
        builder.append("Generated test statements:\n");
        builder.append(context.toAnnotatedText());
        builder.append("\nExceptions:\n");
        builder.append(context.toExceptionText());
        builder.append("\nEvoSuite-observed candidate facts:\n");
        builder.append(context.toCandidateFactText());
        builder.append("\nObservations:\n");
        builder.append(context.toObservationText());
        builder.append("\nCallable members:\n");
        builder.append(context.toCallableMemberText());
        return builder.toString();
    }

    private static void appendAssertionExamples(StringBuilder builder) {
        // Anchor the model on the high-value assertion shapes the grammar enables;
        // adapt ids/values to the current test rather than copying verbatim.
        builder.append("Examples of high-value assertions (adapt ids/values; do not copy verbatim):\n");
        builder.append("- Constructed expected value: {\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new java.math.BigDecimal(\\\"90\\\")\",\"actual\":\"v0.getAmount()\"}\n");
        builder.append("- Relationship between two observed results: {\"assertionId\":\"a1\",\"kind\":\"GREATER\","
                + "\"expected\":\"v1.getBalance()\",\"actual\":\"v0.getBalance()\"}\n");
        builder.append("- Collection size: {\"assertionId\":\"a2\",\"kind\":\"SIZE_EQUALS\","
                + "\"target\":\"v0\",\"size\":\"3\"}\n");
        builder.append("- Enum state: {\"assertionId\":\"a3\",\"kind\":\"EQUALS\","
                + "\"expected\":\"com.example.Status.ACTIVE\",\"actual\":\"v0.getStatus()\"}");
        builder.append("\n- Floating-point equality: {\"assertionId\":\"a4\",\"kind\":\"EQUALS\","
                + "\"expected\":\"0.5F\",\"actual\":\"v0.getRatio()\",\"delta\":\"0.0F\"}");
    }

    private static void appendEnabledField(StringBuilder builder, String field, boolean enabled) {
        if (enabled) {
            builder.append(' ').append(field);
        }
    }

    private static void appendResponseShape(StringBuilder builder, boolean assertionsEnabled) {
        builder.append("{\"schemaVersion\":1");
        if (Properties.LLM_POSTPROCESSING_TEST_NAMES) {
            builder.append(",\"testName\":\"optionalJavaIdentifier\"");
        }
        if (Properties.LLM_POSTPROCESSING_VARIABLE_NAMES) {
            builder.append(",\"variableNames\":{\"v0\":\"optionalJavaIdentifier\"}");
        }
        if (Properties.LLM_POSTPROCESSING_COMMENTS) {
            builder.append(",\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"optional short comment\"}]");
        }
        if (Properties.LLM_POSTPROCESSING_SECTION_BREAKS) {
            builder.append(",\"sectionBreaksAfter\":[\"s0\"]");
        }
        if (assertionsEnabled) {
            builder.append(",\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",");
            builder.append("\"actual\":\"optionalExpression\",");
            builder.append("\"expected\":\"optionalExpression\",\"intent\":\"REGRESSION\",");
            builder.append("\"placement\":{\"afterStatementId\":\"s0\"},\"purpose\":\"optional purpose\"}]");
        }
        builder.append("}");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
