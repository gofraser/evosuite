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
package org.evosuite.llm.postprocess;

/** Small package-private rules shared by response parsing and edit validation. */
final class PostProcessingTextPolicy {

    private PostProcessingTextPolicy() {
        // Utility class.
    }

    static String sanitizeComment(String text) {
        if (text == null || text.contains("*/") || text.contains("/*")) {
            return null;
        }
        String sanitized = text.replace('\r', ' ').replace('\n', ' ').trim();
        while (true) {
            String next = stripCommentPrefix(sanitized).trim();
            if (next.equals(sanitized)) {
                break;
            }
            sanitized = next;
        }
        for (int i = 0; i < sanitized.length(); i++) {
            if (Character.isISOControl(sanitized.charAt(i))) {
                return null;
            }
        }
        return sanitized.startsWith("@") ? null : sanitized;
    }

    static String stripCommentPrefix(String text) {
        if (text.startsWith("//") || text.startsWith("/*")) {
            return text.substring(2);
        }
        return text.startsWith("*") ? text.substring(1) : text;
    }
}
