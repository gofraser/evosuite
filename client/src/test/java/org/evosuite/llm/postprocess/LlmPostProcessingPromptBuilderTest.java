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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPostProcessingPromptBuilderTest {

    private boolean originalAssertions;
    private boolean originalTestNames;
    private boolean originalVariableNames;
    private boolean originalComments;
    private boolean originalSectionBreaks;
    private Properties.LlmPostProcessingPromptVariant originalPromptVariant;

    @BeforeEach
    void saveProperties() {
        originalAssertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        originalTestNames = Properties.LLM_POSTPROCESSING_TEST_NAMES;
        originalVariableNames = Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        originalComments = Properties.LLM_POSTPROCESSING_COMMENTS;
        originalSectionBreaks = Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
        originalPromptVariant = Properties.LLM_POSTPROCESSING_PROMPT_VARIANT;
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = originalAssertions;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = originalTestNames;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = originalVariableNames;
        Properties.LLM_POSTPROCESSING_COMMENTS = originalComments;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = originalSectionBreaks;
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT = originalPromptVariant;
    }

    @Test
    void build_responseShapeContainsOnlyEnabledEditCategories() {
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));

        PromptResult prompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test), 0);
        String userPrompt = prompt.getMessages().get(1).getContent();

        assertTrue(userPrompt.contains("\"schemaVersion\":2"));
        assertTrue(userPrompt.contains("Prompt version: postprocessing-p2-v2"));
        assertTrue(userPrompt.contains("\"assertionDecision\""));
        assertTrue(userPrompt.contains("NO_SAFE_ORACLE"));
        assertTrue(userPrompt.contains("\"testName\""));
        assertTrue(userPrompt.contains("\"assertions\""));
        assertTrue(userPrompt.contains("Observations:"));
        assertTrue(userPrompt.contains("EvoSuite-observed candidate facts:"));
        assertTrue(userPrompt.contains("Candidate facts with candidateId are validated EvoSuite assertions"));
        assertTrue(userPrompt.contains("Return up to "
                + Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST + " grounded assertions"));
        assertTrue(userPrompt.contains("normally return at least one"));
        assertTrue(userPrompt.contains("whether selected candidates or novel assertions"));
        assertTrue(userPrompt.contains("provenance=INPUT"));
        assertTrue(userPrompt.contains("do not assert them directly"));
        assertTrue(userPrompt.contains("complete=false are truncated"));
        assertTrue(userPrompt.contains("Callable members:"));
        assertTrue(userPrompt.contains("purity allowlist"));
        assertTrue(userPrompt.contains("if it says none, do not call instance methods"));
        assertFalse(userPrompt.contains("Propose names for every nameable vN"));
        assertFalse(userPrompt.contains("current rendered name is generic, type-only, or numeric-suffixed"));
        assertTrue(userPrompt.contains("canonical Java expression dialect"));
        assertTrue(userPrompt.contains("Floating-point EQUALS assertions require"));
        assertTrue(userPrompt.contains("Array EQUALS assertions require compatible one-dimensional array operands"));
        assertTrue(userPrompt.contains("preserve Java quoting and escaping"));
        assertTrue(userPrompt.contains("Use CONTAINS, SIZE_EQUALS, MAP_CONTAINS_KEY, or IS_EMPTY only when"));
        assertTrue(userPrompt.contains("\"kind\":\"TRUE\",\"actual\":\"optionalExpression\",\"intent\""));
        assertFalse(userPrompt.contains("\"kind\":\"TRUE\",\"actual\":\"optionalExpression\",\"expected\""));
        assertFalse(userPrompt.contains("Collection size:"));
        assertTrue(userPrompt.contains("do not emit placement or afterStatementId for assertions"));
        assertFalse(userPrompt.contains("\"variableNames\""));
        assertFalse(userPrompt.contains("\"comments\""));
        assertFalse(userPrompt.contains("\"sectionBreaksAfter\""));
    }

    @Test
    void build_omitsAssertionsWhenDisabledForThisTest() {
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));

        PromptResult prompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test), 0, false);
        String userPrompt = prompt.getMessages().get(1).getContent();

        assertTrue(userPrompt.contains("\"schemaVersion\":2"));
        assertTrue(userPrompt.contains("\"testName\""));
        assertTrue(userPrompt.contains("Assertions are disabled for this test"));
        assertFalse(userPrompt.contains("\"assertions\""));
        assertFalse(userPrompt.contains("Assertion kind must be one of"));
        assertFalse(userPrompt.contains("Candidate facts with candidateId are validated EvoSuite assertions"));
        assertFalse(userPrompt.contains("purity allowlist"));
    }

    @Test
    void build_includesCommentQualityGuidanceWhenCommentsAreEnabled() {
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));

        PromptResult prompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test), 0);
        String userPrompt = prompt.getMessages().get(1).getContent();

        assertTrue(userPrompt.contains("\"comments\""));
        assertTrue(userPrompt.contains("\"sectionBreaksAfter\""));
        assertTrue(userPrompt.contains("Add comments only when they clarify test intent"));
        assertTrue(userPrompt.contains("non-obvious setup"));
        assertTrue(userPrompt.contains("Do not comment obvious constructor calls"));
        assertTrue(userPrompt.contains("Prefer no comment over a redundant comment"));
        assertTrue(userPrompt.contains("Use at most one comment per logical phase"));
        assertTrue(userPrompt.contains("sectionBreaksAfter for visual grouping without prose"));
        assertTrue(userPrompt.contains("Add sectionBreaksAfter only between logical phases"));
        assertTrue(userPrompt.contains("setup, action, and verification"));
        assertTrue(userPrompt.contains("only when it would noticeably improve readability"));
        assertTrue(userPrompt.contains("if unsure, omit it"));
        assertTrue(userPrompt.contains("Prefer section breaks over comments when visual grouping is enough"));
        assertTrue(userPrompt.contains("Do not add section breaks after every statement"));
        assertTrue(userPrompt.contains("Do not add a trailing section break"));
        assertTrue(userPrompt.contains("Omit sectionBreaksAfter for very short tests"));
    }

    @Test
    void build_exposesFourStablePromptVariants() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P0_CURRENT;
        String p0 = LlmPostProcessingPromptBuilder.build(context, 0).getMessages().get(1).getContent();
        assertTrue(p0.contains("Prompt version: postprocessing-p0-v2"));
        assertTrue(p0.contains("novel assertions only"));
        assertFalse(p0.contains("Context-valid candidate selection"));

        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P1_GROUNDED_PRODUCTIVE;
        String p1 = LlmPostProcessingPromptBuilder.build(context, 0).getMessages().get(1).getContent();
        assertTrue(p1.contains("Prompt version: postprocessing-p1-v2"));
        assertTrue(p1.contains("prefer 1-3 high-value assertions"));
        assertFalse(p1.contains("Context-valid candidate selection"));

        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
        String p2 = LlmPostProcessingPromptBuilder.build(context, 0).getMessages().get(1).getContent();
        assertTrue(p2.contains("Prompt version: postprocessing-p2-v2"));
        assertTrue(p2.contains("select useful candidates"));

        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P3_TYPED_TEMPLATES;
        String p3 = LlmPostProcessingPromptBuilder.build(context, 0).getMessages().get(1).getContent();
        assertTrue(p3.contains("Prompt version: postprocessing-p3-v2"));
        assertTrue(p3.contains("GREATER/LESS/GREATER_EQUALS/LESS_EQUALS"));
        assertFalse(p3.contains("TRUE/FALSE: assertionId"));
    }

    @Test
    void build_exposesOracleContextVariantsAndKeepsTheirPrefixesCacheable() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        DefaultTestCase first = new DefaultTestCase();
        first.addStatement(new IntPrimitiveStatement(first, 7));
        DefaultTestCase second = new DefaultTestCase();
        second.addStatement(new IntPrimitiveStatement(second, 11));
        Properties.LlmPostProcessingPromptVariant[] variants = {
                Properties.LlmPostProcessingPromptVariant.P4_CANONICAL_CANDIDATES,
                Properties.LlmPostProcessingPromptVariant.P5_ACTION_RANKED_CANDIDATES,
                Properties.LlmPostProcessingPromptVariant.P6_RELATIONAL_OPPORTUNITIES,
                Properties.LlmPostProcessingPromptVariant.P7_STABILITY_LABELS,
                Properties.LlmPostProcessingPromptVariant.P8_COMPACT_OBSERVED_CALLS,
                Properties.LlmPostProcessingPromptVariant.P9_LITERAL_DISCIPLINE,
                Properties.LlmPostProcessingPromptVariant.P10_ASSERTABLE_TYPES_ONLY,
                Properties.LlmPostProcessingPromptVariant.P11_EXCEPTION_ADJACENT_ASSERTIONS,
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2
        };
        String[] expectedVersions = {
                "postprocessing-p4-v3",
                "postprocessing-p5-v3",
                "postprocessing-p6-v3",
                "postprocessing-p7-v5",
                "postprocessing-p8-v3",
                "postprocessing-p9-v3",
                "postprocessing-p10-v4",
                "postprocessing-p11-v3",
                "postprocessing-p12-v6"
        };
        for (int index = 0; index < variants.length; index++) {
            Properties.LLM_POSTPROCESSING_PROMPT_VARIANT = variants[index];
            String firstPrompt = LlmPostProcessingPromptBuilder.build(
                    LlmPostProcessingPromptContext.from(first), 0).getMessages().get(1).getContent();
            String secondPrompt = LlmPostProcessingPromptBuilder.build(
                    LlmPostProcessingPromptContext.from(second), 1).getMessages().get(1).getContent();
            assertTrue(firstPrompt.contains("Prompt version: " + expectedVersions[index]));
            assertEquals(cacheablePrefix(firstPrompt), cacheablePrefix(secondPrompt), variants[index].name());
        }

        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
        String cumulative = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(first), 0).getMessages().get(1).getContent();
        assertTrue(cumulative.contains("\"schemaVersion\":3"));
        assertTrue(cumulative.contains("candidateId entries already contain the complete validated oracle"));
        assertTrue(cumulative.contains("Relational opportunities:"));
        assertTrue(cumulative.contains("Observed safe expressions:"));
        assertTrue(cumulative.contains("Java/JSON literal examples:"));
        assertTrue(cumulative.contains(
                "Candidate selection form: {\"assertionId\":\"a0\",\"candidateId\":\"cN\"}"));
        assertTrue(cumulative.contains(
                "Select a candidate with exactly {\"assertionId\":\"aN\",\"candidateId\":\"cN\"}"));
        assertTrue(cumulative.contains(
                "Candidate selection must omit placement; EvoSuite applies the candidate's validated assertion site"));
        assertTrue(cumulative.contains("\"placement\":{\"site\":\"END_OF_TEST\"}"));
        assertTrue(cumulative.contains(
                "{\"site\":\"BEFORE_TRY\",\"afterStatementId\":\"sN\"}"));
        assertTrue(cumulative.contains(
                "{\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}"));
        assertTrue(cumulative.contains("{\"site\":\"AFTER_CATCH\"}"));
        assertTrue(cumulative.contains(
                "placement, when present, must be a JSON object with a site field"));
        assertFalse(cumulative.contains("END_OF_TEST_or_advertised_safe_site"));
    }

    @Test
    void productionBuildIgnoresHistoricalVariantAndFreezesP2SchemaTwo() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        PostProcessingOptions options = PostProcessingOptions.fromProperties();
        String prompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test, null, null, Collections.emptyList(), options),
                0, true, options).getMessages().get(1).getContent();

        assertTrue(prompt.contains("Prompt version: postprocessing-p2-v2"));
        assertTrue(prompt.contains("\"schemaVersion\":2"));
        assertFalse(prompt.contains("postprocessing-p12-v6"));
        assertFalse(prompt.contains("Relational opportunities:"));
    }

    @Test
    void productionRendererPreservesTheCompatibilityPromptBytes() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));

        String compatibilityPrompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test), 0, true)
                .getMessages().get(1).getContent();
        PostProcessingOptions options = PostProcessingOptions.fromProperties();
        String productionPrompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test, null, null,
                        Collections.emptyList(), options),
                0, true, options).getMessages().get(1).getContent();

        assertEquals(compatibilityPrompt, productionPrompt);
    }

    @Test
    void productionRendererMatchesTheGoldenPromptHash() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        PostProcessingOptions options = PostProcessingOptions.fromProperties();
        String prompt = LlmPostProcessingPromptBuilder.build(
                LlmPostProcessingPromptContext.from(test, null, null,
                        Collections.emptyList(), options),
                0, true, options).getMessages().get(1).getContent();

        assertEquals("3673e6979ce46859ca511abe85ac01db2a9ab4256879562b73365a7c8b6d5827",
                sha256(prompt));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String cacheablePrefix(String prompt) {
        int marker = prompt.indexOf("Generated test statements:");
        return marker < 0 ? prompt : prompt.substring(0, marker);
    }
}
