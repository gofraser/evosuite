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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostProcessingResponseDecoderTest {

    @Test
    void decodesFencedJsonWithoutApplyingFieldPolicy() {
        PostProcessingResponseDecoder.DecodeResult result =
                PostProcessingResponseDecoder.decode(
                        "```json\n{\"schemaVersion\":2,\"futureField\":true}\n```");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getSchemaVersion());
        assertTrue(result.getRoot().get("futureField").asBoolean());
    }

    @Test
    void rejectsMalformedAndUnknownEnvelopesBeforeParsingFields() {
        PostProcessingResponseDecoder.DecodeResult malformed =
                PostProcessingResponseDecoder.decode("not json");
        PostProcessingResponseDecoder.DecodeResult unknown =
                PostProcessingResponseDecoder.decode("{\"schemaVersion\":99}");

        assertFalse(malformed.isSuccess());
        assertTrue(malformed.getFailureReason().startsWith("Response is not valid JSON:"));
        assertFalse(unknown.isSuccess());
        assertEquals("Unsupported schemaVersion: 99", unknown.getFailureReason());
    }

    @Test
    void rejectsVersionsOutsideTheSupportedReadRange() {
        for (int version : new int[]{0, 4, 17}) {
            PostProcessingResponseDecoder.DecodeResult result =
                    PostProcessingResponseDecoder.decode(
                            "{\"schemaVersion\":" + version + "}");

            assertFalse(result.isSuccess());
            assertEquals("Unsupported schemaVersion: " + version,
                    result.getFailureReason());
        }
    }
}
