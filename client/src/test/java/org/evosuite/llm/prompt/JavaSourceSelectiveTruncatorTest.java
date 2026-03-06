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
package org.evosuite.llm.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceSelectiveTruncatorTest {

    private final JavaSourceSelectiveTruncator truncator = new JavaSourceSelectiveTruncator();

    private static final String SAMPLE_CLASS =
            "public class Foo {\n"
            + "    private int x;\n"
            + "    public String name;\n"
            + "\n"
            + "    public Foo(int x) {\n"
            + "        this.x = x;\n"
            + "        System.out.println(\"constructor\");\n"
            + "    }\n"
            + "\n"
            + "    public int getX() {\n"
            + "        return x;\n"
            + "    }\n"
            + "\n"
            + "    public String longPublicMethod() {\n"
            + "        String result = \"\";\n"
            + "        for (int i = 0; i < 100; i++) {\n"
            + "            result += \"iteration\" + i + \"_padding_text_here\";\n"
            + "        }\n"
            + "        return result;\n"
            + "    }\n"
            + "\n"
            + "    private void helper() {\n"
            + "        System.out.println(\"private helper method body\");\n"
            + "        System.out.println(\"more private helper lines\");\n"
            + "    }\n"
            + "\n"
            + "    protected void protectedMethod() {\n"
            + "        System.out.println(\"protected method body\");\n"
            + "    }\n"
            + "}\n";

    @Test
    void returnsNullForUnparseableInput() {
        assertNull(truncator.truncate("this is not valid java {{{ with extra padding to exceed budget", 10));
    }

    @Test
    void returnsTextUnchangedWhenWithinBudget() {
        String result = truncator.truncate(SAMPLE_CLASS, 100_000);
        assertEquals(SAMPLE_CLASS, result);
    }

    @Test
    void stubsPrivateMethodsFirst() {
        // Set budget large enough to keep public methods but force stubbing of private/protected
        String result = truncator.truncate(SAMPLE_CLASS, SAMPLE_CLASS.length() - 50);
        assertNotNull(result, "Should succeed with selective truncation");
        assertTrue(result.contains("getX"), "Should keep small public method getX");
        assertTrue(result.contains("longPublicMethod"), "Should keep public method signature");
        // Private method body should be stubbed
        assertFalse(result.contains("private helper method body"), "Private method body should be stubbed");
        // Protected method body should be stubbed
        assertFalse(result.contains("protected method body"), "Protected method body should be stubbed");
    }

    @Test
    void preservesFieldsAndHeader() {
        String result = truncator.truncate(SAMPLE_CLASS, SAMPLE_CLASS.length() - 50);
        assertNotNull(result);
        assertTrue(result.contains("private int x"), "Should preserve fields");
        assertTrue(result.contains("public String name"), "Should preserve fields");
        assertTrue(result.contains("public class Foo"), "Should preserve class header");
    }

    @Test
    void preservesConstructors() {
        // Budget tight enough to stub non-public but keep constructors
        String result = truncator.truncate(SAMPLE_CLASS, SAMPLE_CLASS.length() - 50);
        assertNotNull(result);
        assertTrue(result.contains("public Foo(int x)"), "Should preserve constructor signature");
    }

    @Test
    void stubsLargestPublicMethodFirst() {
        // Use a tight budget that forces stubbing of the large public method
        // but keeps the small one. Need to allow enough space for the stubbed AST output.
        String result = truncator.truncate(SAMPLE_CLASS, 500);
        assertNotNull(result, "Should succeed with selective truncation");
        assertTrue(result.contains("getX"), "Should keep smaller public method");
        // The large public method body should be stubbed
        assertFalse(result.contains("iteration"), "Large public method body should be stubbed");
    }

    @Test
    void returnsNullWhenFullyStubbedStillOverBudget() {
        // Extremely tight budget that even fully stubbed code can't fit
        assertNull(truncator.truncate(SAMPLE_CLASS, 50));
    }

    @Test
    void handlesSingleMethodClass() {
        String simple = "public class Bar {\n"
                + "    public void doSomething() {\n"
                + "        System.out.println(\"hello world from Bar\");\n"
                + "    }\n"
                + "}\n";
        String result = truncator.truncate(simple, simple.length() - 10);
        assertNotNull(result);
        assertTrue(result.contains("doSomething"), "Should keep method signature");
    }
}
