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
        assertTrue(content.contains("\"schema_version\":2"));
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
        assertFalse(content.contains(System.lineSeparator() + System.lineSeparator()));
        assertEquals(2, Files.readAllLines(recorder.getTraceFile(), StandardCharsets.UTF_8).size());
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
