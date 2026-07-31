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
 * Decodes the JSON envelope and accepts only known response schema versions.
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
        if (schemaVersion < LlmPostProcessingProtocol.MIN_RESPONSE_SCHEMA_VERSION
                || schemaVersion > LlmPostProcessingResponse.SUPPORTED_SCHEMA_VERSION) {
            return DecodeResult.failure("Unsupported schemaVersion: " + schemaVersion);
        }
        return DecodeResult.success(root, schemaVersion);
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
        private final int schemaVersion;
        private final String failureReason;

        private DecodeResult(JsonNode root, int schemaVersion, String failureReason) {
            this.root = root;
            this.schemaVersion = schemaVersion;
            this.failureReason = failureReason;
        }

        static DecodeResult success(JsonNode root, int schemaVersion) {
            return new DecodeResult(root, schemaVersion, null);
        }

        static DecodeResult failure(String reason) {
            return new DecodeResult(null, -1, reason);
        }

        boolean isSuccess() {
            return failureReason == null;
        }

        JsonNode getRoot() {
            return root;
        }

        int getSchemaVersion() {
            return schemaVersion;
        }

        String getFailureReason() {
            return failureReason;
        }
    }
}
