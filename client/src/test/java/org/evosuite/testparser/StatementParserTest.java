/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.assertion.SameAssertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StatementParser — the core AST → EvoSuite statement conversion logic.
 */
class StatementParserTest {

    private TestParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
    }

    private ParseResult parse(String body) {
        return parse(body, List.of(
                "import java.util.*;",
                "import java.util.concurrent.TimeUnit;"
        ));
    }

    private ParseResult parse(String body, List<String> imports) {
        return parser.parseTestMethodBody(body, imports);
    }

    // ========================================================================
    // Primitive Statements
    // ========================================================================

    @Nested
    class PrimitiveStatements {

        @Test
        void parseInt() {
            ParseResult r = parse("int x = 42;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
            assertEquals(42, ((IntPrimitiveStatement) tc.getStatement(0)).getValue().intValue());
        }

        @Test
        void parseNegativeInt() {
            ParseResult r = parse("int x = -5;");
            TestCase tc = r.getTestCase();
            // Negative literals may be parsed as UnaryExpr(-, 5) — check if handled
            assertTrue(tc.size() >= 1);
        }

        @Test
        void parseLong() {
            ParseResult r = parse("long x = 100L;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(LongPrimitiveStatement.class, tc.getStatement(0));
            assertEquals(100L, ((LongPrimitiveStatement) tc.getStatement(0)).getValue().longValue());
        }

        @Test
        void parseDouble() {
            ParseResult r = parse("double x = 3.14;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(DoublePrimitiveStatement.class, tc.getStatement(0));
            assertEquals(3.14, ((DoublePrimitiveStatement) tc.getStatement(0)).getValue(), 0.001);
        }

        @Test
        void parseFloat() {
            ParseResult r = parse("float x = 2.5F;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(FloatPrimitiveStatement.class, tc.getStatement(0));
            assertEquals(2.5f, ((FloatPrimitiveStatement) tc.getStatement(0)).getValue(), 0.001);
        }

        @Test
        void parseBoolean() {
            ParseResult r = parse("boolean x = true;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(BooleanPrimitiveStatement.class, tc.getStatement(0));
            assertTrue(((BooleanPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void parseBooleanFalse() {
            ParseResult r = parse("boolean x = false;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertFalse(((BooleanPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void parseChar() {
            ParseResult r = parse("char x = 'a';");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(CharPrimitiveStatement.class, tc.getStatement(0));
            assertEquals('a', ((CharPrimitiveStatement) tc.getStatement(0)).getValue().charValue());
        }

        @Test
        void parseString() {
            ParseResult r = parse("String x = \"hello\";");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(0));
            assertEquals("hello", ((StringPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void parseStringWithEscapes() {
            ParseResult r = parse("String x = \"hello\\nworld\";");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertEquals("hello\nworld", ((StringPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void parseByteCast() {
            ParseResult r = parse("byte x = (byte)1;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(BytePrimitiveStatement.class, tc.getStatement(0));
            assertEquals((byte) 1, ((BytePrimitiveStatement) tc.getStatement(0)).getValue().byteValue());
        }

        @Test
        void parseShortCast() {
            ParseResult r = parse("short x = (short)100;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ShortPrimitiveStatement.class, tc.getStatement(0));
            assertEquals((short) 100, ((ShortPrimitiveStatement) tc.getStatement(0)).getValue().shortValue());
        }

        @Test
        void parseNullString() {
            // String null → StringPrimitiveStatement(null), NOT NullStatement
            ParseResult r = parse("String x = null;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(0));
            assertNull(((StringPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void parseNullObject() {
            ParseResult r = parse("Object x = null;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(NullStatement.class, tc.getStatement(0));
        }

        @Test
        void parseNullList() {
            ParseResult r = parse("List x = null;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(NullStatement.class, tc.getStatement(0));
        }

        @Test
        void parseZeroInt() {
            ParseResult r = parse("int x = 0;");
            TestCase tc = r.getTestCase();
            assertEquals(0, ((IntPrimitiveStatement) tc.getStatement(0)).getValue().intValue());
        }
    }

    // ========================================================================
    // Constructor Statements
    // ========================================================================

    @Nested
    class ConstructorStatements {

        @Test
        void parseNoArgConstructor() {
            ParseResult r = parse("ArrayList list = new ArrayList();");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            assertEquals(ArrayList.class, tc.getStatement(0).getReturnClass());
        }

        @Test
        void parseConstructorWithArgs() {
            ParseResult r = parse(
                    "int cap = 10;\n" +
                    "ArrayList list = new ArrayList(cap);");
            TestCase tc = r.getTestCase();
            assertEquals(2, tc.size());
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(1));
        }

        @Test
        void parseConstructorWithInlineLiteral() {
            ParseResult r = parse("ArrayList list = new ArrayList(16);");
            TestCase tc = r.getTestCase();
            // Should create IntPrimitiveStatement(16) + ConstructorStatement
            assertEquals(2, tc.size());
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(1));
            assertEquals(16, ((IntPrimitiveStatement) tc.getStatement(0)).getValue().intValue());
        }

        @Test
        void parseGenericConstructorWithDiamond() {
            ParseResult r = parse("HashMap<String, Integer> map = new HashMap<>();");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
        }

        @Test
        void parseStackConstructor() {
            ParseResult r = parse("Stack stack = new Stack();");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            assertEquals(Stack.class, tc.getStatement(0).getReturnClass());
        }
    }

    // ========================================================================
    // Method Statements
    // ========================================================================

    @Nested
    class MethodStatements {

        @Test
        void parseInstanceMethodVoid() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "list.clear();");
            TestCase tc = r.getTestCase();
            assertEquals(2, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            assertInstanceOf(MethodStatement.class, tc.getStatement(1));
        }

        @Test
        void parseInstanceMethodWithReturn() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "int size = list.size();");
            TestCase tc = r.getTestCase();
            assertEquals(2, tc.size());
            assertInstanceOf(MethodStatement.class, tc.getStatement(1));
        }

        @Test
        void parseInstanceMethodWithArgs() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "String item = \"hello\";\n" +
                    "boolean added = list.add(item);");
            TestCase tc = r.getTestCase();
            assertEquals(3, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(1));
            assertInstanceOf(MethodStatement.class, tc.getStatement(2));
        }

        @Test
        void parseStaticMethodCall() {
            ParseResult r = parse("List list = Collections.emptyList();");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(MethodStatement.class, tc.getStatement(0));
            MethodStatement ms = (MethodStatement) tc.getStatement(0);
            assertNull(ms.getCallee()); // static — no callee
        }

        @Test
        void parseMethodWithInlineLiteralArgs() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(\"world\");");
            TestCase tc = r.getTestCase();
            // Should create: ConstructorStatement, StringPrimitiveStatement("world"), MethodStatement
            assertEquals(3, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(1));
            assertInstanceOf(MethodStatement.class, tc.getStatement(2));
        }

        @Test
        void parseMethodWithNullArg() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(null);");
            TestCase tc = r.getTestCase();
            // ConstructorStatement + NullStatement + MethodStatement
            assertEquals(3, tc.size());
            assertInstanceOf(NullStatement.class, tc.getStatement(1));
            assertInstanceOf(MethodStatement.class, tc.getStatement(2));
        }

        @Test
        void parseVoidMethodReturn() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "list.clear();");
            TestCase tc = r.getTestCase();
            assertInstanceOf(MethodStatement.class, tc.getStatement(1));
            MethodStatement ms = (MethodStatement) tc.getStatement(1);
            // void return type
            assertEquals(void.class, ms.getReturnType());
        }
    }

    // ========================================================================
    // Field Statements
    // ========================================================================

    @Nested
    class FieldStatements {

        @Test
        void parseStaticField() {
            ParseResult r = parse("int max = Integer.MAX_VALUE;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(FieldStatement.class, tc.getStatement(0));
            FieldStatement fs = (FieldStatement) tc.getStatement(0);
            assertNull(fs.getSource()); // static — no source
        }

        @Test
        void parseEnumConstant() {
            ParseResult r = parse("TimeUnit unit = TimeUnit.SECONDS;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(EnumPrimitiveStatement.class, tc.getStatement(0));
        }

        @Test
        void parseFieldWrite() {
            // java.awt.Point has public int x, y fields
            ParseResult r = parse("java.awt.Point p = new java.awt.Point();\n" +
                    "p.x = 42;",
                    List.of("import java.awt.Point;"));
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Point() + IntPrimitiveStatement(42) + AssignmentStatement
            assertTrue(tc.size() >= 3, "Expected at least 3 statements, got " + tc.size());
            assertInstanceOf(AssignmentStatement.class, tc.getStatement(tc.size() - 1));
        }
    }

    // ========================================================================
    // Multi-statement tests (integration)
    // ========================================================================

    @Nested
    class MultiStatement {

        @Test
        void parseStackPushPop() {
            ParseResult r = parse(
                    "Stack stack = new Stack();\n" +
                    "int value = 42;\n" +
                    "stack.push(value);\n" +
                    "Object result = stack.pop();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertEquals(4, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(1));
            assertInstanceOf(MethodStatement.class, tc.getStatement(2));
            assertInstanceOf(MethodStatement.class, tc.getStatement(3));
        }

        @Test
        void parseMultipleVariables() {
            ParseResult r = parse(
                    "int a = 1;\n" +
                    "int b = 2;\n" +
                    "String s = \"test\";");
            TestCase tc = r.getTestCase();
            assertEquals(3, tc.size());
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(0));
            assertInstanceOf(IntPrimitiveStatement.class, tc.getStatement(1));
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(2));
        }

        @Test
        void parseListAddAndGet() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "list.add(\"item1\");\n" +
                    "list.add(\"item2\");\n" +
                    "Object first = list.get(0);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // ArrayList() + "item1" + add("item1") + "item2" + add("item2") + 0 + get(0)
            assertEquals(7, tc.size());
        }
    }

    // ========================================================================
    // Class literals
    // ========================================================================

    @Nested
    class ClassLiterals {

        @Test
        void parseClassLiteral() {
            ParseResult r = parse("Class x = String.class;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ClassPrimitiveStatement.class, tc.getStatement(0));
        }

        @Test
        void parsePrimitiveClassLiteral() {
            ParseResult r = parse("Class x = int.class;");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ClassPrimitiveStatement.class, tc.getStatement(0));
        }
    }

    // ========================================================================
    // Array statements
    // ========================================================================

    @Nested
    class ArrayStatements {

        @Test
        void parseArrayCreation() {
            ParseResult r = parse("int[] arr = new int[5];");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
        }

        @Test
        void parseStringArrayCreation() {
            ParseResult r = parse("String[] arr = new String[3];");
            TestCase tc = r.getTestCase();
            assertEquals(1, tc.size());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
        }

        @Test
        void parseArrayAssignment() {
            ParseResult r = parse(
                    "int[] arr = new int[3];\n" +
                    "arr[0] = 42;");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // ArrayStatement + IntPrimitiveStatement(42) + AssignmentStatement
            assertEquals(3, tc.size());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
            assertInstanceOf(AssignmentStatement.class, tc.getStatement(2));
        }

        @Test
        void parseArrayRead() {
            ParseResult r = parse(
                    "int[] arr = new int[3];\n" +
                    "arr[0] = 42;\n" +
                    "int x = arr[0];");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // ArrayStatement + int(42) + AssignmentStatement + (arr[0] reference)
            assertTrue(tc.size() >= 3, "Got: " + tc.toCode());
        }

        @Test
        void parseArrayInitializer() {
            ParseResult r = parse("int[] arr = new int[]{1, 2, 3};");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // ArrayStatement(size=3) + 3 values + 3 assignments = 7
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
            ArrayStatement arrStmt = (ArrayStatement) tc.getStatement(0);
            assertEquals(3, arrStmt.size());
        }

        @Test
        void parseStringArrayInitializer() {
            ParseResult r = parse("String[] arr = new String[]{\"a\", \"b\"};");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
            ArrayStatement arrStmt = (ArrayStatement) tc.getStatement(0);
            assertEquals(2, arrStmt.size());
        }

        @Test
        void parseArrayAsMethodArg() {
            ParseResult r = parse(
                    "int[] arr = new int[3];\n" +
                    "arr[0] = 10;\n" +
                    "java.util.Arrays.sort(arr);",
                    List.of());
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // Should have ArrayStatement, value, assignment, and sort() call
            boolean foundSort = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    assertEquals("sort", ((MethodStatement) tc.getStatement(i)).getMethodName());
                    foundSort = true;
                }
            }
            assertTrue(foundSort, "Should have Arrays.sort() call:\n" + tc.toCode());
        }

        @Test
        void parseMultiDimArray() {
            ParseResult r = parse("int[][] matrix = new int[3][4];");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertEquals(1, tc.size());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(0));
        }

        @Test
        void parseArrayWithVariableDimension() {
            ParseResult r = parse(
                    "int n = 5;\n" +
                    "int[] arr = new int[n];");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertEquals(2, tc.size());
            assertInstanceOf(ArrayStatement.class, tc.getStatement(1));
            ArrayStatement arrStmt = (ArrayStatement) tc.getStatement(1);
            assertEquals(5, arrStmt.size(), "Array should use variable value 5");
        }

        @Test
        void parseArrayWithUnresolvableDimensionEmitsWarning() {
            // Dimension expression is a method call, not a variable — should emit warning
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "int[] arr = new int[list.size()];",
                    List.of("import java.util.*;"));
            TestCase tc = r.getTestCase();
            // Should still produce an ArrayStatement (defaulting to 0)
            boolean foundArray = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof ArrayStatement) {
                    foundArray = true;
                }
            }
            assertTrue(foundArray, "Should have ArrayStatement even with unresolvable dim:\n" + tc.toCode());
            // Should have a warning diagnostic about the non-literal dimension
            assertTrue(r.getDiagnostics().stream()
                    .anyMatch(d -> d.getMessage().contains("Non-literal array dimension")
                            || d.getMessage().contains("array dimension")),
                    "Should warn about non-literal dimension: " + r.getDiagnostics());
        }
    }

    // ========================================================================
    // Binary expressions (InterpretedStatement)
    // ========================================================================

    @Nested
    class BinaryExpressions {

        @Test
        void parseAddition() {
            ParseResult r = parse(
                    "int a = 1;\n" +
                    "int b = 2;\n" +
                    "int c = a + b;");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 3);
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(tc.size() - 1));
        }

        @Test
        void parseMultiplication() {
            ParseResult r = parse(
                    "int a = 3;\n" +
                    "int b = 4;\n" +
                    "int c = a * b;");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 3);
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(tc.size() - 1));
        }
    }

    // ========================================================================
    // Chained method calls
    // ========================================================================

    @Nested
    class ChainedCalls {

        @Test
        void parseChainedMethodCalls() {
            ParseResult r = parse(
                    "ArrayList list = new ArrayList();\n" +
                    "String s = list.toString().trim();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // ArrayList() + toString() + trim()
            assertTrue(tc.size() >= 3);
        }
    }

    // ========================================================================
    // Assertion parsing
    // ========================================================================

    @Nested
    class Assertions {

        private ParseResult parseWithAssertImports(String body) {
            return parse(body, List.of(
                    "import java.util.*;",
                    "import static org.junit.Assert.*;",
                    "import static org.junit.jupiter.api.Assertions.*;"
            ));
        }

        @Test
        void parseAssertEqualsIntLiteral() {
            ParseResult r = parseWithAssertImports(
                    "int x = 42;\n" +
                    "assertEquals(42, x);");
            TestCase tc = r.getTestCase();
            // x should have a PrimitiveAssertion attached
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty(), "Should have assertion");
            Assertion a = stmt.getAssertions().iterator().next();
            assertInstanceOf(PrimitiveAssertion.class, a);
            assertEquals(42, a.getValue());
        }

        @Test
        void parseAssertTrue() {
            ParseResult r = parseWithAssertImports(
                    "boolean flag = true;\n" +
                    "assertTrue(flag);");
            TestCase tc = r.getTestCase();
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty(), "Should have assertion");
            Assertion a = stmt.getAssertions().iterator().next();
            assertInstanceOf(PrimitiveAssertion.class, a);
            assertEquals(true, a.getValue());
        }

        @Test
        void parseAssertFalse() {
            ParseResult r = parseWithAssertImports(
                    "boolean flag = false;\n" +
                    "assertFalse(flag);");
            TestCase tc = r.getTestCase();
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty());
            Assertion a = stmt.getAssertions().iterator().next();
            assertEquals(false, a.getValue());
        }

        @Test
        void parseAssertNull() {
            ParseResult r = parseWithAssertImports(
                    "Object obj = null;\n" +
                    "assertNull(obj);");
            TestCase tc = r.getTestCase();
            // Find the statement that defines obj
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty());
            Assertion a = stmt.getAssertions().iterator().next();
            assertInstanceOf(NullAssertion.class, a);
            assertEquals(true, a.getValue()); // isNull = true
        }

        @Test
        void parseAssertNotNull() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList list = new ArrayList();\n" +
                    "assertNotNull(list);");
            TestCase tc = r.getTestCase();
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty());
            Assertion a = stmt.getAssertions().iterator().next();
            assertInstanceOf(NullAssertion.class, a);
            assertEquals(false, a.getValue()); // isNull = false
        }

        @Test
        void parseAssertEqualsWithMessage() {
            // JUnit4 style: assertEquals("message", expected, actual)
            ParseResult r = parseWithAssertImports(
                    "int x = 10;\n" +
                    "assertEquals(\"should be 10\", 10, x);");
            TestCase tc = r.getTestCase();
            Statement stmt = tc.getStatement(0);
            assertFalse(stmt.getAssertions().isEmpty());
            assertEquals(10, stmt.getAssertions().iterator().next().getValue());
        }

        @Test
        void parseAssertEqualsWithInlineMethodCall() {
            // assertEquals(expected, obj.method()) — method call as actual arg
            ParseResult r = parseWithAssertImports(
                    "ArrayList list = new ArrayList();\n" +
                    "assertEquals(0, list.size());");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Should have: constructor + size() method call
            assertTrue(tc.size() >= 2,
                    "Expected constructor + size(), got " + tc.size() + ": " + tc.toCode());
            // The size() call should have a PrimitiveAssertion(0) attached
            Statement sizeStmt = tc.getStatement(tc.size() - 1);
            assertInstanceOf(MethodStatement.class, sizeStmt);
            assertEquals("size", ((MethodStatement) sizeStmt).getMethodName());
            assertFalse(sizeStmt.getAssertions().isEmpty(), "size() should have assertion");
            assertEquals(0, sizeStmt.getAssertions().iterator().next().getValue());
        }

        @Test
        void parseAssertTrueWithInlineMethodCall() {
            // assertTrue(obj.method(arg)) — method call as condition
            ParseResult r = parseWithAssertImports(
                    "ArrayList list = new ArrayList();\n" +
                    "assertTrue(list.isEmpty());");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 2,
                    "Expected constructor + isEmpty(), got " + tc.size() + ": " + tc.toCode());
            Statement isEmptyStmt = tc.getStatement(tc.size() - 1);
            assertInstanceOf(MethodStatement.class, isEmptyStmt);
            assertEquals("isEmpty", ((MethodStatement) isEmptyStmt).getMethodName());
            assertFalse(isEmptyStmt.getAssertions().isEmpty(), "isEmpty() should have assertion");
            assertEquals(true, isEmptyStmt.getAssertions().iterator().next().getValue());
        }

        @Test
        void parseAssertNullWithInlineMethodCall() {
            // assertNull(map.get(key)) — method call as object arg
            ParseResult r = parseWithAssertImports(
                    "HashMap map = new HashMap();\n" +
                    "String key = \"x\";\n" +
                    "assertNull(map.get(key));");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Find the get() method call
            boolean foundGet = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                if (s instanceof MethodStatement && ((MethodStatement) s).getMethodName().equals("get")) {
                    foundGet = true;
                    assertFalse(s.getAssertions().isEmpty(), "get() should have NullAssertion");
                    assertInstanceOf(NullAssertion.class, s.getAssertions().iterator().next());
                }
            }
            assertTrue(foundGet, "Should have a get() method call:\n" + tc.toCode());
        }

        @Test
        void parseAssertNotEqualsWithTwoVariables() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList a = new ArrayList();\n" +
                    "ArrayList b = new ArrayList();\n" +
                    "b.add(\"x\");\n" +
                    "assertNotEquals(a, b);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // The last real statement (b.add) or the constructor for b should have
            // an EqualsAssertion with value=false attached to b's defining statement
            boolean foundEqualsAssertion = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                for (Assertion a : s.getAssertions()) {
                    if (a instanceof EqualsAssertion) {
                        assertEquals(false, a.getValue());
                        foundEqualsAssertion = true;
                    }
                }
            }
            assertTrue(foundEqualsAssertion,
                    "Should have EqualsAssertion(false) for assertNotEquals:\n" + tc.toCode());
        }

        @Test
        void parseAssertNotEqualsWithLiteralFallsThrough() {
            // assertNotEquals with a literal expected falls through to InterpretedStatement
            ParseResult r = parseWithAssertImports(
                    "int x = 42;\n" +
                    "assertNotEquals(0, x);");
            TestCase tc = r.getTestCase();
            // Should not crash; the assertNotEquals is preserved as InterpretedStatement
            // since there's no negated PrimitiveAssertion
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        }

        @Test
        void parseAssertSame() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList a = new ArrayList();\n" +
                    "ArrayList b = a;\n" +
                    "assertSame(a, b);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            boolean foundSame = false;
            for (int i = 0; i < tc.size(); i++) {
                for (Assertion a : tc.getStatement(i).getAssertions()) {
                    if (a instanceof SameAssertion) {
                        assertEquals(true, a.getValue());
                        foundSame = true;
                    }
                }
            }
            assertTrue(foundSame, "Should have SameAssertion(true):\n" + tc.toCode());
        }

        @Test
        void parseAssertNotSame() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList a = new ArrayList();\n" +
                    "ArrayList b = new ArrayList();\n" +
                    "assertNotSame(a, b);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            boolean foundNotSame = false;
            for (int i = 0; i < tc.size(); i++) {
                for (Assertion a : tc.getStatement(i).getAssertions()) {
                    if (a instanceof SameAssertion) {
                        assertEquals(false, a.getValue());
                        foundNotSame = true;
                    }
                }
            }
            assertTrue(foundNotSame, "Should have SameAssertion(false):\n" + tc.toCode());
        }

        @Test
        void parseAssertArrayEqualsPreservedAsInterpreted() {
            ParseResult r = parseWithAssertImports(
                    "int[] expected = new int[]{1, 2, 3};\n" +
                    "int[] actual = new int[]{1, 2, 3};\n" +
                    "assertArrayEquals(expected, actual);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Should be preserved as InterpretedStatement
            boolean foundInterpreted = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                if (s instanceof InterpretedStatement
                        && s.getCode().contains("assertArrayEquals")) {
                    foundInterpreted = true;
                }
            }
            assertTrue(foundInterpreted,
                    "assertArrayEquals should be preserved as InterpretedStatement:\n" + tc.toCode());
        }

        @Test
        void parseAssertThrowsWithBlockLambda() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList list = new ArrayList();\n" +
                    "assertThrows(IndexOutOfBoundsException.class, () -> {\n" +
                    "    list.get(0);\n" +
                    "});");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Should have: constructor for ArrayList + get() method call from inside lambda
            boolean foundGet = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                if (s instanceof MethodStatement
                        && ((MethodStatement) s).getMethodName().equals("get")) {
                    foundGet = true;
                }
            }
            assertTrue(foundGet,
                    "Lambda body should be parsed — expected get() call:\n" + tc.toCode());
        }

        @Test
        void parseAssertThrowsWithExpressionLambda() {
            ParseResult r = parseWithAssertImports(
                    "ArrayList list = new ArrayList();\n" +
                    "assertThrows(IndexOutOfBoundsException.class, () -> list.get(0));");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            boolean foundGet = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                if (s instanceof MethodStatement
                        && ((MethodStatement) s).getMethodName().equals("get")) {
                    foundGet = true;
                }
            }
            assertTrue(foundGet,
                    "Expression lambda body should be parsed — expected get() call:\n" + tc.toCode());
        }
    }

    // ========================================================================
    // Mockito patterns
    // ========================================================================

    @Nested
    class MockitoPatterns {

        private ParseResult parseWithMockitoImports(String body) {
            return parse(body, List.of(
                    "import java.util.*;",
                    "import static org.mockito.Mockito.*;",
                    "import org.evosuite.runtime.ViolatedAssumptionAnswer;"
            ));
        }

        @Test
        void parseMockCreation() {
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertTrue(tc.size() >= 1, "Should have at least 1 statement, got " + tc.size());
        }

        @Test
        void parseEvoSuiteMockPattern() {
            // EvoSuite's own pattern: mock with ViolatedAssumptionAnswer + doReturn().when()
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n" +
                    "doReturn(42).when(mockList).size();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());

            // Should have: return value statement(s) + FunctionalMockStatement
            boolean hasFunctionalMock = false;
            FunctionalMockStatement mockStmt = null;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockStatement) {
                    hasFunctionalMock = true;
                    mockStmt = (FunctionalMockStatement) tc.getStatement(i);
                }
            }
            assertTrue(hasFunctionalMock, "Should produce FunctionalMockStatement:\n" + tc.toCode());
            assertEquals(java.util.List.class, mockStmt.getTargetClass());
            assertEquals(1, mockStmt.getMockedMethods().size(), "Should have 1 mocked method");
            assertEquals("size", mockStmt.getMockedMethods().get(0).getMethodName());
        }

        @Test
        void parseEvoSuiteMockForAbstractClass() {
            // CALLS_REAL_METHODS variant → FunctionalMockForAbstractClassStatement
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, CALLS_REAL_METHODS);\n" +
                    "doReturn(42).when(mockList).size();");
            TestCase tc = r.getTestCase();

            boolean hasAbstractMock = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockForAbstractClassStatement) {
                    hasAbstractMock = true;
                }
            }
            assertTrue(hasAbstractMock,
                    "Should produce FunctionalMockForAbstractClassStatement:\n" + tc.toCode());
        }

        @Test
        void parseMultipleStubbings() {
            // Multiple doReturn().when() calls on the same mock
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n" +
                    "doReturn(42).when(mockList).size();\n" +
                    "doReturn(true).when(mockList).isEmpty();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());

            FunctionalMockStatement mockStmt = null;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockStatement) {
                    mockStmt = (FunctionalMockStatement) tc.getStatement(i);
                }
            }
            assertNotNull(mockStmt, "Should produce FunctionalMockStatement:\n" + tc.toCode());
            assertEquals(2, mockStmt.getMockedMethods().size(),
                    "Should have 2 mocked methods");
        }

        @Test
        void parseMockWithNoStubbings() {
            // Just mock() with no doReturn/when — should still create FunctionalMockStatement
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, new ViolatedAssumptionAnswer());");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());

            boolean hasFunctionalMock = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockStatement) {
                    hasFunctionalMock = true;
                }
            }
            assertTrue(hasFunctionalMock,
                    "Should produce FunctionalMockStatement with no stubbings:\n" + tc.toCode());
        }

        @Test
        void parseStandardWhenThenReturn() {
            // Standard Mockito pattern: when(mock.method()).thenReturn(value)
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n" +
                    "when(mockList.size()).thenReturn(42);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());

            FunctionalMockStatement mockStmt = null;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockStatement) {
                    mockStmt = (FunctionalMockStatement) tc.getStatement(i);
                }
            }
            assertNotNull(mockStmt,
                    "Should produce FunctionalMockStatement with when/thenReturn:\n" + tc.toCode());
            assertEquals(1, mockStmt.getMockedMethods().size());
            assertEquals("size", mockStmt.getMockedMethods().get(0).getMethodName());
        }

        @Test
        void parseVerify() {
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class);\n" +
                    "mockList.size();\n" +
                    "verify(mockList).size();");
            TestCase tc = r.getTestCase();
            assertTrue(tc.size() >= 2, "Should have at least 2 statements");
        }

        @Test
        void stubbingStopsAtNonStubbingStatement() {
            // After the stubbing calls, a non-stubbing statement should not be consumed
            ParseResult r = parseWithMockitoImports(
                    "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n" +
                    "doReturn(42).when(mockList).size();\n" +
                    "int x = mockList.size();");
            TestCase tc = r.getTestCase();

            FunctionalMockStatement mockStmt = null;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FunctionalMockStatement) {
                    mockStmt = (FunctionalMockStatement) tc.getStatement(i);
                }
            }
            assertNotNull(mockStmt, "Should produce FunctionalMockStatement:\n" + tc.toCode());
            assertEquals(1, mockStmt.getMockedMethods().size());
            // The last statement (int x = mockList.size()) should be a separate statement
            assertTrue(tc.size() > 1, "Should have additional statements after the mock");
        }
    }

    // ========================================================================
    // String concatenation
    // ========================================================================

    @Nested
    class StringConcatenation {

        @Test
        void stringConcatWithLiterals() {
            ParseResult r = parse("String s = \"hello\" + \" \" + \"world\";");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertEquals(1, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(0));
            assertEquals("hello world", ((StringPrimitiveStatement) tc.getStatement(0)).getValue());
        }

        @Test
        void stringConcatWithVariables() {
            ParseResult r = parse(
                    "int x = 1;\n" +
                    "String s = \"val=\" + x;");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            assertEquals(2, tc.size());
            assertInstanceOf(StringPrimitiveStatement.class, tc.getStatement(1));
            assertEquals("val=1", ((StringPrimitiveStatement) tc.getStatement(1)).getValue());
        }

        @Test
        void stringConcatWithMixedTypes() {
            ParseResult r = parse(
                    "int a = 0;\n" +
                    "char b = 'x';\n" +
                    "long c = 5L;\n" +
                    "String s = \"\" + a + b + c;");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            // Last statement should be the concatenated string
            Statement last = tc.getStatement(tc.size() - 1);
            assertInstanceOf(StringPrimitiveStatement.class, last);
            assertEquals("0x5", ((StringPrimitiveStatement) last).getValue());
        }

        @Test
        void stringConcatFallsBackWhenUnresolvable() {
            // Method call result can't be evaluated at parse time
            ParseResult r = parse(
                    "String s = \"prefix\" + System.lineSeparator();",
                    List.of());
            TestCase tc = r.getTestCase();
            // Should fall back to InterpretedStatement
            assertTrue(tc.size() >= 1);
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(tc.size() - 1));
        }
    }

    // ========================================================================
    // InterpretedStatement fallback
    // ========================================================================

    @Nested
    class InterpretedStatementFallback {

        @Test
        void unsupportedStatementTypePreservedAsInterpreted() {
            // try-catch is not an ExpressionStmt — should become InterpretedStatement
            ParseResult r = parse("try { int x = 1; } catch (Exception e) { }");
            TestCase tc = r.getTestCase();
            assertTrue(tc.size() >= 1);
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(0));
        }

        @Test
        void interpretedStatementPreservesSource() {
            ParseResult r = parse("try { int x = 1; } catch (Exception e) { }");
            TestCase tc = r.getTestCase();
            InterpretedStatement is = (InterpretedStatement) tc.getStatement(0);
            assertTrue(is.getSourceCode().contains("try"));
        }

        @Test
        void logicalNotPreservedAsInterpreted() {
            ParseResult r = parse(
                    "boolean a = true;\n" +
                    "boolean b = !a;");
            TestCase tc = r.getTestCase();
            // b should be an InterpretedStatement, not null/crash
            assertTrue(tc.size() >= 2, "Should have at least 2 statements:\n" + tc.toCode());
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(1),
                    "!a should be InterpretedStatement:\n" + tc.toCode());
        }

        @Test
        void prefixIncrementPreservedAsInterpreted() {
            ParseResult r = parse(
                    "int a = 5;\n" +
                    "int b = ++a;");
            TestCase tc = r.getTestCase();
            assertTrue(tc.size() >= 2, "Should have at least 2 statements:\n" + tc.toCode());
            assertInstanceOf(InterpretedStatement.class, tc.getStatement(1),
                    "++a should be InterpretedStatement:\n" + tc.toCode());
        }

        @Test
        void lambdaExpressionPreservedAsInterpreted() {
            // Lambda used as a value (not in assertThrows)
            ParseResult r = parse(
                    "Runnable r = () -> System.out.println(\"hello\");",
                    List.of());
            TestCase tc = r.getTestCase();
            assertTrue(tc.size() >= 1, "Should have at least 1 statement:\n" + tc.toCode());
            // The lambda should become an InterpretedStatement, not a NullStatement
            boolean foundInterpreted = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof InterpretedStatement) {
                    foundInterpreted = true;
                }
            }
            assertTrue(foundInterpreted, "Lambda should be InterpretedStatement:\n" + tc.toCode());
        }
    }

    // ========================================================================
    // Generics and typed collections
    // ========================================================================

    @Nested
    class GenericsAndCollections {

        @Test
        void parseTypedArrayList() {
            ParseResult r = parse(
                    "ArrayList<String> list = new ArrayList<String>();\n" +
                    "list.add(\"hello\");");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // constructor + string literal + add()
            assertTrue(tc.size() >= 2);
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
            boolean foundAdd = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    MethodStatement ms = (MethodStatement) tc.getStatement(i);
                    if ("add".equals(ms.getMethodName())) foundAdd = true;
                }
            }
            assertTrue(foundAdd, "Should find add() call:\n" + tc.toCode());
        }

        @Test
        void parseDiamondOperator() {
            ParseResult r = parse(
                    "ArrayList<String> list = new ArrayList<>();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            assertEquals(1, tc.size());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
        }

        @Test
        void parseHashMapPutAndGet() {
            ParseResult r = parse(
                    "HashMap<String, Integer> map = new HashMap<>();\n" +
                    "map.put(\"key\", 42);\n" +
                    "Integer val = map.get(\"key\");");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // constructor + string + int + put() + string + get()
            assertTrue(tc.size() >= 4);
            int methodCount = 0;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) methodCount++;
            }
            assertEquals(2, methodCount, "Should have put() and get():\n" + tc.toCode());
        }

        @Test
        void parseLinkedListWithImport() {
            ParseResult r = parse(
                    "LinkedList<Integer> list = new LinkedList<>();\n" +
                    "list.add(1);\n" +
                    "list.add(2);\n" +
                    "int size = list.size();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // constructor + int + add() + int + add() + size()
            int methodCount = 0;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) methodCount++;
            }
            assertEquals(3, methodCount, "Should have 2 add() and 1 size():\n" + tc.toCode());
        }

        @Test
        void parseCollectionsStaticMethod() {
            ParseResult r = parse(
                    "ArrayList<String> list = new ArrayList<>();\n" +
                    "list.add(\"b\");\n" +
                    "list.add(\"a\");\n" +
                    "java.util.Collections.sort(list);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            boolean foundSort = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    MethodStatement ms = (MethodStatement) tc.getStatement(i);
                    if ("sort".equals(ms.getMethodName())) foundSort = true;
                }
            }
            assertTrue(foundSort, "Should have Collections.sort() call:\n" + tc.toCode());
        }

        @Test
        void parseIteratorPattern() {
            ParseResult r = parse(
                    "ArrayList<String> list = new ArrayList<>();\n" +
                    "Iterator<String> it = list.iterator();\n" +
                    "boolean more = it.hasNext();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // constructor + iterator() + hasNext()
            assertTrue(tc.size() >= 3);
        }

        @Test
        void parseStackOperations() {
            ParseResult r = parse(
                    "Stack<Integer> stack = new Stack<>();\n" +
                    "stack.push(10);\n" +
                    "stack.push(20);\n" +
                    "Integer top = stack.pop();");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            int methodCount = 0;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) methodCount++;
            }
            assertEquals(3, methodCount, "Should have 2 push() and 1 pop():\n" + tc.toCode());
        }

        @Test
        void parseNestedGenerics() {
            ParseResult r = parse(
                    "HashMap<String, ArrayList<Integer>> map = new HashMap<>();\n" +
                    "ArrayList<Integer> inner = new ArrayList<>();\n" +
                    "inner.add(1);\n" +
                    "map.put(\"nums\", inner);\n" +
                    "ArrayList<Integer> got = map.get(\"nums\");");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            // HashMap() + ArrayList() + int + add() + string + put() + string + get()
            int methodCount = 0;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) methodCount++;
            }
            assertEquals(3, methodCount, "Should have add(), put(), get():\n" + tc.toCode());
        }

        @Test
        void parseListOfMaps() {
            ParseResult r = parse(
                    "ArrayList<HashMap<String, String>> list = new ArrayList<>();\n" +
                    "HashMap<String, String> entry = new HashMap<>();\n" +
                    "entry.put(\"k\", \"v\");\n" +
                    "list.add(entry);\n" +
                    "HashMap<String, String> first = list.get(0);");
            TestCase tc = r.getTestCase();
            assertFalse(r.hasErrors(), "Should have no errors: " + r.getDiagnostics());
            int methodCount = 0;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) methodCount++;
            }
            assertEquals(3, methodCount, "Should have put(), add(), get():\n" + tc.toCode());
        }
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    @Nested
    class ErrorHandling {

        @Test
        void unresolvedTypeProducesError() {
            ParseResult r = parse("NonExistentType x = new NonExistentType();");
            assertTrue(r.hasErrors());
        }

        @Test
        void emptyBodyProducesNoErrors() {
            ParseResult r = parse("");
            assertFalse(r.hasErrors());
            assertEquals(0, r.getTestCase().size());
        }
    }
}
