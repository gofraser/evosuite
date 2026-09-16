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
package org.evosuite.llm.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnthropicChatModelTest {

    private static String endpointFor(String baseUrl) {
        return new AnthropicChatModel("claude", "key", baseUrl, 0.0, 10, 5).getEndpoint();
    }

    @Test
    void emptyBaseUrlUsesTheDefaultEndpoint() {
        assertEquals("https://api.anthropic.com/v1/messages", endpointFor(null));
        assertEquals("https://api.anthropic.com/v1/messages", endpointFor("  "));
    }

    @Test
    void hostOnlyBaseUrlGetsTheVersionedPath() {
        assertEquals("http://127.0.0.1:8080/v1/messages", endpointFor("http://127.0.0.1:8080"));
        assertEquals("http://127.0.0.1:8080/v1/messages", endpointFor("http://127.0.0.1:8080/"));
    }

    /** The previous client took a {@code .../v1/} base and appended {@code messages}; keep that working. */
    @Test
    void baseUrlEndingInV1IsNotDoubled() {
        assertEquals("https://api.anthropic.com/v1/messages", endpointFor("https://api.anthropic.com/v1"));
        assertEquals("https://proxy.example/anthropic/v1/messages", endpointFor("https://proxy.example/anthropic/v1/"));
    }

    @Test
    void fullMessagesUrlIsUsedAsIs() {
        assertEquals("https://proxy.example/v1/messages", endpointFor("https://proxy.example/v1/messages"));
    }
}
