/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License, or (at your
 * option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAssertionRepairerTest {

    @Test
    void stabilityFailuresAreNotRepairableAndObservedFailuresKeepExactDiagnostic() {
        String raw = "{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\","
                + "\"kind\":\"TRUE\",\"actual\":\"v0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(raw, booleanContext());
        LlmPostProcessingResponse accepted = new LlmPostProcessingResponse(1);

        List<LlmPostProcessingParseResult.Diagnostic> stability = Collections.singletonList(
                new LlmPostProcessingParseResult.Diagnostic(
                        LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION,
                        "assertions[a0]", "second execution changed the result"));
        assertTrue(LlmAssertionRepairer.collectRepairableRejectedAssertions(
                raw, parsed, parsed.getResponse(), accepted, stability).isEmpty());

        List<LlmPostProcessingParseResult.Diagnostic> observed = Collections.singletonList(
                new LlmPostProcessingParseResult.Diagnostic(
                        LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                        "assertions[a0]", "expected true but observed false"));
        List<LlmAssertionRepairer.RejectedAssertion> repairable =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(
                        raw, parsed, parsed.getResponse(), accepted, observed);
        assertEquals(1, repairable.size());
        assertTrue(repairable.get(0).getDiagnostics().get(0).contains("expected true but observed false"));
    }

    @Test
    void fencedInitialResponseStillProducesRepairCandidates() {
        String raw = "```json\n{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\","
                + "\"kind\":\"UNKNOWN\",\"actual\":\"v0\"}]}\n```";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(raw, booleanContext());

        List<LlmAssertionRepairer.RejectedAssertion> repairable =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(raw, parsed,
                        parsed.getResponse(), new LlmPostProcessingResponse(1), Collections.emptyList());

        assertEquals(1, repairable.size());
    }

    @Test
    void repairCorrelationUsesOriginalAssertionIndexWhenEarlierEntryHasNoId() {
        String raw = "{\"schemaVersion\":1,\"assertions\":["
                + "{\"kind\":\"TRUE\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"UNKNOWN\",\"actual\":\"v0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(raw, booleanContext());

        List<LlmAssertionRepairer.RejectedAssertion> repairable =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(
                        parsed, parsed.getResponse(), new LlmPostProcessingResponse(1),
                        Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList());

        assertEquals(1, repairable.size());
        assertEquals("a1", repairable.get(0).getAssertionId());
    }

    @Test
    void snippetHarnessCompilationFailuresAreNotSentBackForAssertionRepair() {
        String raw = "{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\","
                + "\"kind\":\"TRUE\",\"actual\":\"v0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(raw, booleanContext());
        List<LlmPostProcessingParseResult.Diagnostic> diagnostics = Collections.singletonList(
                new LlmPostProcessingParseResult.Diagnostic(
                        LlmPostProcessingParseResult.DiagnosticCode.COMPILE, "assertions[a0]",
                        "type Class does not take parameters",
                        LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY));

        assertTrue(LlmAssertionRepairer.collectRepairableRejectedAssertions(
                raw, parsed, parsed.getResponse(), new LlmPostProcessingResponse(1), diagnostics).isEmpty());
    }

    @Test
    void repairParseResultPreservesPerAssertionDiagnostics() {
        String raw = "```json\n[{\"assertionId\":\"a0\",\"kind\":\"UNKNOWN\","
                + "\"actual\":\"v0\"}]\n```";

        LlmPostProcessingParseResult result = LlmAssertionRepairer.parseRepairResponse(
                raw, booleanContext(), new HashSet<>(Arrays.asList("a0")));

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, result.getDiagnostics().size());
    }

    @Test
    void repairPromptUsesSpecificCorrectionAndOnlyReferencedVariableContext() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 9));
        LlmPostProcessingPromptContext promptContext = LlmPostProcessingPromptContext.from(test);
        String raw = "{\"schemaVersion\":2,\"assertions\":[{\"assertionId\":\"a0\","
                + "\"kind\":\"TRUE\",\"actual\":\"v0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                raw, promptContext.toParseContext());

        List<LlmAssertionRepairer.RejectedAssertion> rejected =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(raw, parsed,
                        parsed.getResponse(), new LlmPostProcessingResponse(2), Collections.emptyList());
        List<LlmMessage> messages = LlmAssertionRepairer.buildRepairMessages(promptContext, rejected);
        String repairPrompt = messages.get(1).getContent();

        assertTrue(repairPrompt.contains("exact required type"));
        assertTrue(repairPrompt.contains("s0 v0"));
        assertFalse(repairPrompt.contains("s1 v1"));
        assertTrue(repairPrompt.contains("\"schemaVersion\":2"));
    }

    @Test
    void productionRepairSnapshotPreservesCompatibilityPromptBytes() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingPromptContext promptContext = LlmPostProcessingPromptContext.from(test);
        OracleContext oracleContext = OracleContextFactory.capture(promptContext);
        LlmPostProcessingResponse response = new LlmPostProcessingResponse(2);
        response.addAssertion(new LlmPostProcessingResponse.AssertionProposal(
                "a0", LlmPostProcessingResponse.AssertionKind.TRUE,
                null, "v0", null, null));
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"assertions\":[{\"assertionId\":\"a0\","
                        + "\"kind\":\"TRUE\",\"actual\":\"v0\"}]}",
                promptContext.toParseContext());
        List<LlmAssertionRepairer.RejectedAssertion> rejected =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(
                        parsed, parsed.getResponse(), new LlmPostProcessingResponse(2),
                        Collections.emptyList());
        PostProcessingOptions options = PostProcessingOptions.fromProperties();

        assertEquals(
                LlmAssertionRepairer.buildRepairMessages(promptContext, rejected, options)
                        .get(1).getContent(),
                LlmAssertionRepairer.buildRepairMessages(oracleContext, rejected, options)
                        .get(1).getContent());
        assertEquals(1, response.getAssertions().size());
    }

    @Test
    void callablePolicyFailuresAreNotRepairable() {
        String raw = "{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\","
                + "\"kind\":\"TRUE\",\"actual\":\"v0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(raw, booleanContext());
        List<LlmPostProcessingParseResult.Diagnostic> diagnostics = Collections.singletonList(
                new LlmPostProcessingParseResult.Diagnostic(
                        LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD,
                        "assertions[a0].actual", "Method is not listed in the callable policy",
                        LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY));

        assertTrue(LlmAssertionRepairer.collectRepairableRejectedAssertions(
                raw, parsed, parsed.getResponse(), new LlmPostProcessingResponse(1), diagnostics).isEmpty());
    }

    @Test
    void schemaThreeRepairPromptCanCorrectMalformedPlacementRepresentation() {
        Properties.LlmPostProcessingPromptVariant original =
                Properties.LLM_POSTPROCESSING_PROMPT_VARIANT;
        try {
            Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                    Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
            DefaultTestCase test = new DefaultTestCase();
            test.addStatement(new IntPrimitiveStatement(test, 7));
            LlmPostProcessingPromptContext promptContext =
                    LlmPostProcessingPromptContext.from(test);
            String raw = "{\"schemaVersion\":3,\"assertions\":[{\"assertionId\":\"a0\","
                    + "\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\","
                    + "\"placement\":\"END_OF_TEST\"}]}";
            LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                    raw, promptContext.toParseContext());

            List<LlmAssertionRepairer.RejectedAssertion> rejected =
                    LlmAssertionRepairer.collectRepairableRejectedAssertions(
                            raw, parsed, parsed.getResponse(),
                            new LlmPostProcessingResponse(3), Collections.emptyList());
            assertEquals(1, rejected.size());

            String repairPrompt = LlmAssertionRepairer.buildRepairMessages(
                    promptContext, rejected).get(1).getContent();
            assertTrue(repairPrompt.contains(
                    "Preserve the semantic placement site"));
            assertTrue(repairPrompt.contains(
                    "correct that representation or required field"));
            assertTrue(repairPrompt.contains(
                    "placement must be a JSON object with a site field"));
            assertTrue(repairPrompt.contains(
                    "Encode placement as an object with a valid site field"));
            assertTrue(repairPrompt.contains("{\"site\":\"END_OF_TEST\"}"));
            assertTrue(repairPrompt.contains(
                    "Safe assertion sites:\n- END_OF_TEST"));
            assertFalse(repairPrompt.contains(
                    "Preserve each assertion placement exactly"));
        } finally {
            Properties.LLM_POSTPROCESSING_PROMPT_VARIANT = original;
        }
    }

    private static LlmPostProcessingResponseParser.ParseContext booleanContext() {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        types.put("v0", "boolean");
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0")), new HashSet<>(Arrays.asList("v0")),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), types);
    }
}
