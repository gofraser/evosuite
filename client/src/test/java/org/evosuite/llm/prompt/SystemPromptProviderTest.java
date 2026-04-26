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

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptProviderTest {

    private final Properties.OutputFormat originalOutputFormat = Properties.TEST_FORMAT;

    @AfterEach
    void restoreOutputFormat() {
        Properties.TEST_FORMAT = originalOutputFormat;
    }

    @Test
    void junit5PromptIncludesNoControlFlowRule() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        String prompt = new SystemPromptProvider().getSystemPrompt();

        assertTrue(prompt.contains("Do NOT use any control-flow statements"),
                "System prompt must forbid control-flow statements for parser compatibility");
        assertTrue(prompt.contains("Do NOT use try/catch/finally blocks"),
                "System prompt must forbid try/catch/finally for parser compatibility");
        assertTrue(prompt.contains("no if/else, switch, for, while, do-while"),
                "System prompt must explicitly list forbidden branches and loops");
        assertTrue(prompt.contains("Tests must be straight-line code only"),
                "System prompt must require straight-line tests");
        assertTrue(prompt.contains("Do NOT define anonymous classes"),
                "System prompt must forbid anonymous classes for parser compatibility");
        assertTrue(prompt.contains("Prefer direct method/constructor/field access over reflection"),
                "System prompt should discourage unnecessary reflection");
        assertTrue(prompt.contains("Method.invoke wraps user exceptions in InvocationTargetException"),
                "System prompt should explain reflective exception wrapping");
        assertTrue(prompt.contains("Do NOT install, replace, reset, or restore a SecurityManager"),
                "System prompt must forbid SecurityManager mutation in sandboxed tests");
        assertTrue(prompt.contains("System.setSecurityManager(...)"),
                "System prompt must explicitly forbid System.setSecurityManager");
    }

    @Test
    void junit4PromptIncludesNoControlFlowRule() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;

        String prompt = new SystemPromptProvider().getSystemPrompt();

        assertTrue(prompt.contains("Do NOT use any control-flow statements"),
                "System prompt must forbid control-flow statements for parser compatibility");
        assertTrue(prompt.contains("Do NOT use try/catch/finally blocks"),
                "System prompt must forbid try/catch/finally for parser compatibility");
        assertTrue(prompt.contains("Tests must be straight-line code only"),
                "System prompt must require straight-line tests");
        assertTrue(prompt.contains("Do NOT define anonymous classes"),
                "System prompt must forbid anonymous classes for parser compatibility");
        assertTrue(prompt.contains("Prefer direct method/constructor/field access over reflection"),
                "System prompt should discourage unnecessary reflection");
        assertTrue(prompt.contains("Method.invoke wraps user exceptions in InvocationTargetException"),
                "System prompt should explain reflective exception wrapping");
        assertTrue(prompt.contains("Do NOT install, replace, reset, or restore a SecurityManager"),
                "System prompt must forbid SecurityManager mutation in sandboxed tests");
        assertTrue(prompt.contains("System.setSecurityManager(...)"),
                "System prompt must explicitly forbid System.setSecurityManager");
    }
}
