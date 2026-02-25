package org.evosuite.llm;

import java.util.Objects;

/**
 * Provider-neutral message representation used across prompt and service layers.
 */
public final class LlmMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    private final Role role;
    private final String content;

    private LlmMessage(Role role, String content) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = Objects.requireNonNull(content, "content");
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
