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

class PostProcessingPromptRendererTest {

    private boolean originalAssertions;
    private boolean originalTestNames;
    private boolean originalVariableNames;
    private boolean originalComments;
    private boolean originalSectionBreaks;

    @BeforeEach
    void saveProperties() {
        originalAssertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        originalTestNames = Properties.LLM_POSTPROCESSING_TEST_NAMES;
        originalVariableNames = Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        originalComments = Properties.LLM_POSTPROCESSING_COMMENTS;
        originalSectionBreaks = Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_ASSERTIONS = originalAssertions;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = originalTestNames;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = originalVariableNames;
        Properties.LLM_POSTPROCESSING_COMMENTS = originalComments;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = originalSectionBreaks;
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

        PromptResult prompt = productionPrompt(test, true);
        String userPrompt = prompt.getMessages().get(1).getContent();

        assertTrue(userPrompt.contains("\"schemaVersion\":2"));
        assertTrue(userPrompt.contains("Prompt version: postprocessing-production-v2"));
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

        PromptResult prompt = productionPrompt(test, false);
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

        PromptResult prompt = productionPrompt(test, false);
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
    void productionBuildUsesFrozenProtocol() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        PostProcessingOptions options = PostProcessingOptions.fromProperties();
        String prompt = PostProcessingPromptRenderer.build(
                OracleContext.from(test, null, Collections.emptyList(), options),
                true, options).getMessages().get(1).getContent();

        assertTrue(prompt.contains("Prompt version: postprocessing-production-v2"));
        assertTrue(prompt.contains("\"schemaVersion\":2"));
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
        String prompt = PostProcessingPromptRenderer.build(
                OracleContext.from(test, null,
                        Collections.emptyList(), options),
                true, options).getMessages().get(1).getContent();

        assertEquals("a35a8be80cbe909d037b619e4c0569728d2a18629aa4d64098062766db4e46a7",
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

    private static PromptResult productionPrompt(DefaultTestCase test, boolean assertionsEnabled) {
        PostProcessingOptions options = PostProcessingOptions.fromProperties();
        OracleContext context = OracleContext.from(test, null,
                Collections.emptyList(), options);
        return PostProcessingPromptRenderer.build(context, assertionsEnabled, options);
    }
}
