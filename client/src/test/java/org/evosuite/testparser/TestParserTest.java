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
package org.evosuite.testparser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestParserTest {

    private TestParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
    }

    @Test
    void parseTestClassFindsAllTestMethods() {
        String source = "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void test1() {\n"
                + "        ArrayList list = new ArrayList();\n"
                + "    }\n"
                + "    @org.junit.Test\n"
                + "    public void test2() {\n"
                + "        int x = 42;\n"
                + "    }\n"
                + "    public void helperNotATest() {}\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(2, results.size());
        assertEquals("test1", results.get(0).getOriginalMethodName());
        assertEquals("test2", results.get(1).getOriginalMethodName());
    }

    @Test
    void parseTestMethodByName() {
        String source = "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void testFoo() {\n"
                + "        ArrayList list = new ArrayList();\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testFoo");
        assertNotNull(result);
        assertEquals("testFoo", result.getOriginalMethodName());
        assertFalse(result.hasErrors());
    }

    @Test
    void parseTestMethodNotFoundReturnsError() {
        String source = "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void testFoo() {}\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "nonExistent");
        assertTrue(result.hasErrors());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.getMessage().contains("nonExistent")));
    }

    @Test
    void parseTestMethodBody() {
        String body = "int x = 42;\n";
        List<String> imports = List.of("import java.util.ArrayList;");

        ParseResult result = parser.parseTestMethodBody(body, imports);
        assertNotNull(result);
        assertFalse(result.hasErrors());
    }

    @Test
    void parseTestMethodBodyWithPackage() {
        // LinkedList is in java.util — without an explicit import, only
        // the package wildcard (from the package declaration) can resolve it.
        String body = "LinkedList<String> list = new LinkedList<>();\n";
        List<String> imports = List.of();

        ParseResult result = parser.parseTestMethodBody(body, imports, "java.util");
        assertNotNull(result);
        assertFalse(result.hasErrors(),
                "Expected no errors but got: " + result.getDiagnostics());
        assertEquals(1, result.getTestCase().size());
    }

    @Test
    void parseJUnit5TestAnnotation() {
        String source = "public class MyTest {\n"
                + "    @org.junit.jupiter.api.Test\n"
                + "    public void testBar() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(1, results.size());
        assertEquals("testBar", results.get(0).getOriginalMethodName());
    }

    @Test
    void parseShortTestAnnotation() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test\n"
                + "    public void testBaz() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(1, results.size());
    }

    @Test
    void parseJUnit4ExpectedExceptionAnnotation() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test(expected = IllegalArgumentException.class)\n"
                + "    public void testThrows() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testThrows");
        assertNotNull(result);
        assertFalse(result.hasErrors());
        assertEquals("IllegalArgumentException", result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit4FullyQualifiedExpectedException() {
        String source = "public class MyTest {\n"
                + "    @org.junit.Test(expected = java.io.IOException.class)\n"
                + "    public void testIO() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testIO");
        assertEquals("java.io.IOException", result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit5NoExpectedException() {
        String source = "public class MyTest {\n"
                + "    @org.junit.jupiter.api.Test\n"
                + "    public void testNormal() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testNormal");
        assertNull(result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit4TestWithoutExpected() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test\n"
                + "    public void testPlain() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testPlain");
        assertNull(result.getExpectedExceptionClass());
    }
}
