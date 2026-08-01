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
 */
package org.evosuite.llm.postprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Decodes the JSON envelope for the canonical production response schema.
 * Field and assertion policy validation remains in the response parser.
 */
final class PostProcessingResponseDecoder {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private PostProcessingResponseDecoder() {
        // Utility class.
    }

    static DecodeResult decode(String response) {
        if (response == null || response.trim().isEmpty()) {
            return DecodeResult.failure("Empty LLM post-processing response");
        }

        JsonNode root;
        try {
            root = JSON_MAPPER.readTree(normalizeJsonResponse(response));
        } catch (IOException e) {
            return DecodeResult.failure("Response is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return DecodeResult.failure("Response root must be a JSON object");
        }

        JsonNode schemaVersionNode = root.get("schemaVersion");
        if (schemaVersionNode == null || !schemaVersionNode.isIntegralNumber()) {
            return DecodeResult.failure("Missing or non-integral schemaVersion");
        }
        int schemaVersion = schemaVersionNode.asInt();
        if (schemaVersion != LlmPostProcessingProtocol.RESPONSE_SCHEMA_VERSION) {
            return DecodeResult.failure("Unsupported schemaVersion: " + schemaVersion
                    + "; expected " + LlmPostProcessingProtocol.RESPONSE_SCHEMA_VERSION);
        }
        return DecodeResult.success(root);
    }

    static String normalizeJsonResponse(String response) {
        String trimmed = response.trim();
        if (!trimmed.startsWith("```")) {
            return response;
        }

        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            return response;
        }

        String fenceHeader = trimmed.substring(3, firstLineEnd).trim();
        if (!fenceHeader.isEmpty() && !"json".equalsIgnoreCase(fenceHeader)) {
            return response;
        }

        return trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
    }

    static final class DecodeResult {
        private final JsonNode root;
        private final String failureReason;

        private DecodeResult(JsonNode root, String failureReason) {
            this.root = root;
            this.failureReason = failureReason;
        }

        static DecodeResult success(JsonNode root) {
            return new DecodeResult(root, null);
        }

        static DecodeResult failure(String reason) {
            return new DecodeResult(null, reason);
        }

        boolean isSuccess() {
            return failureReason == null;
        }

        JsonNode getRoot() {
            return root;
        }

        String getFailureReason() {
            return failureReason;
        }
    }
}
