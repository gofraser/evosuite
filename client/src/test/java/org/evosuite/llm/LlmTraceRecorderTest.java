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
        assertTrue(content.contains("\"run_id\":\"run-abc\""));
        assertTrue(content.contains("\"feature\":\"TEST_REPAIR\""));
        assertTrue(content.contains("\"expanded_classes\":[\"java.util.ArrayList\"]"));
        assertTrue(content.contains("\"input_tokens\":12"));
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
