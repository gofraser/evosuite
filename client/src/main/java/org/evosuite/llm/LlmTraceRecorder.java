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

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes reproducibility traces for LLM interactions in JSONL format.
 */
public class LlmTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(LlmTraceRecorder.class);
    private static final Gson GSON = new Gson();

    private final LlmConfiguration configuration;
    private final Path traceFile;
    private boolean directoryCreated;

    /** Constructs a trace recorder for the given LLM configuration. */
    public LlmTraceRecorder(LlmConfiguration configuration) {
        this.configuration = configuration;
        this.traceFile = configuration.getTraceDir().resolve("llm-trace.jsonl");
    }

    /** Records a single LLM call with full context, token usage, and outcome metadata. */
    public void recordCall(LlmFeature feature,
                           List<LlmMessage> messages,
                           String responseText,
                           int inputTokens,
                           int outputTokens,
                           long latencyMs,
                           String parseStatus,
                           int repairAttempt,
                           boolean expansionAttempted,
                           List<String> expandedClasses,
                           String errorType) {
        recordCall(feature, messages, responseText, inputTokens, outputTokens, latencyMs,
                parseStatus, repairAttempt, expansionAttempted, expandedClasses, errorType,
                null, false);
    }

    /** Records a call with SUT context mode and availability metadata for trace recording. */
    public void recordCall(LlmFeature feature,
                           List<LlmMessage> messages,
                           String responseText,
                           int inputTokens,
                           int outputTokens,
                           long latencyMs,
                           String parseStatus,
                           int repairAttempt,
                           boolean expansionAttempted,
                           List<String> expandedClasses,
                           String errorType,
                           org.evosuite.Properties.LlmSutContextMode sutContextMode,
                           boolean contextUnavailable) {
        if (!configuration.isTraceEnabled()) {
            return;
        }

        List<LlmMessage> safeMessages = messages == null ? Collections.<LlmMessage>emptyList() : messages;
        List<String> expanded = expandedClasses == null ? Collections.<String>emptyList() : expandedClasses;
        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("run_id", configuration.getRunId());
        traceRecord.put("timestamp", Instant.now().toString());
        traceRecord.put("feature", feature.name());
        traceRecord.put("provider", configuration.getProvider().name());
        traceRecord.put("model", configuration.getModel());
        traceRecord.put("prompt_hash", deterministicPromptHash(safeMessages));
        traceRecord.put("messages", toSerializableMessages(safeMessages));
        traceRecord.put("response_text", responseText);
        traceRecord.put("parse_status", parseStatus);
        traceRecord.put("repair_attempt", repairAttempt);
        traceRecord.put("expansion_attempted", expansionAttempted);
        traceRecord.put("expanded_classes", expanded);
        traceRecord.put("input_tokens", inputTokens);
        traceRecord.put("output_tokens", outputTokens);
        traceRecord.put("latency_ms", latencyMs);
        traceRecord.put("error_type", errorType == null ? "" : errorType);
        traceRecord.put("sut_context_mode", sutContextMode == null ? "" : sutContextMode.name());
        traceRecord.put("context_unavailable", contextUnavailable);
        String json = GSON.toJson(traceRecord);

        synchronized (this) {
            try {
                if (!directoryCreated) {
                    Files.createDirectories(traceFile.getParent());
                    directoryCreated = true;
                }
                Files.write(traceFile,
                        Collections.singleton(json + System.lineSeparator()),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Failed writing LLM trace: {}", e.getMessage());
            }
        }
    }

    String deterministicPromptHash(List<LlmMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LlmMessage message : messages) {
                if (message == null) {
                    continue;
                }
                digest.update(message.getRole().name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
                digest.update(message.getContent().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1E);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public Path getTraceFile() {
        return traceFile;
    }

    private List<Map<String, String>> toSerializableMessages(List<LlmMessage> messages) {
        List<Map<String, String>> serialized = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null) {
                continue;
            }
            Map<String, String> jsonMessage = new LinkedHashMap<>();
            jsonMessage.put("role", message.getRole().name());
            jsonMessage.put("content", message.getContent());
            serialized.add(jsonMessage);
        }
        return serialized;
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte datum : data) {
            builder.append(String.format("%02x", datum));
        }
        return builder.toString();
    }
}
