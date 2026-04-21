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

import org.evosuite.Properties;
import org.evosuite.llm.prompt.PromptResult;
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
        recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                .feature(LlmFeature.SEEDING)
                .messages(Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")))
                .responseText("response")
                .inputTokens(10)
                .outputTokens(20)
                .latencyMs(100)
                .parseStatus("SUCCESS")
                .repairAttempt(1)
                .expansionAttempted(false)
                .expandedClasses(Collections.<String>emptyList())
                .errorType("")
                .sutContextMode(Properties.LlmSutContextMode.BYTECODE_DISASSEMBLED)
                .contextUnavailable(false)
                .build());

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
        recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                .feature(LlmFeature.STAGNATION)
                .messages(Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")))
                .responseText("response")
                .inputTokens(10)
                .outputTokens(20)
                .latencyMs(100)
                .parseStatus("SUCCESS")
                .repairAttempt(1)
                .expansionAttempted(false)
                .expandedClasses(Collections.<String>emptyList())
                .errorType("")
                .sutContextMode(Properties.LlmSutContextMode.SOURCE_CODE)
                .contextUnavailable(true)
                .build());

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
        recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                .feature(LlmFeature.TEST_REPAIR)
                .messages(Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")))
                .responseText("response")
                .inputTokens(10)
                .outputTokens(20)
                .latencyMs(100)
                .parseStatus("SUCCESS")
                .repairAttempt(1)
                .expansionAttempted(false)
                .expandedClasses(Collections.<String>emptyList())
                .errorType("")
                .build());

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        // Legacy call uses the overload without context mode - should still write trace
        assertTrue(content.contains("\"sut_context_mode\":\"\""));
        assertTrue(content.contains("\"context_unavailable\":false"));
    }

    @Test
    void traceRecordIncludesDependencySummaryTelemetryFields() throws Exception {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "model-1", "", "", 0.0, 256, 3, 1, 1,
                true, tempDir, "run-deps");

        PromptResult.DependencySummaryMetadata metadata = new PromptResult.DependencySummaryMetadata(
                12000, 800, true, true, "dynamic_scaled",
                40, 12, 5, 4, 3,
                18, 24, 7, 1);

        LlmTraceRecorder recorder = new LlmTraceRecorder(configuration);
        recorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                .feature(LlmFeature.SEEDING)
                .messages(Arrays.asList(LlmMessage.system("sys"), LlmMessage.user("usr")))
                .responseText("response")
                .inputTokens(10)
                .outputTokens(20)
                .latencyMs(100)
                .parseStatus("SUCCESS")
                .repairAttempt(1)
                .expansionAttempted(false)
                .expandedClasses(Collections.<String>emptyList())
                .errorType("")
                .sutContextMode(Properties.LlmSutContextMode.SIGNATURE_ONLY)
                .contextUnavailable(false)
                .contextTruncated(false)
                .contextCommentsStripped(false)
                .contextSelectivelyTruncated(false)
                .clusterSummaryTruncated(false)
                .clusterSummaryChars(9999)
                .dependencySummaryMetadata(metadata)
                .build());

        String content = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"cluster_summary_budget_chars\":12000"));
        assertTrue(content.contains("\"cluster_summary_per_class_cap_chars\":800"));
        assertTrue(content.contains("\"cluster_summary_per_class_cap_auto\":true"));
        assertTrue(content.contains("\"cluster_summary_compact_signatures\":true"));
        assertTrue(content.contains("\"cluster_summary_budget_mode\":\"dynamic_scaled\""));
        assertTrue(content.contains("\"cluster_summary_candidate_classes\":40"));
        assertTrue(content.contains("\"cluster_summary_emitted_classes\":12"));
        assertTrue(content.contains("\"cluster_summary_emitted_instantiators\":18"));
        assertTrue(content.contains("\"cluster_summary_emitted_modifiers\":24"));
        assertTrue(content.contains("\"cluster_summary_dropped_per_class_cap\":7"));
        assertTrue(content.contains("\"cluster_summary_dropped_global_budget\":1"));
    }
}
