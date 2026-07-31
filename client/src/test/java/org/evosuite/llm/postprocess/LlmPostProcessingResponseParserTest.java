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
import org.evosuite.llm.postprocess.LlmPostProcessingParseResult.DiagnosticCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessingResponseParserTest {

    private int originalMaxAssertions;
    private int originalMaxComments;
    private int originalMaxCommentChars;
    private int originalMaxExpressionChars;
    private int originalMaxExpressionNodes;
    private int originalMaxChainDepth;
    private boolean originalAllowChainedCalls;
    private int originalMaxConstructedArrayElements;
    private int originalMaxLiteralChars;
    private boolean originalAllowImmutableConstructors;
    private String originalImmutableTypes;
    private String originalPureStaticAllowlist;
    private Properties.LlmPostProcessingPromptVariant originalPromptVariant;

    @BeforeEach
    void saveProperties() {
        originalMaxAssertions = Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST;
        originalMaxComments = Properties.LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST;
        originalMaxCommentChars = Properties.LLM_POSTPROCESSING_MAX_COMMENT_CHARS;
        originalMaxExpressionChars = Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_CHARS;
        originalMaxExpressionNodes = Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_NODES;
        originalMaxChainDepth = Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH;
        originalAllowChainedCalls = Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS;
        originalMaxConstructedArrayElements = Properties.LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS;
        originalMaxLiteralChars = Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS;
        originalAllowImmutableConstructors = Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS;
        originalImmutableTypes = Properties.LLM_POSTPROCESSING_IMMUTABLE_TYPES;
        originalPureStaticAllowlist = Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST;
        originalPromptVariant = Properties.LLM_POSTPROCESSING_PROMPT_VARIANT;
        Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST = 5;
        Properties.LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST = 3;
        Properties.LLM_POSTPROCESSING_MAX_COMMENT_CHARS = 160;
        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_CHARS = 500;
        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_NODES = 64;
        Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH = 4;
        Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS = true;
        Properties.LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS = 16;
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 200;
        Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS = true;
        Properties.LLM_POSTPROCESSING_IMMUTABLE_TYPES = "";
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = "";
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST = originalMaxAssertions;
        Properties.LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST = originalMaxComments;
        Properties.LLM_POSTPROCESSING_MAX_COMMENT_CHARS = originalMaxCommentChars;
        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_CHARS = originalMaxExpressionChars;
        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_NODES = originalMaxExpressionNodes;
        Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH = originalMaxChainDepth;
        Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS = originalAllowChainedCalls;
        Properties.LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS = originalMaxConstructedArrayElements;
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = originalMaxLiteralChars;
        Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS = originalAllowImmutableConstructors;
        Properties.LLM_POSTPROCESSING_IMMUTABLE_TYPES = originalImmutableTypes;
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = originalPureStaticAllowlist;
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT = originalPromptVariant;
    }

    @Test
    void parse_schemaThreeAcceptsAdvertisedInCatchSite() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P11_EXCEPTION_ADJACENT_ASSERTIONS;
        HashSet<String> statements = new HashSet<>(Arrays.asList("s0", "s1"));
        HashSet<String> variables = new HashSet<>(Arrays.asList("v0", "e0"));
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callables = new HashSet<>();
        callables.add(new LlmPostProcessingResponseParser.CallableMethod(
                "e0", "java.lang.Throwable", "getMessage", "()Ljava/lang/String;",
                "java.lang.String"));
        Map<String, String> types = new LinkedHashMap<>();
        types.put("v0", "int");
        types.put("e0", "java.lang.Throwable");
        LlmPostProcessingResponseParser.ParseContext throwingContext =
                LlmPostProcessingResponseParser.context(
                        statements, variables, callables, Collections.<String>emptySet(),
                        Collections.<String>emptySet(), types,
                        Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap(),
                        "s1");
        String json = "{\"schemaVersion\":3,\"assertions\":[{"
                + "\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"\\\"boom\\\"\",\"actual\":\"e0.getMessage()\","
                + "\"placement\":{\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}}]}";

        LlmPostProcessingParseResult result =
                LlmPostProcessingResponseParser.parse(json, throwingContext);

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getDiagnostics().isEmpty(), result.getDiagnostics().toString());
        assertEquals(LlmPostProcessingResponse.AssertionSite.IN_CATCH,
                result.getResponse().getAssertions().get(0).getSite());
    }

    @Test
    void parse_schemaThreeRejectsUnavailableExceptionSite() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P11_EXCEPTION_ADJACENT_ASSERTIONS;
        String json = "{\"schemaVersion\":3,\"assertions\":[{"
                + "\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0 > 0\","
                + "\"placement\":{\"site\":\"IN_CATCH\",\"exceptionId\":\"e0\"}}]}";

        LlmPostProcessingParseResult result =
                LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getCode() == DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_validResponse_acceptsIndependentEditCategories() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"withdrawReducesBalance\","
                + "\"variableNames\":{\"v0\":\"account\",\"v1\":\"amount\"},"
                + "\"comments\":[{\"afterStatementId\":\"s1\",\"text\":\"Withdraw part of the balance.\"}],"
                + "\"sectionBreaksAfter\":[\"s0\",\"s1\"],"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"90\",\"actual\":\"v0.getBalance()\",\"delta\":null,"
                + "\"purpose\":\"Balance is reduced.\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getDiagnostics().isEmpty());
        LlmPostProcessingResponse response = result.getResponse();
        assertEquals(1, response.getSchemaVersion());
        assertEquals("withdrawReducesBalance", response.getTestName());
        assertEquals("account", response.getVariableNames().get("v0"));
        assertEquals(1, response.getComments().size());
        assertTrue(response.getSectionBreaksAfter().contains("s0"));
        assertEquals(1, response.getAssertions().size());
        assertEquals(LlmPostProcessingResponse.AssertionKind.EQUALS,
                response.getAssertions().get(0).getKind());
        assertEquals("Balance is reduced.", response.getAssertions().get(0).getPurpose());
    }

    @Test
    void parse_exposesImmutableDecodedResponseBoundary() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"testName\":\"readable\","
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                        + "\"actual\":\"v0 > 0\"}]}", context());

        DecodedPostProcessingResponse decoded = result.getDecodedResponse();
        assertNotNull(decoded);
        assertSame(result.getResponse(), decoded.getResponse());
        assertEquals(1, decoded.getProposedCounts().getAssertions());
        assertEquals(1, decoded.getRawAssertions().size());
        assertEquals("a0", decoded.getRawAssertions().get(0).getAssertionId());
    }

    @Test
    void assertionDiagnosticsCarryTypedIdentityAndWireIndex() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0;\"},"
                        + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0\"}]}",
                context());

        LlmPostProcessingParseResult.Diagnostic diagnostic = result.getDiagnostics().stream()
                .filter(value -> value.getPath().startsWith("assertions["))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("a0", diagnostic.getAssertionId());
        assertEquals(0, diagnostic.getAssertionIndex());
        assertEquals(0, result.getRawAssertions().get(0).getIndex());
    }

    @Test
    void rawAssertionsRetainEntriesWithoutAnAssertionId() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"assertions\":["
                        + "{\"kind\":\"TRUE\",\"actual\":\"v0\"},"
                        + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0\"}]}",
                context());

        assertEquals(2, result.getRawAssertions().size());
        assertEquals(0, result.getRawAssertions().get(0).getIndex());
        assertNull(result.getRawAssertions().get(0).getAssertionId());
        assertEquals(1, result.getRawAssertions().get(1).getIndex());
    }

    @Test
    void diagnosticsCarryTypedRepairability() {
        LlmPostProcessingParseResult.Diagnostic repairable =
                new LlmPostProcessingParseResult.Diagnostic(
                        DiagnosticCode.INVALID_FIELD, "assertions[a0].actual", "bad expression");
        LlmPostProcessingParseResult.Diagnostic policyRejected =
                new LlmPostProcessingParseResult.Diagnostic(
                        DiagnosticCode.INVALID_FIELD, "assertions[a0].actual", "unknown variable v9",
                        LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY);

        assertEquals(LlmPostProcessingParseResult.Repairability.REPAIRABLE,
                repairable.getRepairability());
        assertEquals(LlmPostProcessingParseResult.Repairability.NON_REPAIRABLE,
                policyRejected.getRepairability());
        assertEquals(LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY,
                policyRejected.getReason());

        LlmPostProcessingParseResult.Diagnostic wordingChange =
                new LlmPostProcessingParseResult.Diagnostic(
                        DiagnosticCode.INVALID_FIELD, "assertions[a0].actual",
                        "callable policy rejected this expression");
        assertEquals(LlmPostProcessingParseResult.Repairability.REPAIRABLE,
                wordingChange.getRepairability());
    }

    @Test
    void parse_normalizesProposedVariableNamesInAssertionExpressions() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"variableNames\":{\"v0\":\"account\"},"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"90\",\"actual\":\"account.getBalance()\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals("v0.getBalance()", result.getResponse().getAssertions().get(0).getActual());
    }

    @Test
    void parse_markdownFencedJson_acceptsResponse() {
        String json = "```json\n"
                + "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"loadsDictionary\","
                + "\"variableNames\":{\"v0\":\"parser\"}"
                + "}\n"
                + "```";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("loadsDictionary", result.getResponse().getTestName());
        assertEquals("parser", result.getResponse().getVariableNames().get("v0"));
    }

    @Test
    void parse_plainMarkdownFence_acceptsResponse() {
        String json = "```\n"
                + "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"loadsDictionary\""
                + "}\n"
                + "```";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("loadsDictionary", result.getResponse().getTestName());
    }

    @Test
    void parse_unknownFieldsProduceDiagnosticsWithoutDiscardingValidEdits() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"keepsValidFields\","
                + "\"futureField\":\"ignored\","
                + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Prepare.\",\"future\":\"ignored\"}],"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0 > 0\","
                + "\"future\":\"ignored\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("keepsValidFields", result.getResponse().getTestName());
        assertEquals(1, result.getResponse().getComments().size());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals(3, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_missingSchemaVersion_isInfrastructureFailure() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"testName\":\"someTest\"}", context());

        assertTrue(result.isInfrastructureFailure());
        assertNull(result.getResponse());
        assertTrue(result.getInfrastructureFailureReason().contains("schemaVersion"));
    }

    @Test
    void parse_schemaThreeIsReadableForReplay() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":3,\"testName\":\"someTest\"}", context());

        assertFalse(result.isInfrastructureFailure());
        assertNotNull(result.getResponse());
        assertEquals(3, result.getResponse().getSchemaVersion());
    }

    @Test
    void parse_unknownSchemaVersionsFailClosed() {
        for (int schema : new int[]{0, 4, 99}) {
            LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                    "{\"schemaVersion\":" + schema + "}", context());
            assertTrue(result.isInfrastructureFailure(), "schema " + schema);
            assertNull(result.getResponse());
        }
    }

    @Test
    void parse_schemaTwoRecordsStructuredNoAssertionDecision() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"assertionDecision\":\"NO_SAFE_ORACLE\","
                        + "\"noAssertionReason\":\"ONLY_SETUP_VALUES\",\"assertions\":[]}", context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("NO_SAFE_ORACLE", result.getResponse().getAssertionDecision());
        assertEquals("ONLY_SETUP_VALUES", result.getResponse().getNoAssertionReason());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_invalidNoAssertionReasonDoesNotInvalidateOtherwiseEmptyResponse() {
        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"assertionDecision\":\"NO_SAFE_ORACLE\","
                        + "\"noAssertionReason\":\"MADE_UP\",\"assertions\":[]}", context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertNull(result.getResponse().getNoAssertionReason());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_unknownIdsRejectOnlyAffectedEntries() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"variableNames\":{\"v0\":\"account\",\"v9\":\"missing\"},"
                + "\"comments\":[{\"afterStatementId\":\"s9\",\"text\":\"bad\"},"
                + "{\"afterStatementId\":\"s1\",\"text\":\"good\"}],"
                + "\"sectionBreaksAfter\":[\"s0\",\"s9\"]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("account", result.getResponse().getVariableNames().get("v0"));
        assertFalse(result.getResponse().getVariableNames().containsKey("v9"));
        assertEquals(1, result.getResponse().getComments().size());
        assertTrue(result.getResponse().getSectionBreaksAfter().contains("s0"));
        assertFalse(result.getResponse().getSectionBreaksAfter().contains("s9"));
        assertEquals(3, count(result, DiagnosticCode.UNKNOWN_ID));
    }

    @Test
    void parse_duplicatesAreNormalizedWithDiagnostics() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"variableNames\":{\"v0\":\"name\",\"v1\":\"name\"},"
                + "\"comments\":[{\"afterStatementId\":\"s1\",\"text\":\"same\"},"
                + "{\"afterStatementId\":\"s1\",\"text\":\"same\"}],"
                + "\"sectionBreaksAfter\":[\"s0\",\"s0\"],"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.isActive()\"},"
                + "{\"assertionId\":\"a0\",\"kind\":\"FALSE\",\"actual\":\"v0.isEmpty()\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getVariableNames().size());
        assertEquals(1, result.getResponse().getComments().size());
        assertEquals(1, result.getResponse().getSectionBreaksAfter().size());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals(3, count(result, DiagnosticCode.DUPLICATE));
    }

    @Test
    void parse_rejectsDuplicateAssertionExpressions() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a0", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.DUPLICATE));
    }

    @Test
    void parse_rejectsAssertionsAlreadyRepresentedByObservedCandidateFacts() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}"
                + "]}";
        HashSet<String> observedCandidateKeys = new HashSet<>(
                Arrays.asList("EQUALS|7|v0|"));

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0")),
                        new HashSet<>(Arrays.asList("v0")),
                        new HashSet<LlmPostProcessingResponseParser.CallableMethod>(),
                        observedCandidateKeys));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.DUPLICATE));
    }

    @Test
    void parse_rejectsDirectSetupInputAssertionsButAllowsRelationalUse() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0 > 0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0")),
                        new HashSet<>(Arrays.asList("v0")),
                        new HashSet<LlmPostProcessingResponseParser.CallableMethod>(),
                        new HashSet<String>(),
                        new HashSet<>(Arrays.asList("v0"))));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.DUPLICATE));
    }

    @Test
    void parse_acceptsNumericRelationalKindsAndRejectsDegenerateOrNonNumeric() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"GREATER\",\"expected\":\"v1\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"GREATER_EQUALS\",\"expected\":\"v0\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"LESS\",\"expected\":\"5\",\"actual\":\"v2\"}"
                + "]}";
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "int");
        variableTypes.put("v1", "int");
        variableTypes.put("v2", "java.lang.String");

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0", "s1", "s2")),
                        new HashSet<>(Arrays.asList("v0", "v1", "v2")),
                        new HashSet<LlmPostProcessingResponseParser.CallableMethod>(),
                        new HashSet<String>(),
                        new HashSet<String>(),
                        variableTypes));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a0", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(LlmPostProcessingResponse.AssertionKind.GREATER,
                result.getResponse().getAssertions().get(0).getKind());
        // a1 is degenerate (v0 >= v0 is always true); a2 compares a String with LESS.
        assertEquals(1, count(result, DiagnosticCode.DUPLICATE));
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsObviousTautologicalAssertions() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"v0\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"SAME\",\"expected\":\"v0\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"TRUE\",\"actual\":\"v0 == v0\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"FALSE\",\"actual\":\"v0 != v0\"},"
                + "{\"assertionId\":\"a4\",\"kind\":\"TRUE\",\"actual\":\"v0 != v1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a4", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(4, count(result, DiagnosticCode.DUPLICATE));
    }

    @Test
    void parse_limitsCommentsAndAssertions() {
        Properties.LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST = 1;
        Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST = 1;
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"one\"},"
                + "{\"afterStatementId\":\"s1\",\"text\":\"two\"}],"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.isActive()\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"FALSE\",\"actual\":\"v0.isEmpty()\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getComments().size());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals(2, count(result, DiagnosticCode.LIMIT_EXCEEDED));
    }

    @Test
    void parse_invalidAssertionDoesNotInvalidateNamesOrComments() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"validName\","
                + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"still accepted\"}],"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"42;\",\"actual\":\"v0.getValue()\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("validName", result.getResponse().getTestName());
        assertEquals(1, result.getResponse().getComments().size());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_sanitizesCommentsAndRejectsUnsafeCommentText() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"comments\":["
                + "{\"afterStatementId\":\"s0\",\"text\":\"// useful comment\\nwith newline\"},"
                + "{\"afterStatementId\":\"s1\",\"text\":\"*/ closes block\"},"
                + "{\"afterStatementId\":\"s2\",\"text\":\"@Test\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getComments().size());
        assertEquals("useful comment with newline", result.getResponse().getComments().get(0).getText());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_unsupportedAssertionKindRejectsOnlyThatAssertion() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EVENTUALLY_EQUALS\","
                + "\"expected\":\"1\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.UNSUPPORTED_KIND));
    }

    @Test
    void parse_acceptsCollectionAssertionAliasesAndRegressionIntent() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"CONTAINS\","
                + "\"container\":\"v1\",\"element\":\"\\\"x\\\"\",\"intent\":\"REGRESSION\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"SIZE_EQUALS\","
                + "\"target\":\"v1\",\"size\":\"1\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"IS_EMPTY\",\"target\":\"v1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                collectionPredicateContext());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals(3, result.getResponse().getAssertions().size());
        assertEquals(LlmPostProcessingResponse.AssertionKind.CONTAINS,
                result.getResponse().getAssertions().get(0).getKind());
        assertEquals("v1", result.getResponse().getAssertions().get(0).getActual());
        assertEquals("\"x\"", result.getResponse().getAssertions().get(0).getExpected());
        assertEquals("REGRESSION", result.getResponse().getAssertions().get(0).getIntent());
    }

    @Test
    void parse_rejectsSpecificationIntentForRegressionSchema() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"NOT_NULL\","
                + "\"actual\":\"v1\",\"intent\":\"SPECIFICATION\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.UNSUPPORTED_KIND));
    }

    @Test
    void parse_normalizesLegacyAssertionPlacementToEndOfTest() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"NOT_NULL\",\"actual\":\"v1\","
                + "\"placement\":{\"afterStatementId\":\"s1\"}},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v2\","
                + "\"placement\":{\"afterStatementId\":\"s1\"}}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getAssertions().size());
        assertNull(result.getResponse().getAssertions().get(0).getAfterStatementId());
        assertNull(result.getResponse().getAssertions().get(1).getAfterStatementId());
        assertEquals(0, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_invalidJavaExpressionRejectsOnlyThatAssertion() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"testName\":\"stillValid\","
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0 +\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals("stillValid", result.getResponse().getTestName());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_unknownVariableIdInAssertionExpressionRejectsOnlyThatAssertion() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"42\",\"actual\":\"v9.getValue()\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.UNKNOWN_ID));
    }

    @Test
    void parse_rejectsAssertionOperandShapeViolations() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"expected\":\"true\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\",\"delta\":\"0.1\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"SAME\",\"expected\":\"v0\",\"actual\":\"v1\",\"delta\":\"0\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"EQUALS\",\"expected\":\"1\",\"actual\":\"v0\",\"delta\":\"0.1\"},"
                + "{\"assertionId\":\"a4\",\"kind\":\"FALSE\",\"actual\":\"v0.isEmpty()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(3, result.getResponse().getAssertions().size());
        assertEquals("a0", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals("a3", result.getResponse().getAssertions().get(1).getAssertionId());
        assertEquals("a4", result.getResponse().getAssertions().get(2).getAssertionId());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_expressionResourceLimitsRejectOnlyThatAssertion() {
        Properties.LLM_POSTPROCESSING_MAX_EXPRESSION_NODES = 3;
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 4;
        Properties.LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS = 2;
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.getValue().equals(1)\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"\\\"abcdef\\\"\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"EQUALS\",\"expected\":\"new int[]{1,2,3}\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a3", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(3, count(result, DiagnosticCode.LIMIT_EXCEEDED));
    }

    @Test
    void parse_allowsOneDimensionalLiteralArrayConstruction() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new String[]{\\\"a\\\", null}\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_rejectsSizedNonLiteralAndMultidimensionalArrayConstruction() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new int[2]\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new int[]{v0}\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new int[][]{{1}}\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a3", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(3, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsUnsupportedMutationAndCodeBlockExpressionConstructs() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0 = 3\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"++v0\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"TRUE\",\"actual\":\"v0.stream().anyMatch(x -> x != null)\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"TRUE\",\"actual\":\"v0.stream().map(String::trim).count() > 0\"},"
                + "{\"assertionId\":\"a4\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a4", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(4, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsRawAssertionCallsInsideExpressions() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"assertTrue(v0 > 0)\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"FALSE\",\"actual\":\"org.junit.Assert.fail()\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"NOT_NULL\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a2", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsNonAllowlistedConstructorsButAllowsImmutableConstructors() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new java.util.ArrayList()\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new java.math.BigDecimal(\\\"1\\\")\",\"actual\":\"v0\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_respectsImmutableConstructorDisableFlagAndConfiguredExtensions() {
        Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS = false;
        String disabledJson = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new java.math.BigDecimal(\\\"1\\\")\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult disabled = LlmPostProcessingResponseParser.parse(disabledJson, context());

        assertFalse(disabled.isInfrastructureFailure());
        assertTrue(disabled.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(disabled, DiagnosticCode.INVALID_FIELD));

        Properties.LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS = true;
        Properties.LLM_POSTPROCESSING_IMMUTABLE_TYPES = "com.example.Value";
        String configuredJson = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"new Value(1)\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult configured = LlmPostProcessingResponseParser.parse(configuredJson, context());

        assertFalse(configured.isInfrastructureFailure());
        assertEquals(1, configured.getResponse().getAssertions().size());
        assertTrue(configured.getDiagnostics().isEmpty());
    }

    @Test
    void parse_allowsBuiltInPureStaticCallsAndConfiguredPureStaticCalls() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST =
                "com.example.Values#normalize(I)I";
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Math.abs(v0)\",\"actual\":\"1\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Values.normalize(v0)\",\"actual\":\"1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getAssertions().size());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_requiresConfiguredPureStaticEntriesToUseFullJvmDescriptors() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST =
                "com.example.Values#normalize(I),"
                        + "com.example.Values#coerce";
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Values.normalize(v0)\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Values.coerce(v0)\",\"actual\":\"v1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsConfiguredPureStaticTypeWildcard() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST =
                "com.example.Values#*";
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Values.normalize(v0)\",\"actual\":\"v1\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_matchesConfiguredStaticDescriptorAgainstArgumentTypes() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST =
                "com.example.Values#normalize(I)I";
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Values.normalize(v1)\",\"actual\":\"1\"}]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_matchesInstanceCallableDescriptorAgainstArgumentTypes() {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "com.example.Converter");
        variableTypes.put("v1", "java.lang.String");
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callables = new HashSet<>();
        callables.add(new LlmPostProcessingResponseParser.CallableMethod(
                "v0", "com.example.Converter", "convert", "(I)I", "int"));
        LlmPostProcessingResponseParser.ParseContext parseContext = LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1")),
                new HashSet<>(Arrays.asList("v0", "v1")), callables,
                new HashSet<String>(), new HashSet<String>(), variableTypes);
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"v0.convert(v1)\",\"actual\":\"1\"}]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, parseContext);

        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsPrimitiveWideningFollowedByBoxingForCallableDescriptor() {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "com.example.Consumer");
        variableTypes.put("v1", "int");
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callables = new HashSet<>();
        callables.add(new LlmPostProcessingResponseParser.CallableMethod(
                "v0", "com.example.Consumer", "accept", "(Ljava/lang/Long;)Z", "boolean"));
        LlmPostProcessingResponseParser.ParseContext parseContext = LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1")),
                new HashSet<>(Arrays.asList("v0", "v1")), callables,
                new HashSet<String>(), new HashSet<String>(), variableTypes);
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                + "\"actual\":\"v0.accept(v1)\"}]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, parseContext);

        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_allowsWrapperUnboxingFollowedByPrimitiveWideningForCallableDescriptor() {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "com.example.Consumer");
        variableTypes.put("v1", "java.lang.Integer");
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callables = new HashSet<>();
        callables.add(new LlmPostProcessingResponseParser.CallableMethod(
                "v0", "com.example.Consumer", "accept", "(J)Z", "boolean"));
        LlmPostProcessingResponseParser.ParseContext parseContext = LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1")),
                new HashSet<>(Arrays.asList("v0", "v1")), callables,
                new HashSet<String>(), new HashSet<String>(), variableTypes);
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                + "\"actual\":\"v0.accept(v1)\"}]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, parseContext);

        assertEquals(1, result.getResponse().getAssertions().size());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_allowsBuiltInPureStaticValueFactories() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"BigDecimal.valueOf(3L)\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\","
                + "\"actual\":\"java.util.Optional.empty()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getAssertions().size());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_deniesEnvironmentSensitiveStaticCallsEvenWhenConfigured() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST =
                "java.lang.System#*,java.lang.Runtime#*,java.nio.file.Files#*";
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                + "\"actual\":\"System.currentTimeMillis() > 0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\","
                + "\"actual\":\"Runtime.getRuntime()\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"NOT_NULL\","
                + "\"actual\":\"Files.createTempFile(\\\"x\\\", \\\"y\\\")\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Math.abs(v0)\",\"actual\":\"v1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a3", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(3, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_deniesMathRandomEvenWhenMathWildcardIsAllowlisted() {
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = "java.lang.Math#*,Math#*";
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                + "\"actual\":\"Math.random() < 0.9\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Math.abs(v0)\",\"actual\":\"v1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_deniesEnvironmentSensitiveInstanceCallsEvenWhenCallable() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"NOT_NULL\",\"actual\":\"v0.getClass()\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0.wait()\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"TRUE\",\"actual\":\"v0.isReady()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0")),
                        new HashSet<>(Arrays.asList("v0")),
                        new HashSet<>(Arrays.asList(
                                new LlmPostProcessingResponseParser.CallableMethod("v0", "getClass", 0),
                                new LlmPostProcessingResponseParser.CallableMethod("v0", "wait", 0),
                                new LlmPostProcessingResponseParser.CallableMethod("v0", "isReady", 0)))));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a2", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsNonAllowlistedStaticCalls() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\","
                + "\"actual\":\"System.currentTimeMillis() > 0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0.toString()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsNonAllowlistedInstanceCalls() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.isReady()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json,
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0")),
                        new HashSet<>(Arrays.asList("v0"))));

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_enforcesMemberChainDepthLimit() {
        Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH = 2;
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.getA().getB().isReady()\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0.isReady()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.LIMIT_EXCEEDED));
    }

    @Test
    void parse_enforcesTypedCanonicalOperandMatrix() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NULL\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"SAME\",\"expected\":\"v0\",\"actual\":\"v1\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"EQUALS\",\"expected\":\"\\\"x\\\"\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a4\",\"kind\":\"TRUE\",\"actual\":\"v2\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a4", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(4, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_addsExactDeltaForFloatingEqualityAndRejectsInvalidExplicitDelta() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"1.0\",\"actual\":\"v3\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"1\",\"actual\":\"v0\",\"delta\":\"0.1\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"EQUALS\",\"expected\":\"1.0\",\"actual\":\"v3\",\"delta\":\"-0.1\"},"
                + "{\"assertionId\":\"a3\",\"kind\":\"EQUALS\",\"expected\":\"1.0\",\"actual\":\"v3\",\"delta\":\"0.1\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getAssertions().size());
        assertEquals("a0", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals("0.0D", result.getResponse().getAssertions().get(0).getDelta());
        assertEquals("a3", result.getResponse().getAssertions().get(1).getAssertionId());
        assertEquals(2, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_canonicalizesRuntimeSpecialFloatingTokensUsingDeclaredType() {
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"Infinity\",\"actual\":\"v3\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\","
                + "\"expected\":\"-Infinity\",\"actual\":\"v7\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"EQUALS\","
                + "\"expected\":\"NaN\",\"actual\":\"v3\"}]}";

        LlmPostProcessingParseResult result =
                LlmPostProcessingResponseParser.parse(json, typedContext());

        assertTrue(result.getDiagnostics().isEmpty(), result.getDiagnostics().toString());
        assertEquals(3, result.getResponse().getAssertions().size());
        assertEquals("Double.POSITIVE_INFINITY",
                result.getResponse().getAssertions().get(0).getExpected());
        assertEquals("0.0D", result.getResponse().getAssertions().get(0).getDelta());
        assertEquals("Float.NEGATIVE_INFINITY",
                result.getResponse().getAssertions().get(1).getExpected());
        assertEquals("0.0F", result.getResponse().getAssertions().get(1).getDelta());
        assertEquals("Double.NaN",
                result.getResponse().getAssertions().get(2).getExpected());
    }

    @Test
    void parse_normalizesMechanicalAssertionShapeErrorsBeforeValidation() {
        String json = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v2\",\"expected\":false},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"actual\":\"v0\",\"value\":7}]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertEquals(2, result.getResponse().getAssertions().size(),
                result.getDiagnostics().stream().map(diagnostic -> diagnostic.getMessage())
                        .collect(java.util.stream.Collectors.joining(" | ")));
        assertEquals("v2", result.getResponse().getAssertions().get(0).getActual());
        assertEquals("7", result.getResponse().getAssertions().get(1).getExpected());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_selectsValidatedCandidateByStableId() {
        Map<String, LlmPostProcessingResponseParser.SelectableCandidate> candidates = new LinkedHashMap<>();
        candidates.put("c0", new LlmPostProcessingResponseParser.SelectableCandidate(
                LlmPostProcessingResponse.AssertionKind.EQUALS, "7", "v0", null));
        LlmPostProcessingResponseParser.ParseContext context = LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0")), new HashSet<>(Arrays.asList("v0")),
                new HashSet<LlmPostProcessingResponseParser.CallableMethod>(),
                new HashSet<>(Arrays.asList("EQUALS|7|v0|")), new HashSet<String>(),
                new LinkedHashMap<String, String>(), candidates);

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}",
                context);

        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("7", result.getResponse().getAssertions().get(0).getExpected());
        assertEquals("c0", result.getResponse().getAssertions().get(0).getCandidateId());
        assertEquals("SELECTED_CANDIDATE", result.getResponse().getAssertions().get(0).getSource());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_revalidatesSelectedCandidateAgainstDeclaredOperandTypes() {
        Map<String, LlmPostProcessingResponseParser.SelectableCandidate> candidates = new LinkedHashMap<>();
        candidates.put("c0", new LlmPostProcessingResponseParser.SelectableCandidate(
                LlmPostProcessingResponse.AssertionKind.EQUALS, "0.0F", "v0", "0.0F"));
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "java.lang.Number");
        LlmPostProcessingResponseParser.ParseContext parseContext =
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Collections.singletonList("s0")),
                        new HashSet<>(Collections.singletonList("v0")),
                        Collections.<LlmPostProcessingResponseParser.CallableMethod>emptySet(),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        variableTypes, candidates);

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":1,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}",
                parseContext);

        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_usesValidatedImplicitPlacementForThrowingCandidateSelection() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P11_EXCEPTION_ADJACENT_ASSERTIONS;
        Map<String, LlmPostProcessingResponseParser.SelectableCandidate> candidates = new LinkedHashMap<>();
        candidates.put("c0", new LlmPostProcessingResponseParser.SelectableCandidate(
                LlmPostProcessingResponse.AssertionKind.EQUALS, "7", "v0", null)
                .withDefaultPlacement(
                        LlmPostProcessingResponse.AssertionSite.BEFORE_TRY, "s0", null));
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "int");
        LlmPostProcessingResponseParser.ParseContext throwingContext =
                LlmPostProcessingResponseParser.context(
                        new HashSet<>(Arrays.asList("s0", "s1")),
                        new HashSet<>(Collections.singletonList("v0")),
                        Collections.<LlmPostProcessingResponseParser.CallableMethod>emptySet(),
                        Collections.<String>emptySet(), Collections.<String>emptySet(),
                        variableTypes, candidates, "s1");

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":3,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}",
                throwingContext);

        assertTrue(result.getDiagnostics().isEmpty(), result.getDiagnostics().toString());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals(LlmPostProcessingResponse.AssertionSite.BEFORE_TRY,
                result.getResponse().getAssertions().get(0).getSite());
        assertEquals("s0", result.getResponse().getAssertions().get(0).getAfterStatementId());
    }

    @Test
    void parse_enforcesArrayOperandCompatibility() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"NOT_EQUALS\",\"expected\":\"v4\",\"actual\":\"v5\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"EQUALS\",\"expected\":\"v4\",\"actual\":\"v6\"},"
                + "{\"assertionId\":\"a2\",\"kind\":\"EQUALS\",\"expected\":\"v4\",\"actual\":\"v5\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, typedContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a2", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, count(result, DiagnosticCode.UNSUPPORTED_KIND));
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_allowsTypeResolvedChainedCallableMembers() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.getCart().contains(v1)\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"NOT_NULL\",\"actual\":\"v0.getCart()\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, chainedContext(true));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(2, result.getResponse().getAssertions().size());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void parse_rejectsChainedCallableMembersWhenDisabled() {
        Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS = false;
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.getCart().contains(v1)\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, chainedContext(true));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(0, result.getResponse().getAssertions().size());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    @Test
    void parse_rejectsChainedCallableMembersWhenTypeMethodIsNotAdvertised() {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"TRUE\",\"actual\":\"v0.getCart().contains(v1)\"}"
                + "]}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, chainedContext(false));

        assertFalse(result.isInfrastructureFailure());
        assertEquals(0, result.getResponse().getAssertions().size());
        assertEquals(1, count(result, DiagnosticCode.INVALID_FIELD));
    }

    private static LlmPostProcessingResponseParser.ParseContext context() {
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callableMethods = new HashSet<>(
                Arrays.asList(
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "getBalance", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "isActive", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "isEmpty", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "getValue", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "isReady", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "stream", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v0", "getA", 0)));
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1", "s2")),
                new HashSet<>(Arrays.asList("v0", "v1")),
                callableMethods);
    }

    private static LlmPostProcessingResponseParser.ParseContext typedContext() {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "int");
        variableTypes.put("v1", "java.lang.String");
        variableTypes.put("v2", "boolean");
        variableTypes.put("v3", "double");
        variableTypes.put("v4", "int[]");
        variableTypes.put("v5", "int[]");
        variableTypes.put("v6", "long[]");
        variableTypes.put("v7", "float");
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7")),
                new HashSet<>(Arrays.asList("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7")),
                new HashSet<LlmPostProcessingResponseParser.CallableMethod>(),
                new HashSet<String>(),
                new HashSet<String>(),
                variableTypes);
    }

    private static LlmPostProcessingResponseParser.ParseContext collectionPredicateContext() {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v1", "java.util.List");
        HashSet<LlmPostProcessingResponseParser.CallableMethod> callableMethods = new HashSet<>(
                Arrays.asList(
                        new LlmPostProcessingResponseParser.CallableMethod(
                                "v1", "java.util.List", "contains", "(Ljava/lang/Object;)Z", "boolean"),
                        new LlmPostProcessingResponseParser.CallableMethod("v1", "size", 0),
                        new LlmPostProcessingResponseParser.CallableMethod("v1", "isEmpty", 0)));
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1")),
                new HashSet<>(Arrays.asList("v1")),
                callableMethods,
                new HashSet<String>(),
                new HashSet<String>(),
                variableTypes);
    }

    private static LlmPostProcessingResponseParser.ParseContext chainedContext(boolean includeTypeContains) {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        variableTypes.put("v0", "com.example.Order");
        variableTypes.put("v1", "com.example.Item");

        HashSet<LlmPostProcessingResponseParser.CallableMethod> callableMethods = new HashSet<>();
        callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                "v0", "com.example.Order", "getCart", 0, "com.example.Cart"));
        if (includeTypeContains) {
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    null, "com.example.Cart", "contains", "(Lcom/example/Item;)Z", "boolean"));
        }
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0", "s1")),
                new HashSet<>(Arrays.asList("v0", "v1")),
                callableMethods,
                new HashSet<String>(),
                new HashSet<String>(),
                variableTypes);
    }

    private static int count(LlmPostProcessingParseResult result, DiagnosticCode code) {
        int count = 0;
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getCode() == code) {
                count++;
            }
        }
        return count;
    }
}
