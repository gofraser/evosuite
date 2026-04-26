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

/**
 * Produces the shared system prompt for all LLM interactions.
 */
public class SystemPromptProvider {

    /** Returns the system prompt string configured for the current output format. */
    public String getSystemPrompt() {
        String coverageDirective = " Your goal is to maximize code coverage of the class under test: "
                + "exercise every reachable method, branch, and edge case. "
                + "Include boundary values, null inputs, exception paths, and typical usage.";

        String structuralDirectives = " IMPORTANT: Each @Test method MUST be completely self-contained. "
                + "Do NOT use @Before or @After methods. Do NOT use class-level fields. "
                + "Do NOT use helper methods or inner classes. All setup and variable declarations "
                + "must happen inside the @Test method itself. "
                + "Do NOT use try/catch/finally blocks. "
                + "Do NOT use any control-flow statements: no if/else, switch, for, while, do-while, "
                + "enhanced for loops, conditional (?:) expressions, break, or continue. "
                + "Tests must be straight-line code only (plus assertThrows lambdas when needed). "
                + "Do NOT define anonymous classes. "
                + "Only access public and package-private members (methods, constructors, and fields). "
                + "Do NOT access private or protected members directly — they are not accessible from test code. "
                + "Prefer direct method/constructor/field access over reflection. "
                + "Do NOT use reflection (Class.forName, getDeclaredMethod, getDeclaredField, setAccessible, Method.invoke, Field.get/Field.set) "
                + "when an accessible direct call exists in the provided context. "
                + "If reflection is truly unavoidable, remember that Method.invoke wraps user exceptions in InvocationTargetException: "
                + "assert InvocationTargetException and then assert on getCause(). "
                + "Prefer real objects and constructors over mocking. Only use Mockito as a last resort "
                + "when a dependency cannot be instantiated directly. "
                + "Do NOT perform real file I/O, network I/O, or database operations in tests. "
                + "Tests run in a sandboxed environment where filesystem writes, network connections, "
                + "and process execution are blocked. If the class under test requires file or stream "
                + "input, pass in-memory objects (e.g., StringReader, ByteArrayInputStream) instead "
                + "of real files. Do NOT install, replace, reset, or restore a SecurityManager, and do NOT call "
                + "System.setSecurityManager(...); EvoSuite controls sandbox permissions itself and such mutations "
                + "are blocked. "
                + "Return raw Java code only. Do NOT wrap code in markdown fences (```). "
                + "Do NOT include any prose or explanation.";

        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            return "You are an expert Java test generation assistant integrated into EvoSuite. "
                    + "Generate only valid Java JUnit5 test code using org.junit.jupiter.api annotations. "
                    + "Return code only. Follow method signatures and generic types from the "
                    + "provided context strictly. "
                    + "Use assertThrows() for exception testing instead of @Test(expected=...)."
                    + structuralDirectives
                    + coverageDirective;
        }
        return "You are an expert Java test generation assistant integrated into EvoSuite. "
                + "Generate only valid Java JUnit4 test code using org.junit.Test annotations. "
                + "Return code only. Follow method signatures and generic types from the provided context strictly."
                + structuralDirectives
                + coverageDirective;
    }
}
