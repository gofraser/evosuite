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
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmTraceRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    void writesTraceRecordWithRequiredFields() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.OPENAI,
                "model-1",
                "",
                "",
                0.0,
                256,
                3,
                1,
                1,
                true,
                tempDir,
                "run-abc");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                .feature(LlmFeature.TEST_REPAIR)
                .messages(Arrays.asList(LlmMessage.system("system"), LlmMessage.user("user")))
                .responseText("response")
                .inputTokens(12)
                .outputTokens(34)
                .latencyMs(56)
                .parseStatus("SUCCESS")
                .repairAttempt(1)
                .expansionAttempted(true)
                .expandedClasses(Arrays.asList("java.util.ArrayList"))
                .errorType("")
                .build());

        Path traceFile = recorder.getTraceFile();
        assertTrue(Files.exists(traceFile));

        String content = new String(Files.readAllBytes(traceFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"schema_version\":3"));
        assertTrue(content.contains("\"run_id\":\"run-abc\""));
        assertTrue(content.contains("\"feature\":\"TEST_REPAIR\""));
        assertTrue(content.contains("\"expanded_classes\":[\"java.util.ArrayList\"]"));
        assertTrue(content.contains("\"input_tokens\":12"));
    }

    @Test
    void writesPostProcessingSchemaAttributionAndMinimizationMetadata() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.OPENAI,
                "model-1",
                "",
                "",
                0.0,
                256,
                3,
                1,
                1,
                true,
                tempDir,
                "run-post");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        LlmTraceRecorder.setPostProcessingTraceContext("COMPLETE", "SEARCH_FINISHED", 4);
        try {
            recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                    .feature(LlmFeature.POST_PROCESSING)
                    .messages(Arrays.asList(LlmMessage.system("system"), LlmMessage.user("initial")))
                    .responseText("initial")
                    .parseStatus("SUCCESS")
                    .repairAttempt(1)
                    .build());
            recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                    .feature(LlmFeature.POST_PROCESSING)
                    .messages(Arrays.asList(LlmMessage.system("system"), LlmMessage.user("repair")))
                    .responseText("repair")
                    .parseStatus("SUCCESS")
                    .repairAttempt(2)
                    .build());
        } finally {
            LlmTraceRecorder.clearPostProcessingTraceContext();
        }

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"postprocessing_call_kind\":\"initial\""));
        assertTrue(content.contains("\"postprocessing_call_kind\":\"repair\""));
        assertTrue(content.contains("\"postprocessing_test_index\":4"));
        assertTrue(content.contains("\"postprocessing_minimization_status\":\"COMPLETE\""));
        assertTrue(content.contains("\"postprocessing_minimization_stop_cause\":\"SEARCH_FINISHED\""));
        assertTrue(content.contains("\"postprocessing_prompt_version\":\"postprocessing-production-v2\""));
        assertTrue(content.contains("\"postprocessing_response_schema_version\":2"));
        assertTrue(content.contains("\"postprocessing_parser_version\":\"postprocessing-parser-v4\""));
        assertTrue(content.contains("\"effective_temperature\":0.0"));
        assertTrue(content.contains("\"system_prompt_hash\":"));
        assertTrue(content.contains("\"user_prompt_hash\":"));
        assertFalse(content.contains(System.lineSeparator() + System.lineSeparator()));
        assertEquals(2, Files.readAllLines(recorder.getTraceFile(), StandardCharsets.UTF_8).size());
        assertTrue(new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8)
                .contains("\"event_type\":\"llm_call\""));
    }

    @Test
    void recordsConfiguredPostProcessingPolicyAndCapabilities() throws Exception {
        boolean assertions = org.evosuite.Properties.LLM_POSTPROCESSING_ASSERTIONS;
        boolean testNames = org.evosuite.Properties.LLM_POSTPROCESSING_TEST_NAMES;
        boolean variableNames = org.evosuite.Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        boolean comments = org.evosuite.Properties.LLM_POSTPROCESSING_COMMENTS;
        boolean sectionBreaks = org.evosuite.Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
        org.evosuite.Properties.LlmPostProcessingRepairPolicy repairPolicy =
                org.evosuite.Properties.LLM_POSTPROCESSING_REPAIR_POLICY;
        try {
            org.evosuite.Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
            org.evosuite.Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
            org.evosuite.Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
            org.evosuite.Properties.LLM_POSTPROCESSING_COMMENTS = true;
            org.evosuite.Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
            org.evosuite.Properties.LLM_POSTPROCESSING_REPAIR_POLICY =
                    org.evosuite.Properties.LlmPostProcessingRepairPolicy.BATCHED;

            LlmConfiguration configuration = new LlmConfiguration(
                    org.evosuite.Properties.LlmProvider.OPENAI, "model-1", "", "", 0.0,
                    256, 3, 1, 1, true, tempDir, "run-policy");
            LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
            recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                    .feature(LlmFeature.POST_PROCESSING)
                    .messages(Arrays.asList(LlmMessage.user("request")))
                    .responseText("response")
                    .parseStatus("SUCCESS")
                    .build());

            String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
            assertTrue(content.contains("\"postprocessing_repair_policy\":\"BATCHED\""));
            assertTrue(content.contains("\"postprocessing_capabilities\":[\"test_names\",\"comments\"]"));
        } finally {
            org.evosuite.Properties.LLM_POSTPROCESSING_ASSERTIONS = assertions;
            org.evosuite.Properties.LLM_POSTPROCESSING_TEST_NAMES = testNames;
            org.evosuite.Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = variableNames;
            org.evosuite.Properties.LLM_POSTPROCESSING_COMMENTS = comments;
            org.evosuite.Properties.LLM_POSTPROCESSING_SECTION_BREAKS = sectionBreaks;
            org.evosuite.Properties.LLM_POSTPROCESSING_REPAIR_POLICY = repairPolicy;
        }
    }

    @Test
    void writesPostProcessingAssertionDiagnosticEvent() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.OPENAI,
                "model-1",
                "",
                "",
                0.0,
                256,
                3,
                1,
                1,
                true,
                tempDir,
                "run-diagnostic");

        Map<String, Object> assertionJson = new LinkedHashMap<>();
        assertionJson.put("assertionId", "a0");
        assertionJson.put("kind", "EQUALS");
        assertionJson.put("actual", "v0.getValue()");
        assertionJson.put("expected", "42");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordPostProcessingAssertionDiagnostic(
                new LlmTraceRecorder.PostProcessingAssertionDiagnosticRecord(
                        7,
                        "COMPLETED",
                        "NONE",
                        "initial",
                        "validation",
                        "COMPILE",
                        "assertions[a0].actual",
                        "Expression calls a method not listed as callable",
                        "a0",
                        "EQUALS",
                        "v0.getValue()",
                        "42",
                        "",
                        "REGRESSION",
                        "s3",
                        "check value",
                        assertionJson));

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"event_type\":\"postprocessing_assertion_rejection\""));
        assertTrue(content.contains("\"postprocessing_test_index\":7"));
        assertTrue(content.contains("\"diagnostic_code\":\"COMPILE\""));
        assertTrue(content.contains("\"diagnostic_source\":\"validation\""));
        assertTrue(content.contains("\"diagnostic_message\":\"Expression calls a method not listed as callable\""));
        assertTrue(content.contains("\"assertion_actual\":\"v0.getValue()\""));
        assertTrue(content.contains("\"assertion_json\":{\"assertionId\":\"a0\""));
    }

    @Test
    void writesPostProcessingAssertionLifecycleEvent() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.OPENAI,
                "model-1", "", "", 0.0, 256, 3, 1, 1,
                true, tempDir, "run-lifecycle");
        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);

        recorder.recordPostProcessingAssertionLifecycle(
                new LlmTraceRecorder.PostProcessingAssertionLifecycleRecord(
                        3, "COMPLETED", "NONE", "shipped", "final_validation",
                        "a2", "EQUALS", "v0", "42", "", "REGRESSION",
                        "s1", "preserve the result", "SELECTED_CANDIDATE", "c4"));

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"event_type\":\"postprocessing_assertion_lifecycle\""));
        assertTrue(content.contains("\"lifecycle_state\":\"shipped\""));
        assertTrue(content.contains("\"assertion_id\":\"a2\""));
        assertTrue(content.contains("\"postprocessing_call_kind\":\"final_validation\""));
        assertTrue(content.contains("\"assertion_source\":\"SELECTED_CANDIDATE\""));
        assertTrue(content.contains("\"candidate_id\":\"c4\""));
    }

    @Test
    void promptHashIsDeterministicFromMessageContent() {
        LlmConfiguration configuration = new LlmConfiguration(
                org.evosuite.Properties.LlmProvider.OPENAI,
                "model-1",
                "",
                "",
                0.0,
                256,
                3,
                1,
                1,
                true,
                tempDir,
                "run-abc");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        String hashA = recorder.deterministicPromptHash(Arrays.asList(
                LlmMessage.system("system"),
                LlmMessage.user("user")));
        String hashB = recorder.deterministicPromptHash(Arrays.asList(
                LlmMessage.system("system"),
                LlmMessage.user("user")));
        String hashC = recorder.deterministicPromptHash(Arrays.asList(
                LlmMessage.system("system"),
                LlmMessage.user("different")));

        assertEquals(hashA, hashB);
        assertNotEquals(hashA, hashC);
    }
}
