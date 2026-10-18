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
package org.evosuite.llm.prompt;

import org.evosuite.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that {@link PromptResult#toBuilder()} never hands a caller a stale
 * {@code prefixHash} after the caller replaces the messages on the builder.
 */
class PromptResultTest {

    @Test
    void toBuilderWithDifferentMessagesRecomputesPrefixHash() {
        List<LlmMessage> originalMessages = Arrays.asList(
                LlmMessage.system("original system message"),
                LlmMessage.user("original user message"));
        PromptResult original = new PromptResult.Builder()
                .messages(originalMessages)
                .build();

        List<LlmMessage> newMessages = Arrays.asList(
                LlmMessage.system("a completely different system message"),
                LlmMessage.user("a completely different user message"));
        PromptResult updated = original.toBuilder()
                .messages(newMessages)
                .build();

        assertEquals(PrefixHash.of(newMessages), updated.getPrefixHash());
        assertNotEquals(original.getPrefixHash(), updated.getPrefixHash(),
                "Replacing the system message must change the prefix hash");
    }

    @Test
    void toBuilderWithUnchangedMessagesKeepsSamePrefixHash() {
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.system("stable system message"),
                LlmMessage.user("stable user message"));
        PromptResult original = new PromptResult.Builder()
                .messages(messages)
                .build();

        PromptResult rebuilt = original.toBuilder().build();

        assertEquals(original.getPrefixHash(), rebuilt.getPrefixHash());
        assertEquals(PrefixHash.of(messages), rebuilt.getPrefixHash());
    }

    @Test
    void explicitPrefixHashSurvivesUntilMessagesAreReplaced() {
        List<LlmMessage> messages = Arrays.asList(LlmMessage.system("s"), LlmMessage.user("u"));
        PromptResult original = new PromptResult.Builder()
                .messages(messages)
                .prefixHash("explicit-hash")
                .build();
        assertEquals("explicit-hash", original.getPrefixHash());

        // toBuilder() must not propagate a stale explicit hash once messages change.
        List<LlmMessage> newMessages = Arrays.asList(LlmMessage.system("s2"), LlmMessage.user("u2"));
        PromptResult updated = original.toBuilder().messages(newMessages).build();

        assertEquals(PrefixHash.of(newMessages), updated.getPrefixHash());
        assertNotEquals("explicit-hash", updated.getPrefixHash());
    }
}
