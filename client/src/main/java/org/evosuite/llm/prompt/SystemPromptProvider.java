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
                + "MANDATORY INLINING: You MUST NOT define any methods other than those annotated with @Test. Do NOT create private helper methods for setup. If multiple tests require the same complex initialization, you MUST copy and paste that setup code directly into every @Test method that needs it. "
                + "Do NOT use try/catch/finally blocks. "
                + "Do NOT use any control-flow statements: no if/else, switch, for, while, do-while, "
                + "enhanced for loops, conditional (?:) expressions, break, or continue. "
                + "Tests must be straight-line code only. If assertions are explicitly allowed and an exception assertion is truly needed, "
                + "an assertThrows lambda is acceptable. "
                + "Do NOT define anonymous classes or anonymous implementations of interfaces/abstract classes. If an interface or abstract class is required, you MUST use Mockito.mock(Class.class) instead of writing an anonymous class body. "
                + "Do NOT use Mockito.spy(...) or spy-based stubbing patterns such as doReturn(...).when(spy)...; these are not reliably representable in EvoSuite parsed statements. Prefer real instances or Mockito.mock(...). "
                + "ACCESS & STRATEGY: You have access to both public and package-private members (methods, constructors, and fields) because tests are generated in the same package as the SUT. If a public factory or constructor is too complex to instantiate (e.g., requires many dependencies), PREFER using a package-private constructor or method to create the SUT instance directly. This is often the simplest way to bypass heavyweight setup logic. "
                + "Do NOT access private or protected members directly — they are not accessible from test code. "
                + "Prefer direct method/constructor/field access over reflection. "
                + "Do NOT use reflection (Class.forName, getDeclaredMethod, getDeclaredField, setAccessible, Method.invoke, Field.get/Field.set) "
                + "when an accessible direct call exists in the provided context. "
                + "If reflection is truly unavoidable, remember that Method.invoke wraps user exceptions in InvocationTargetException: "
                + "assert InvocationTargetException and then assert on getCause(). "
                + "Prefer real objects and constructors over mocking. Only use Mockito as a last resort "
                + "when a dependency cannot be instantiated directly. "
                + "Do NOT perform real file I/O, network I/O, or database operations in tests. "
                + "Tests run in a sandboxed and **HEADLESS** environment. "
                + "If the class under test requires file or stream input, pass in-memory objects (e.g., StringReader, ByteArrayInputStream) instead of real files. "
                + "Do NOT install, replace, reset, or restore a SecurityManager, and do NOT call System.setSecurityManager(...); EvoSuite controls sandbox permissions itself and such mutations are blocked. "
                + "Do NOT instantiate classes that require a local GraphicsEnvironment, display, or keyboard/mouse focus (e.g., JFrame, JDialog, or other heavy-weight Swing/AWT components) directly if they trigger AWTError. Focus on testing the underlying logic. If a GUI component must be used, mock the graphical dependencies using Mockito. "
                + "SIGNATURE FIDELITY: Prioritize constructors and methods listed in the context. If a concrete dependency is required but its constructor is not listed, you may mock it using mock(Dependency.class). Do not invent non-existent signatures. "
                + "REAL INTERACTION: Every test must invoke at least one method belonging to the CLASS UNDER TEST (SUT). Tests that only assert on constants or literals (e.g., assertEquals(\"a\", \"a\")) provide zero value and will be rejected. "
                + "PRECISION: Generic types (e.g., List<String>, Vector<Character>) must match the context EXACTLY. A type mismatch will cause a compilation error. "
                + "EXPLICIT TYPING: Always use explicit variable types (e.g., Person p = ... instead of var p = ...). "
                + "IMPORTANT: ALWAYS USE FULLY QUALIFIED NAMES (FQNs) for all inner classes, enums, and static members (e.g., Use `Outer.Inner.CONSTANT` instead of `Inner.CONSTANT` or `CONSTANT`) to avoid ambiguity. "
                + "IMPORT SYNTAX: Java has NO `import ... as ...` alias syntax (that is Kotlin/Scala/Python). To resolve a simple-name collision (for example two types both named `Query`), import only one of the colliding types and reference the other by its fully qualified name at every call site (e.g., `net.sourceforge.beanbin.query.Query q = new net.sourceforge.beanbin.query.Query();`). Never write `import x.y.Z as Alias;`. "
                + "INSTANTIATION: Before calling a method, ensure you have a valid instance. If a class has no public constructor, search 'Available dependency types' for a static factory method (e.g., getInstance(), create()) or a builder that returns that type. "
                + "MOCKING: Only use `org.mockito.Mockito.mock(Class.class)` for interfaces or abstract classes that have no listed concrete implementations. For concrete classes, always prefer the provided constructors. Do not mock classes from the java.* or javax.* packages. "
                + "STRUCTURE: Provide only the necessary imports and the @Test methods. Do NOT include a class declaration (e.g., public class Test { ... }). Do NOT use markdown code fences (```). Start your response with the first import or test method. "
                + "Return raw Java code only. Do NOT include any prose or explanation.";

        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            return "You are an expert Java test generation assistant integrated into EvoSuite. "
                    + "Generate only valid Java JUnit5 test code using org.junit.jupiter.api annotations. "
                    + "Return code only. Follow method signatures and generic types from the "
                    + "provided context strictly. "
                    + "When assertions are allowed and exception behavior must be checked, use assertThrows() "
                    + "instead of @Test(expected=...). If a later instruction explicitly says coverage-guidance "
                    + "tests should not include assertions, obey that instruction and omit assertions entirely."
                    + structuralDirectives
                    + coverageDirective;
        }
        return "You are an expert Java test generation assistant integrated into EvoSuite. "
                + "Generate only valid Java JUnit4 test code using org.junit.Test annotations. "
                + "Return code only. Follow method signatures and generic types from the provided context strictly. "
                + "If a later instruction explicitly says coverage-guidance tests should not include assertions, "
                + "obey that instruction and omit assertions entirely."
                + structuralDirectives
                + coverageDirective;
    }
}
