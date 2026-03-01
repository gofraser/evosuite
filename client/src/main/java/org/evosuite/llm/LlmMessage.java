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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LlmMessage)) return false;
        LlmMessage that = (LlmMessage) o;
        return role == that.role && content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return 31 * role.hashCode() + content.hashCode();
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
