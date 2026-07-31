/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
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
