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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatModelParamTest {

    @Test
    void usesMaxCompletionTokensForGpt5AndReasoningFamilies() {
        assertTrue(OpenAiCompatibleChatModel.usesMaxCompletionTokens("gpt-5.4-nano"));
        assertTrue(OpenAiCompatibleChatModel.usesMaxCompletionTokens("gpt-5"));
        assertTrue(OpenAiCompatibleChatModel.usesMaxCompletionTokens("o3-mini"));
        assertTrue(OpenAiCompatibleChatModel.usesMaxCompletionTokens("o4-mini"));
        assertTrue(OpenAiCompatibleChatModel.usesMaxCompletionTokens("o1-preview"));
    }

    @Test
    void keepsMaxTokensForOlderChatFamilies() {
        assertFalse(OpenAiCompatibleChatModel.usesMaxCompletionTokens("gpt-4o-mini"));
        assertFalse(OpenAiCompatibleChatModel.usesMaxCompletionTokens("gpt-4.1-mini"));
        assertFalse(OpenAiCompatibleChatModel.usesMaxCompletionTokens("qwen35-397b"));
        assertFalse(OpenAiCompatibleChatModel.usesMaxCompletionTokens(""));
        assertFalse(OpenAiCompatibleChatModel.usesMaxCompletionTokens(null));
    }
}

