/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
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
