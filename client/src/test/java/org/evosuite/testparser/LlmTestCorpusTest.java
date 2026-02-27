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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Corpus of realistic LLM-generated test patterns. These represent common
 * output from ChatGPT, Claude, Gemini, etc. when asked to write JUnit tests.
 * <p>
 * The goal is to verify the parser handles real-world LLM output gracefully,
 * including common imperfections and style variations.
 */
class LlmTestCorpusTest {

    private TestParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
    }

    private ParseResult parse(String body, List<String> imports) {
        return parser.parseTestMethodBody(body, imports);
    }

    private ParseResult parseJava(String body) {
        return parse(body, List.of(
                "import java.util.*;",
                "import java.util.stream.*;",
                "import java.io.*;",
                "import static org.junit.Assert.*;",
                "import static org.junit.jupiter.api.Assertions.*;"
        ));
    }

    // ========================================================================
    // Pattern: typical ArrayList test with inline literals and assertions
    // ========================================================================

    @Nested
    @DisplayName("ArrayList usage patterns")
    class ArrayListPatterns {

        @Test
        void typicalAddAndSizeTest() {
            ParseResult r = parseJava(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(\"hello\");\n" +
                    "list.add(\"world\");\n" +
                    "int size = list.size();\n" +
                    "assertEquals(2, size);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 4, "Should have at least 4 statements");
            // The assertEquals should attach as assertion, not create more statements
        }

        @Test
        void addAndGetWithCast() {
            ParseResult r = parseJava(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(\"item\");\n" +
                    "Object first = list.get(0);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 3);
        }

        @Test
        void clearAndIsEmpty() {
            ParseResult r = parseJava(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(1);\n" +
                    "list.clear();\n" +
                    "boolean empty = list.isEmpty();\n" +
                    "assertTrue(empty);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }
    }

    // ========================================================================
    // Pattern: HashMap usage
    // ========================================================================

    @Nested
    @DisplayName("HashMap usage patterns")
    class HashMapPatterns {

        @Test
        void putAndGet() {
            ParseResult r = parseJava(
                    "HashMap map = new HashMap();\n" +
                    "map.put(\"key\", \"value\");\n" +
                    "Object result = map.get(\"key\");\n" +
                    "assertEquals(\"value\", result);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 3);
        }

        @Test
        void containsKey() {
            ParseResult r = parseJava(
                    "HashMap map = new HashMap();\n" +
                    "map.put(\"a\", 1);\n" +
                    "boolean has = map.containsKey(\"a\");\n" +
                    "assertTrue(has);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }

        @Test
        void sizeAfterPuts() {
            ParseResult r = parseJava(
                    "HashMap map = new HashMap();\n" +
                    "map.put(\"x\", 1);\n" +
                    "map.put(\"y\", 2);\n" +
                    "map.put(\"z\", 3);\n" +
                    "int size = map.size();\n" +
                    "assertEquals(3, size);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }
    }

    // ========================================================================
    // Pattern: multiple assertions on the same variable
    // ========================================================================

    @Nested
    @DisplayName("Multiple assertions")
    class MultipleAssertions {

        @Test
        void assertNotNullThenEquals() {
            ParseResult r = parseJava(
                    "ArrayList list = new ArrayList();\n" +
                    "int size = list.size();\n" +
                    "assertNotNull(list);\n" +
                    "assertEquals(0, size);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }
    }

    // ========================================================================
    // Pattern: LLM using diamond operator and generics (common in LLM output)
    // ========================================================================

    @Nested
    @DisplayName("Generics and diamond operator")
    class GenericsPatterns {

        @Test
        void diamondOperator() {
            ParseResult r = parseJava(
                    "HashMap<String, Integer> map = new HashMap<>();\n" +
                    "map.put(\"one\", 1);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 1);
        }

        @Test
        void rawTypeUsage() {
            // LLMs sometimes use raw types
            ParseResult r = parseJava(
                    "ArrayList list = new ArrayList();\n" +
                    "HashMap map = new HashMap();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertEquals(2, tc.size());
        }
    }

    // ========================================================================
    // Pattern: string operations
    // ========================================================================

    @Nested
    @DisplayName("String operations")
    class StringPatterns {

        @Test
        void stringMethodChain() {
            ParseResult r = parseJava(
                    "String s = \"  Hello World  \";\n" +
                    "String trimmed = s.trim();\n" +
                    "int len = trimmed.length();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 3);
        }

        @Test
        void stringContainsAssert() {
            ParseResult r = parseJava(
                    "String s = \"hello world\";\n" +
                    "boolean has = s.contains(\"world\");\n" +
                    "assertTrue(has);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }
    }

    // ========================================================================
    // Pattern: negative values, edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases and negative values")
    class EdgeCases {

        @Test
        void negativeIntegers() {
            ParseResult r = parseJava("int x = -100;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertEquals(-100, ((IntPrimitiveStatement) tc.getStatement(0)).getValue().intValue());
        }

        @Test
        void zeroValues() {
            ParseResult r = parseJava(
                    "int a = 0;\n" +
                    "double b = 0.0;\n" +
                    "long c = 0L;");
            TestCase tc = r.getTestCase();
            assertEquals(3, tc.size());
        }

        @Test
        void nullAssignment() {
            ParseResult r = parseJava("Object x = null;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(NullStatement.class, tc.getStatement(0));
        }

        @Test
        void emptyStringLiteral() {
            ParseResult r = parseJava("String s = \"\";");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(0));
            assertEquals("", ((StringPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void maxIntValue() {
            ParseResult r = parseJava("int max = Integer.MAX_VALUE;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(FieldStatement.class, tc.getStatement(0));
        }
    }

    // ========================================================================
    // Pattern: unresolvable types produce errors but don't crash
    // ========================================================================

    @Nested
    @DisplayName("Graceful error handling")
    class GracefulErrors {

        @Test
        void unknownTypeDoesNotCrash() {
            ParseResult r = parseJava("MyUnknownClass x = new MyUnknownClass();");
            assertTrue(r.hasErrors());
            // Should still produce a test case (possibly empty or with interpreted fallback)
            assertNotNull(r.getTestCase());
        }

        @Test
        void partiallyParsableTest() {
            ParseResult r = parseJava(
                    "int x = 42;\n" +
                    "UnknownType y = new UnknownType();\n" +
                    "int z = 10;");
            // First and third should parse; second should produce error
            assertTrue(r.hasErrors());
            TestCase tc = r.getTestCase();
            // At minimum x=42 should be there, and z=10 may or may not be depending on error recovery
            assertTrue(tc.size() >= 1);
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
        }

        @Test
        void tryCatchBlockPreservedAsInterpreted() {
            ParseResult r = parseJava(
                    "try {\n" +
                    "    int x = 1;\n" +
                    "} catch (Exception e) {\n" +
                    "    // ignore\n" +
                    "}");
            TestCase tc = r.getTestCase();
            assertTrue(tc.size() >= 1);
            assertInstanceOf(UninterpretedStatement.class, tc.getStatement(0));
        }

        @Test
        void ifStatementPreservedAsInterpreted() {
            ParseResult r = parseJava(
                    "int x = 5;\n" +
                    "if (x > 3) {\n" +
                    "    int y = 10;\n" +
                    "}");
            TestCase tc = r.getTestCase();
            // x=5 should parse, if-block preserved as UninterpretedStatement
            assertTrue(tc.size() >= 2);
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
            assertInstanceOf(UninterpretedStatement.class, tc.getStatement(1));
        }
    }

    // ========================================================================
    // Pattern: Full test class parsing (as an LLM would generate)
    // ========================================================================

    @Nested
    @DisplayName("Full test class parsing")
    class FullTestClass {

        @Test
        void parseFullJUnit4TestClass() {
            String source =
                    "import java.util.ArrayList;\n" +
                    "import org.junit.Test;\n" +
                    "import static org.junit.Assert.*;\n" +
                    "\n" +
                    "public class ArrayListTest {\n" +
                    "    @Test\n" +
                    "    public void testAdd() {\n" +
                    "        ArrayList list = new ArrayList();\n" +
                    "        list.add(\"hello\");\n" +
                    "        assertEquals(1, list.size());\n" +
                    "    }\n" +
                    "\n" +
                    "    @Test\n" +
                    "    public void testClear() {\n" +
                    "        ArrayList list = new ArrayList();\n" +
                    "        list.add(\"x\");\n" +
                    "        list.clear();\n" +
                    "        boolean empty = list.isEmpty();\n" +
                    "        assertTrue(empty);\n" +
                    "    }\n" +
                    "}\n";

            List<ParseResult> results = parser.parseTestClass(source);
            assertEquals(2, results.size());
            assertEquals("testAdd", results.get(0).getOriginalMethodName());
            assertEquals("testClear", results.get(1).getOriginalMethodName());

            // Both should parse without errors
            for (ParseResult r : results) {
                assertFalse(r.hasErrors(), r.getOriginalMethodName() + " has errors: " + r.getDiagnostics());
                assertTrue(r.getTestCase().size() >= 2,
                        r.getOriginalMethodName() + " should have at least 2 statements");
            }
        }

        @Test
        void parseFullJUnit5TestClass() {
            String source =
                    "import java.util.HashMap;\n" +
                    "import org.junit.jupiter.api.Test;\n" +
                    "import static org.junit.jupiter.api.Assertions.*;\n" +
                    "\n" +
                    "public class HashMapTest {\n" +
                    "    @Test\n" +
                    "    void testPutAndGet() {\n" +
                    "        HashMap map = new HashMap();\n" +
                    "        map.put(\"key\", \"value\");\n" +
                    "        Object val = map.get(\"key\");\n" +
                    "        assertNotNull(val);\n" +
                    "    }\n" +
                    "}\n";

            List<ParseResult> results = parser.parseTestClass(source);
            assertEquals(1, results.size());
            assertFalse(results.get(0).hasErrors(),
                    "Errors: " + results.get(0).getDiagnostics());
        }
    }
}
