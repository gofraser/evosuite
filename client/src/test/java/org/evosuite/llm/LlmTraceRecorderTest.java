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
        recorder.recordCall(
                LlmFeature.TEST_REPAIR,
                Arrays.asList(LlmMessage.system("system"), LlmMessage.user("user")),
                "response",
                12,
                34,
                56,
                "SUCCESS",
                1,
                true,
                Arrays.asList("java.util.ArrayList"),
                "");

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
