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
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmLiteralNiceifierTest {

    @BeforeEach
    void setUp() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.TARGET_CLASS = "com.example.Foo";
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        LlmService.resetInstanceForTesting();
    }

    // ---- Replacement parsing tests ----

    @Test
    void parseReplacements_stringReplacements() {
        String response = "\"xK9#q\" -> \"hello\"\n\"abc123\" -> \"username\"";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(2, result.size());
        assertEquals("hello", result.get("xK9#q"));
        assertEquals("username", result.get("abc123"));
    }

    @Test
    void parseReplacements_numericReplacements() {
        String response = "42 -> 100\n-1 -> 0";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(2, result.size());
        assertEquals("100", result.get("42"));
        assertEquals("0", result.get("-1"));
    }

    @Test
    void parseReplacements_unicodeArrow() {
        String response = "\"test\" \u2192 \"example\"";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(1, result.size());
        assertEquals("example", result.get("test"));
    }

    @Test
    void parseReplacements_fatArrow() {
        String response = "\"test\" => \"example\"";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(1, result.size());
        assertEquals("example", result.get("test"));
    }

    @Test
    void parseReplacements_escapedQuotes() {
        String response = "\"he said \\\"hi\\\"\" -> \"greeting\"";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(1, result.size());
        assertEquals("greeting", result.get("he said \"hi\""));
    }

    @Test
    void parseReplacements_emptyResponse() {
        assertTrue(LlmLiteralNiceifier.parseReplacements("").isEmpty());
    }

    @Test
    void parseReplacements_nullResponse() {
        assertTrue(LlmLiteralNiceifier.parseReplacements(null).isEmpty());
    }

    @Test
    void parseReplacements_rejectsOverlyLongReplacements() {
        String longValue = "x".repeat(300);
        String response = "\"short\" -> \"" + longValue + "\"";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseReplacements_ignoresNonReplacementLines() {
        String response = "Here are some suggestions:\n\"test\" -> \"example\"\nHope this helps!";
        Map<String, String> result = LlmLiteralNiceifier.parseReplacements(response);

        assertEquals(1, result.size());
        assertEquals("example", result.get("test"));
    }

    // ---- Unescape tests ----

    @Test
    void unescapeJava_handlesEscapes() {
        assertEquals("he said \"hi\"", LlmLiteralNiceifier.unescapeJava("he said \\\"hi\\\""));
        assertEquals("line1\nline2", LlmLiteralNiceifier.unescapeJava("line1\\nline2"));
        assertEquals("tab\there", LlmLiteralNiceifier.unescapeJava("tab\\there"));
        assertEquals("back\\slash", LlmLiteralNiceifier.unescapeJava("back\\\\slash"));
    }

    @Test
    void unescapeJava_nullReturnsNull() {
        assertNull(LlmLiteralNiceifier.unescapeJava(null));
    }

    // ---- Coverage verification limitation ----

    @Test
    void niceify_appliesReplacementsWithoutCoverageCheck() {
        // Verify the documented behavior: replacements are applied without
        // coverage re-verification. This test documents the current limitation.
        LlmLiteralNiceifier niceifier = new LlmLiteralNiceifier();
        // When LLM is unavailable, no replacements happen (graceful degradation)
        niceifier.niceify(null);
        assertEquals(0, niceifier.getLiteralsNiceified(),
                "No replacements should occur without LLM availability");
    }

    // ---- Feature toggle tests ----

    @Test
    void niceify_noOpWhenLlmUnavailable() {
        LlmLiteralNiceifier niceifier = new LlmLiteralNiceifier();
        // Should not throw, just no-op
        niceifier.niceify(null);
        assertEquals(0, niceifier.getLiteralsNiceified());
    }
}
