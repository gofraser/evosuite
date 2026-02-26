package org.evosuite.llm;

import org.evosuite.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class LlmTraceRecorderContextModeTest {

    @TempDir
    Path tempDir;

    @Test
    void traceRecordIncludesContextModeField() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "model-1", "", "", 0.0, 256, 3, 1, 1,
                true, tempDir, "run-ctx");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordCall(
                LlmFeature.SEEDING,
                Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")),
                "response", 10, 20, 100,
                "SUCCESS", 1, false, Collections.<String>emptyList(), "",
                Properties.LlmSutContextMode.BYTECODE_DISASSEMBLED, false);

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"sut_context_mode\":\"BYTECODE_DISASSEMBLED\""));
        assertTrue(content.contains("\"context_unavailable\":false"));
    }

    @Test
    void traceRecordIncludesContextUnavailableFlag() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "model-1", "", "", 0.0, 256, 3, 1, 1,
                true, tempDir, "run-strict");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordCall(
                LlmFeature.STAGNATION,
                Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")),
                "response", 10, 20, 100,
                "SUCCESS", 1, false, Collections.<String>emptyList(), "",
                Properties.LlmSutContextMode.SOURCE_CODE, true);

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"sut_context_mode\":\"SOURCE_CODE\""));
        assertTrue(content.contains("\"context_unavailable\":true"));
    }

    @Test
    void legacyRecordCallOmitsContextFields() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "model-1", "", "", 0.0, 256, 3, 1, 1,
                true, tempDir, "run-legacy");

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordCall(
                LlmFeature.TEST_REPAIR,
                Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")),
                "response", 10, 20, 100,
                "SUCCESS", 1, false, Collections.<String>emptyList(), "");

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        // Legacy call uses the overload without context mode - should still write trace
        assertTrue(content.contains("\"sut_context_mode\":\"\""));
        assertTrue(content.contains("\"context_unavailable\":false"));
    }
}
