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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        return buildInternal(context, testIndex, assertionsEnabled, null);
    }

    /** Production entry point; new requests always use the frozen P2 protocol. */
    static PromptResult build(LlmPostProcessingPromptContext context, int testIndex,
                              boolean assertionsEnabled, PostProcessingOptions options) {
        return PostProcessingPromptRenderer.build(context, assertionsEnabled, options);
    }

    private static PromptResult buildInternal(LlmPostProcessingPromptContext context, int testIndex,
                                              boolean assertionsEnabled, PostProcessingOptions options) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(new SystemPromptProvider().getPostProcessingSystemPrompt()));
        messages.add(LlmMessage.user(userPrompt(context, testIndex, assertionsEnabled, options)));
        return new PromptResult.Builder().messages(messages).build();
    }

    private static String userPrompt(LlmPostProcessingPromptContext context, int testIndex,
                                     boolean assertionsEnabled) {
        return userPrompt(context, testIndex, assertionsEnabled, null);
    }

    private static String userPrompt(LlmPostProcessingPromptContext context, int testIndex,
                                     boolean assertionsEnabled, PostProcessingOptions options) {
        Properties.LlmPostProcessingPromptVariant variant = options == null
                ? promptVariant() : Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
        PromptVariantCapabilities capabilities = PromptVariantCapabilities.forVariant(variant);
        boolean testNames = options == null ? Properties.LLM_POSTPROCESSING_TEST_NAMES : options.features().testNames();
        boolean variableNames = options == null ? Properties.LLM_POSTPROCESSING_VARIABLE_NAMES : options.features().variableNames();
        boolean comments = options == null ? Properties.LLM_POSTPROCESSING_COMMENTS : options.features().comments();
        boolean sectionBreaks = options == null ? Properties.LLM_POSTPROCESSING_SECTION_BREAKS : options.features().sectionBreaks();
        // Everything from here down to "Generated test statements:" is invariant
        // across tests of the same category in a run, so it forms a byte-identical
        // cacheable prefix. Per-test content (the test index, statements,
        // observations, candidates, callable members) is emitted afterwards; the
        // test index is intentionally not in the prompt (it is only trace metadata).
        StringBuilder builder = new StringBuilder();
        builder.append("Prompt version: ").append(options == null
                ? LlmPostProcessingProtocol.historicalPromptVersion(variant)
                : options.promptVersion()).append('\n');
        builder.append("Target class: ").append(nullToEmpty(options == null ? Properties.TARGET_CLASS : options.targetClass())).append('\n');
        builder.append("Enabled fields:");
        appendEnabledField(builder, "testName", testNames);
        appendEnabledField(builder, "variableNames", variableNames);
        appendEnabledField(builder, "comments", comments);
        appendEnabledField(builder, "sectionBreaksAfter", sectionBreaks);
        appendEnabledField(builder, "assertions", assertionsEnabled);
        builder.append("\n\n");
        int schemaVersion = options == null
                ? (capabilities.hasExceptionAdjacentPlacements() ? 3 : 2)
                : options.responseSchemaVersion();
        builder.append("Return this JSON shape with schemaVersion ")
                .append(schemaVersion).append(":\n");
        appendResponseShape(builder, assertionsEnabled, capabilities, options);
        builder.append("\n\n");
        if (assertionsEnabled) {
            appendAssertionExamples(builder, context, variant);
            if (capabilities.hasLiteralDiscipline()) {
                appendLiteralExamples(builder);
            }
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
            appendVariantProductivityRules(builder, context, variant, options);
            builder.append("- Observations with provenance INPUT are setup values; do not assert them directly.\n");
            if (allowsCandidateSelection(variant)) {
                if (capabilities.hasCanonicalCandidates()) {
                    builder.append("- candidateId entries already contain the complete validated oracle. Select the ID; never reconstruct its expected or actual expression.\n");
                    builder.append("- Select a candidate with exactly {\"assertionId\":\"aN\",\"candidateId\":\"cN\"}; replace cN with an advertised candidateId and do not add kind, expected, actual, or delta.\n");
                    if (capabilities.hasExceptionAdjacentPlacements()) {
                        builder.append("- Candidate selection must omit placement; EvoSuite applies the candidate's validated assertion site, including for throwing tests.\n");
                    }
                } else {
                    builder.append("- Candidate facts with candidateId are validated EvoSuite assertions. Select one with {\"assertionId\":\"aN\",\"candidateId\":\"cN\"}; do not copy or reconstruct it.\n");
                }
                builder.append("- You may select useful candidates and also propose novel relational assertions that add semantic value.\n");
            } else {
                builder.append("- Candidate facts are context only; this variant evaluates novel assertion synthesis and does not accept candidateId selection.\n");
            }
            builder.append("- Observations with relationalOnly=true may only be used in relational assertions.\n");
            builder.append("- Observations with complete=false are truncated; do not use them for exact equality, exact size, or exact content assertions.\n");
            builder.append("- Call only callable members listed in the Callable members section. Those entries are the purity allowlist; if it says none, do not call instance methods.\n");
            if (variant == Properties.LlmPostProcessingPromptVariant.P3_TYPED_TEMPLATES) {
                builder.append("- For this test, assertion kind must be one of ")
                        .append(String.join(", ", compatibleKinds(context))).append(".\n");
            } else {
                builder.append("- Assertion kind must be one of EQUALS, NOT_EQUALS, TRUE, FALSE, NULL, NOT_NULL, SAME, NOT_SAME, CONTAINS, NOT_CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, IS_EMPTY, GREATER, LESS, GREATER_EQUALS, LESS_EQUALS.\n");
            }
            builder.append("- Prefer the highest-value grounded oracles, whether selected candidates or novel assertions. Novel assertions are especially useful for relationships between two observed SUT results, constructed expected values via allowlisted immutable constructors or pure static factories, and meaningful object/collection state.\n");
            if (capabilities.hasActionRoles()) {
                builder.append("- Prioritize RESULT and POST_STATE candidates associated with the final SUT action; UNKNOWN roles are weaker evidence.\n");
            }
            if (capabilities.hasRelationalOpportunities()) {
                builder.append("- When a listed relational opportunity adds information, prefer one grounded identity, equality, or comparison assertion over unrelated inspectors.\n");
            }
            if (capabilities.hasStabilityLabels()) {
                builder.append("- Prefer STABLE candidates, use UNKNOWN cautiously, and never select UNSTABLE candidates.\n");
            }
            builder.append("- GREATER/LESS/GREATER_EQUALS/LESS_EQUALS assert that actual compares to expected (GREATER means actual > expected) and require numeric operands.\n");
            builder.append("- Assertion intent is optional and currently must be REGRESSION; do not infer specification-expected behavior beyond the observed SUT behavior.\n");
            if (capabilities.hasExceptionAdjacentPlacements()) {
                builder.append("- Use only an advertised placement site. EvoSuite owns exception expectation, caught type, try/fail/catch control flow, and exception-source verification.\n");
                builder.append("- placement, when present, must be a JSON object with a site field; never use a placement string or a type field.\n");
                builder.append("- For a synthesized assertion in a non-throwing test use {\"site\":\"END_OF_TEST\"}. For a synthesized assertion in a throwing test use exactly one advertised form: {\"site\":\"BEFORE_TRY\",\"afterStatementId\":\"sN\"}, {\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}, or {\"site\":\"AFTER_CATCH\"}.\n");
                builder.append("- Never propose an assertion merely that an exception is thrown.\n");
            } else {
                builder.append("- Assertions are attached after the final test statement; do not emit placement or afterStatementId for assertions.\n");
            }
            if (variant != Properties.LlmPostProcessingPromptVariant.P3_TYPED_TEMPLATES) {
                builder.append("- Collection assertion aliases are allowed: CONTAINS/NOT_CONTAINS may use container+element, SIZE_EQUALS may use target+size, MAP_CONTAINS_KEY may use map+key, and IS_EMPTY may use target.\n");
            }
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
        } else {
            builder.append("- Assertions are disabled for this test; omit the assertions field.\n");
        }
        builder.append("- Omit fields you do not want to change; empty arrays/objects are allowed.\n\n");
        builder.append("Generated test statements:\n");
        builder.append(context.toAnnotatedText(capabilities.hasActionRoles()));
        builder.append("\nExceptions:\n");
        builder.append(context.toExceptionText());
        if (capabilities.hasExceptionAdjacentPlacements() && !context.getExceptions().isEmpty()) {
            builder.append("\nAutomatic exception handling:\n");
            builder.append("- throwingStatement=")
                    .append(context.getExceptions().get(0).getStatementId()).append('\n');
            builder.append("- EvoSuite manages try/fail/catch and exception-source verification\n");
            builder.append("- do not assert that an exception is thrown\n");
            builder.append("\nSafe assertion sites:\n");
            builder.append(context.toSafeAssertionSiteText());
        }
        builder.append("\nEvoSuite-observed candidate facts:\n");
        builder.append(context.toCandidateFactText(allowsCandidateSelection(variant),
                capabilities.hasCanonicalCandidates(), capabilities.hasActionRoles(),
                capabilities.hasStabilityLabels(), capabilities.hasAssertableTypesOnly()));
        builder.append("\nObservations:\n");
        builder.append(context.toObservationText());
        if (capabilities.hasRelationalOpportunities()) {
            builder.append("\nRelational opportunities:\n");
            builder.append(context.toRelationalOpportunityText());
        }
        if (capabilities.hasCompactObservedCalls()) {
            builder.append("\nObserved safe expressions:\n");
            builder.append(context.toObservedSafeExpressionText());
            builder.append("\nAdditional legal calls:\n");
            builder.append(context.toAdditionalLegalCallText());
        } else {
            builder.append("\nCallable members:\n");
            builder.append(context.toCallableMemberText());
        }
        return builder.toString();
    }

    private static void appendAssertionExamples(StringBuilder builder,
                                                LlmPostProcessingPromptContext context,
                                                Properties.LlmPostProcessingPromptVariant variant) {
        builder.append("Assertion object forms:\n");
        if (variant == Properties.LlmPostProcessingPromptVariant.P3_TYPED_TEMPLATES) {
            appendTypedTemplates(builder, context);
        } else {
            builder.append("- TRUE/FALSE/NULL/NOT_NULL: assertionId, kind, actual; never expected.\n");
            builder.append("- EQUALS/NOT_EQUALS/SAME/NOT_SAME and comparisons: assertionId, kind, expected, actual.\n");
            builder.append("- Floating EQUALS/NOT_EQUALS additionally accepts delta; scalar JSON values are normalized to expression strings.\n");
        }
        if (allowsCandidateSelection(variant) && isLegacyVariant(variant)) {
            for (LlmPostProcessingPromptContext.CandidateFact fact : context.getCandidateFacts()) {
                if (fact.getCandidateId() != null) {
                    builder.append("- Context-valid candidate selection: {\"assertionId\":\"a0\",\"candidateId\":\"")
                            .append(fact.getCandidateId()).append("\"}\n");
                    break;
                }
            }
        } else if (allowsCandidateSelection(variant)) {
            builder.append("- Candidate selection form: {\"assertionId\":\"a0\",\"candidateId\":\"cN\"}; use an advertised candidateId in place of cN.\n");
        }
        PromptVariantCapabilities capabilities =
                PromptVariantCapabilities.forVariant(variant);
        if (capabilities.hasExceptionAdjacentPlacements()) {
            builder.append("- Placement forms: {\"site\":\"END_OF_TEST\"}; "
                    + "{\"site\":\"BEFORE_TRY\",\"afterStatementId\":\"sN\"}; "
                    + "{\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}; "
                    + "{\"site\":\"AFTER_CATCH\"}.\n");
        }
    }

    private static void appendLiteralExamples(StringBuilder builder) {
        builder.append("Java/JSON literal examples:\n");
        builder.append("- observed Java value quote+slash; Java expression \"quote\\\\slash\"; JSON expression string \"\\\"quote\\\\\\\\slash\\\"\".\n");
        builder.append("- char Java expression '\\\\n'; JSON expression string \"'\\\\\\\\n'\".\n");
        builder.append("- floating expressions Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.0F, 1.0D; long expression 1L.\n");
        builder.append("- one-dimensional array Java expression new int[]{1, 2}; encode the entire Java expression as one JSON string.\n");
        builder.append("- Prefer candidateId selection when it already represents the required literal.\n");
    }

    private static void appendVariantProductivityRules(StringBuilder builder,
                                                        LlmPostProcessingPromptContext context,
                                                        Properties.LlmPostProcessingPromptVariant variant,
                                                        PostProcessingOptions options) {
        int maximum = options == null ? Math.max(0, Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST)
                : options.assertionPolicy().maxAssertions();
        if (variant == Properties.LlmPostProcessingPromptVariant.P0_CURRENT) {
            builder.append("- Return up to ").append(maximum)
                    .append(" novel assertions only when they add value beyond EvoSuite-observed candidate facts.\n");
            return;
        }
        int preferred = Math.min(3, maximum);
        builder.append("- Return up to ").append(maximum).append(" grounded assertions");
        if (preferred > 0) {
            builder.append("; prefer 1-").append(preferred)
                    .append(" high-value assertions over verbosity");
        }
        builder.append(".\n");
        builder.append("- When a stable non-input observation")
                .append(allowsCandidateSelection(variant) ? " or selectable candidate" : "")
                .append(" supports a useful oracle, normally return at least one.\n");
    }

    private static void appendTypedTemplates(StringBuilder builder,
                                             LlmPostProcessingPromptContext context) {
        Set<String> kinds = compatibleKinds(context);
        if (kinds.contains("EQUALS")) {
            builder.append("- EQUALS/NOT_EQUALS: assertionId, kind, expected, actual")
                    .append(kinds.contains("GREATER") ? "; floating values also use delta" : "")
                    .append(".\n");
        }
        if (kinds.contains("TRUE")) {
            builder.append("- TRUE/FALSE: assertionId, kind, actual; never expected.\n");
        }
        if (kinds.contains("NULL")) {
            builder.append("- NULL/NOT_NULL and SAME/NOT_SAME are available for reference-typed operands.\n");
        }
        if (kinds.contains("GREATER")) {
            builder.append("- GREATER/LESS/GREATER_EQUALS/LESS_EQUALS: assertionId, kind, expected, actual.\n");
        }
        if (kinds.contains("CONTAINS")) {
            builder.append("- CONTAINS/NOT_CONTAINS are available only for the listed contains receiver.\n");
        }
        if (kinds.contains("SIZE_EQUALS")) {
            builder.append("- SIZE_EQUALS is available only for the listed size receiver.\n");
        }
        if (kinds.contains("MAP_CONTAINS_KEY")) {
            builder.append("- MAP_CONTAINS_KEY is available only for the listed containsKey receiver.\n");
        }
        if (kinds.contains("IS_EMPTY")) {
            builder.append("- IS_EMPTY is available only for the listed isEmpty receiver.\n");
        }
    }

    private static Set<String> compatibleKinds(LlmPostProcessingPromptContext context) {
        Set<String> types = new LinkedHashSet<>();
        for (LlmPostProcessingPromptContext.StatementContext statement : context.getStatements()) {
            if (statement.getVariableId() != null && statement.getDeclaredType() != null) {
                types.add(statement.getDeclaredType());
            }
        }
        boolean bool = false;
        boolean numeric = false;
        boolean reference = false;
        for (String type : types) {
            String normalized = type == null ? "" : type.replace("java.lang.", "");
            bool |= "boolean".equals(normalized) || "Boolean".equals(normalized);
            numeric |= normalized.matches("(byte|short|int|long|float|double|Byte|Short|Integer|Long|Float|Double)");
            reference |= !normalized.matches("(boolean|byte|short|int|long|float|double|char)");
        }
        Set<String> kinds = new LinkedHashSet<>();
        if (!types.isEmpty()) {
            kinds.add("EQUALS");
            kinds.add("NOT_EQUALS");
        }
        if (bool) {
            kinds.add("TRUE");
            kinds.add("FALSE");
        }
        if (reference) {
            kinds.add("NULL");
            kinds.add("NOT_NULL");
            kinds.add("SAME");
            kinds.add("NOT_SAME");
        }
        if (numeric) {
            kinds.add("GREATER");
            kinds.add("LESS");
            kinds.add("GREATER_EQUALS");
            kinds.add("LESS_EQUALS");
        }
        Set<String> methods = new LinkedHashSet<>();
        for (LlmPostProcessingPromptContext.CallableMember member : context.getCallableMembers()) {
            methods.add(member.getSignature());
        }
        if (containsMethod(methods, "contains")) {
            kinds.add("CONTAINS");
            kinds.add("NOT_CONTAINS");
        }
        if (containsMethod(methods, "size")) {
            kinds.add("SIZE_EQUALS");
        }
        if (containsMethod(methods, "containsKey")) {
            kinds.add("MAP_CONTAINS_KEY");
        }
        if (containsMethod(methods, "isEmpty")) {
            kinds.add("IS_EMPTY");
        }
        if (kinds.isEmpty()) {
            kinds.add("EQUALS");
        }
        return kinds;
    }

    private static boolean containsMethod(Set<String> signatures, String name) {
        for (String signature : signatures) {
            if (signature != null && signature.startsWith(name + "(")) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsCandidateSelection(Properties.LlmPostProcessingPromptVariant variant) {
        return variant != Properties.LlmPostProcessingPromptVariant.P0_CURRENT
                && variant != Properties.LlmPostProcessingPromptVariant.P1_GROUNDED_PRODUCTIVE;
    }

    private static boolean isLegacyVariant(Properties.LlmPostProcessingPromptVariant variant) {
        return variant == Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION
                || variant == Properties.LlmPostProcessingPromptVariant.P3_TYPED_TEMPLATES;
    }

    private static Properties.LlmPostProcessingPromptVariant promptVariant() {
        return Properties.LLM_POSTPROCESSING_PROMPT_VARIANT == null
                ? Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION
                : Properties.LLM_POSTPROCESSING_PROMPT_VARIANT;
    }

    private static void appendEnabledField(StringBuilder builder, String field, boolean enabled) {
        if (enabled) {
            builder.append(' ').append(field);
        }
    }

    private static void appendResponseShape(StringBuilder builder, boolean assertionsEnabled,
                                            PromptVariantCapabilities capabilities,
                                            PostProcessingOptions options) {
        boolean testNames = options == null ? Properties.LLM_POSTPROCESSING_TEST_NAMES : options.features().testNames();
        boolean variableNames = options == null ? Properties.LLM_POSTPROCESSING_VARIABLE_NAMES : options.features().variableNames();
        boolean comments = options == null ? Properties.LLM_POSTPROCESSING_COMMENTS : options.features().comments();
        boolean sectionBreaks = options == null ? Properties.LLM_POSTPROCESSING_SECTION_BREAKS : options.features().sectionBreaks();
        builder.append("{\"schemaVersion\":")
                .append(options == null && capabilities.hasExceptionAdjacentPlacements() ? 3
                        : LlmPostProcessingProtocol.RESPONSE_SCHEMA_VERSION);
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
            if (capabilities.hasExceptionAdjacentPlacements()) {
                builder.append("\"placement\":{\"site\":\"END_OF_TEST\"},");
            }
            builder.append("\"intent\":\"REGRESSION\",");
            builder.append("\"purpose\":\"optional purpose\"}]");
        }
        builder.append("}");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
