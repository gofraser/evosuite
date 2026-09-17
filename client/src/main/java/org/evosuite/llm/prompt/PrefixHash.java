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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Computes a stable fingerprint of a prompt's cacheable prefix (the system
 * message content), so callers can tell whether two prompts share the same
 * prefix without comparing the full text.
 */
public final class PrefixHash {

    private PrefixHash() {
    }

    /**
     * Hash of the concatenated SYSTEM-role message contents in {@code messages},
     * joined by {@code "\n"}. Returns {@link #ofText(String)} of the empty
     * string when there are no SYSTEM messages.
     */
    public static String of(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return ofText("");
        }
        StringBuilder systemContent = new StringBuilder();
        boolean first = true;
        for (LlmMessage message : messages) {
            if (message == null || message.getRole() != LlmMessage.Role.SYSTEM) {
                continue;
            }
            if (!first) {
                systemContent.append('\n');
            }
            systemContent.append(message.getContent());
            first = false;
        }
        return ofText(systemContent.toString());
    }

    /** Lowercase hex SHA-256 of {@code text} ({@code null} treated as the empty string). */
    public static String ofText(String text) {
        String content = text == null ? "" : text;
        return hex(sha256().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** A fresh SHA-256 digest; the algorithm is mandatory in every JDK, so its absence is a fatal state. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Lowercase hex rendering of {@code data}. */
    public static String hex(byte[] data) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte b : data) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
