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
    void repairParseResultPreservesPerAssertionDiagnostics() {
        String raw = "```json\n[{\"assertionId\":\"a0\",\"kind\":\"UNKNOWN\","
                + "\"actual\":\"v0\"}]\n```";

        LlmPostProcessingParseResult result = LlmAssertionRepairer.parseRepairResponse(
                raw, booleanContext(), new HashSet<>(Arrays.asList("a0")));

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, result.getDiagnostics().size());
    }

    private static LlmPostProcessingResponseParser.ParseContext booleanContext() {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        types.put("v0", "boolean");
        return LlmPostProcessingResponseParser.context(
                new HashSet<>(Arrays.asList("s0")), new HashSet<>(Arrays.asList("v0")),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), types);
    }
}
