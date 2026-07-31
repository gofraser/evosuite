/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import com.fasterxml.jackson.databind.JsonNode;

/** Pure schema rules for assertion kinds and their field aliases. */
final class PostProcessingAssertionKindRules {

    private PostProcessingAssertionKindRules() {
        // Utility class.
    }

    static boolean requiresExpected(LlmPostProcessingResponse.AssertionKind kind) {
        switch (kind) {
            case EQUALS:
            case NOT_EQUALS:
            case SAME:
            case NOT_SAME:
            case CONTAINS:
            case NOT_CONTAINS:
            case SIZE_EQUALS:
            case MAP_CONTAINS_KEY:
            case GREATER:
            case LESS:
            case GREATER_EQUALS:
            case LESS_EQUALS:
                return true;
            case TRUE:
            case FALSE:
            case NULL:
            case NOT_NULL:
            case IS_EMPTY:
            default:
                return false;
        }
    }

    static boolean allowsDelta(LlmPostProcessingResponse.AssertionKind kind) {
        return kind == LlmPostProcessingResponse.AssertionKind.EQUALS
                || kind == LlmPostProcessingResponse.AssertionKind.NOT_EQUALS;
    }

    static boolean isRelational(LlmPostProcessingResponse.AssertionKind kind) {
        return kind == LlmPostProcessingResponse.AssertionKind.GREATER
                || kind == LlmPostProcessingResponse.AssertionKind.LESS
                || kind == LlmPostProcessingResponse.AssertionKind.GREATER_EQUALS
                || kind == LlmPostProcessingResponse.AssertionKind.LESS_EQUALS;
    }

    static JsonNode expectedNode(LlmPostProcessingResponse.AssertionKind kind, JsonNode entry) {
        switch (kind) {
            case CONTAINS:
            case NOT_CONTAINS:
                return entry.has("expected") ? entry.get("expected") : entry.get("element");
            case SIZE_EQUALS:
                return entry.has("expected") ? entry.get("expected") : entry.get("size");
            case MAP_CONTAINS_KEY:
                return entry.has("expected") ? entry.get("expected") : entry.get("key");
            default:
                return entry.get("expected");
        }
    }

    static JsonNode actualNode(LlmPostProcessingResponse.AssertionKind kind, JsonNode entry) {
        switch (kind) {
            case CONTAINS:
            case NOT_CONTAINS:
                return entry.has("actual") ? entry.get("actual") : entry.get("container");
            case SIZE_EQUALS:
            case IS_EMPTY:
                return entry.has("actual") ? entry.get("actual") : entry.get("target");
            case MAP_CONTAINS_KEY:
                return entry.has("actual") ? entry.get("actual") : entry.get("map");
            default:
                return entry.get("actual");
        }
    }

    static String expectedPathSuffix(LlmPostProcessingResponse.AssertionKind kind) {
        switch (kind) {
            case CONTAINS:
            case NOT_CONTAINS:
                return ".expected/.element";
            case SIZE_EQUALS:
                return ".expected/.size";
            case MAP_CONTAINS_KEY:
                return ".expected/.key";
            default:
                return ".expected";
        }
    }

    static String actualPathSuffix(LlmPostProcessingResponse.AssertionKind kind) {
        switch (kind) {
            case CONTAINS:
            case NOT_CONTAINS:
                return ".actual/.container";
            case SIZE_EQUALS:
            case IS_EMPTY:
                return ".actual/.target";
            case MAP_CONTAINS_KEY:
                return ".actual/.map";
            default:
                return ".actual";
        }
    }
}
