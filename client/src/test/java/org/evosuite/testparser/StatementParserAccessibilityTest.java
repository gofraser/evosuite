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
package org.evosuite.testparser;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the parser's accessibility handling.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Same-package protected/package-private members are accepted</li>
 *   <li>Cross-package non-public members are rejected</li>
 *   <li>Private members remain rejected in all cases</li>
 *   <li>Public members are always accepted</li>
 * </ul>
 */
class StatementParserAccessibilityTest {

    // ========================================================================
    // Test fixture classes (same package: org.evosuite.testparser)
    // ========================================================================

    /** Target class with a protected constructor. */
    public static class ProtectedCtorTarget {
        protected int value;

        protected ProtectedCtorTarget(int value) {
            this.value = value;
        }
    }

    /** Target class with a protected method. */
    public static class ProtectedMethodTarget {
        protected String compute(String input) {
            return input + "!";
        }
    }

    /** Target class with a package-private constructor. */
    public static class PackagePrivateCtorTarget {
        int field;

        PackagePrivateCtorTarget(int f) {
            this.field = f;
        }
    }

    /** Target class with a package-private method. */
    public static class PackagePrivateMethodTarget {
        String render(String data) {
            return data;
        }
    }

    /** Target class with a private constructor (should always be rejected). */
    public static class PrivateCtorTarget {
        private PrivateCtorTarget(int x) {
        }
    }

    private TestParser parser;
    private String savedTargetClass;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
        savedTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = savedTargetClass;
    }

    private ParseResult parseLlm(String body, List<String> imports) {
        parser.setMarkParsedFromLlm(true);
        return parser.parseTestMethodBody(body, imports);
    }

    private ParseResult parse(String body, List<String> imports) {
        return parser.parseTestMethodBody(body, imports);
    }

    // ========================================================================
    // Same-package protected members: should be accepted
    // ========================================================================

    @Nested
    class SamePackageProtected {

        @Test
        void protectedConstructorInSamePackageIsAccepted() {
            // ProtectedCtorTarget is in org.evosuite.testparser.
            // With TARGET_CLASS unset (permissive) or matching package,
            // the protected constructor should be accessible.
            ParseResult r = parseLlm(
                    "int val = 42;\n"
                            + "StatementParserAccessibilityTest.ProtectedCtorTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedCtorTarget(val);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            // The last statement should be a real ConstructorStatement, not a null fallback
            assertTrue(tc.size() >= 2, "Expected at least int + constructor statements: " + tc.toCode());
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(tc.size() - 1),
                    "Protected constructor should produce a ConstructorStatement, not a fallback:\n"
                            + tc.toCode());
        }

        @Test
        void protectedMethodInSamePackageIsAccepted() {
            ParseResult r = parseLlm(
                    "StatementParserAccessibilityTest.ProtectedMethodTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedMethodTarget();\n"
                            + "String result = t.compute(\"hello\");",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            boolean hasMethodStatement = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    MethodStatement ms = (MethodStatement) tc.getStatement(i);
                    if ("compute".equals(ms.getMethodName())) {
                        hasMethodStatement = true;
                    }
                }
            }
            assertTrue(hasMethodStatement,
                    "Protected method should produce a MethodStatement:\n" + tc.toCode());
        }

        @Test
        void protectedFieldInSamePackageIsAccepted() {
            ParseResult r = parseLlm(
                    "StatementParserAccessibilityTest.ProtectedCtorTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedCtorTarget(10);\n"
                            + "int v = t.value;",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            boolean hasFieldStatement = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FieldStatement) {
                    hasFieldStatement = true;
                }
            }
            assertTrue(hasFieldStatement,
                    () -> "Protected field should produce a FieldStatement:\n" + tc.toCode());
        }

        @Test
        void protectedConstructorWithExplicitTargetClassIsAccepted() {
            // Set TARGET_CLASS to a class in the same package as ProtectedCtorTarget
            Properties.TARGET_CLASS = "org.evosuite.testparser.SomeTargetClass";

            ParseResult r = parseLlm(
                    "int val = 1;\n"
                            + "StatementParserAccessibilityTest.ProtectedCtorTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedCtorTarget(val);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(tc.size() - 1),
                    "Protected constructor in SUT package should produce ConstructorStatement:\n"
                            + tc.toCode());
        }
    }

    // ========================================================================
    // Same-package package-private members: should be accepted
    // ========================================================================

    @Nested
    class SamePackagePackagePrivate {

        @Test
        void packagePrivateConstructorInSamePackageIsAccepted() {
            ParseResult r = parseLlm(
                    "int f = 7;\n"
                            + "StatementParserAccessibilityTest.PackagePrivateCtorTarget t = "
                            + "new StatementParserAccessibilityTest.PackagePrivateCtorTarget(f);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(tc.size() - 1),
                    "Package-private constructor should produce ConstructorStatement:\n"
                            + tc.toCode());
        }

        @Test
        void packagePrivateMethodInSamePackageIsAccepted() {
            ParseResult r = parseLlm(
                    "StatementParserAccessibilityTest.PackagePrivateMethodTarget t = "
                            + "new StatementParserAccessibilityTest.PackagePrivateMethodTarget();\n"
                            + "String out = t.render(\"data\");",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            boolean hasMethodStatement = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    MethodStatement ms = (MethodStatement) tc.getStatement(i);
                    if ("render".equals(ms.getMethodName())) {
                        hasMethodStatement = true;
                    }
                }
            }
            assertTrue(hasMethodStatement,
                    "Package-private method should produce MethodStatement:\n" + tc.toCode());
        }
    }

    // ========================================================================
    // Cross-package non-public members: should be rejected
    // ========================================================================

    @Nested
    class CrossPackageRejection {

        @Test
        void protectedConstructorInDifferentPackageIsRejected() {
            // Set TARGET_CLASS to a different package
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "int val = 42;\n"
                            + "StatementParserAccessibilityTest.ProtectedCtorTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedCtorTarget(val);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            TestCase tc = r.getTestCase();
            TestCodeVisitor visitor = new TestCodeVisitor();
            tc.accept(visitor);
            String code = visitor.getCode();

            // The constructor should NOT be a ConstructorStatement because
            // ProtectedCtorTarget is in org.evosuite.testparser but
            // TARGET_CLASS is in com.example.other
            boolean hasRealConstructor = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof ConstructorStatement) {
                    ConstructorStatement cs = (ConstructorStatement) tc.getStatement(i);
                    if (cs.getReturnClass() == ProtectedCtorTarget.class) {
                        hasRealConstructor = true;
                    }
                }
            }
            assertFalse(hasRealConstructor,
                    "Protected constructor in different package should be rejected:\n" + code);
        }

        @Test
        void protectedMethodInDifferentPackageIsRejected() {
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "StatementParserAccessibilityTest.ProtectedMethodTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedMethodTarget();\n"
                            + "String result = t.compute(\"hello\");",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            TestCase tc = r.getTestCase();

            boolean hasComputeMethod = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    MethodStatement ms = (MethodStatement) tc.getStatement(i);
                    if ("compute".equals(ms.getMethodName())) {
                        hasComputeMethod = true;
                    }
                }
            }
            assertFalse(hasComputeMethod,
                    "Protected method in different package should be rejected:\n" + tc.toCode());
        }

        @Test
        void protectedFieldInDifferentPackageIsRejected() {
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "StatementParserAccessibilityTest.ProtectedCtorTarget t = "
                            + "new StatementParserAccessibilityTest.ProtectedCtorTarget(10);\n"
                            + "int v = t.value;",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            TestCase tc = r.getTestCase();

            boolean hasFieldStatement = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof FieldStatement) {
                    hasFieldStatement = true;
                }
            }
            assertFalse(hasFieldStatement,
                    "Protected field in different package should be rejected:\n" + tc.toCode());
        }

        @Test
        void packagePrivateConstructorInDifferentPackageIsRejected() {
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "int f = 7;\n"
                            + "StatementParserAccessibilityTest.PackagePrivateCtorTarget t = "
                            + "new StatementParserAccessibilityTest.PackagePrivateCtorTarget(f);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            TestCase tc = r.getTestCase();

            boolean hasRealConstructor = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof ConstructorStatement) {
                    ConstructorStatement cs = (ConstructorStatement) tc.getStatement(i);
                    if (cs.getReturnClass() == PackagePrivateCtorTarget.class) {
                        hasRealConstructor = true;
                    }
                }
            }
            assertFalse(hasRealConstructor,
                    "Package-private constructor in different package should be rejected:\n"
                            + tc.toCode());
        }
    }

    // ========================================================================
    // Private members: should always be rejected
    // ========================================================================

    @Nested
    class PrivateAlwaysRejected {

        @Test
        void privateConstructorInSamePackageIsRejected() {
            // Even with TARGET_CLASS unset (permissive) or same-package,
            // private constructors should never be accessible
            ParseResult r = parseLlm(
                    "int x = 1;\n"
                            + "StatementParserAccessibilityTest.PrivateCtorTarget t = "
                            + "new StatementParserAccessibilityTest.PrivateCtorTarget(x);",
                    List.of("import org.evosuite.testparser.StatementParserAccessibilityTest;"));

            TestCase tc = r.getTestCase();

            boolean hasRealConstructor = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof ConstructorStatement) {
                    ConstructorStatement cs = (ConstructorStatement) tc.getStatement(i);
                    if (cs.getReturnClass() == PrivateCtorTarget.class) {
                        hasRealConstructor = true;
                    }
                }
            }
            assertFalse(hasRealConstructor,
                    "Private constructor should always be rejected:\n" + tc.toCode());
        }
    }

    // ========================================================================
    // Public members: should always be accepted
    // ========================================================================

    @Nested
    class PublicAlwaysAccepted {

        @Test
        void publicConstructorInDifferentPackageIsAccepted() {
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "java.util.ArrayList list = new java.util.ArrayList();",
                    List.of("import java.util.ArrayList;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();
            assertInstanceOf(ConstructorStatement.class, tc.getStatement(0),
                    "Public constructor should always be accepted:\n" + tc.toCode());
        }

        @Test
        void publicMethodInDifferentPackageIsAccepted() {
            Properties.TARGET_CLASS = "com.example.other.SomeClass";

            ParseResult r = parseLlm(
                    "java.util.ArrayList list = new java.util.ArrayList();\n"
                            + "int size = list.size();",
                    List.of("import java.util.ArrayList;"));

            assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            boolean hasMethodStatement = false;
            for (int i = 0; i < tc.size(); i++) {
                if (tc.getStatement(i) instanceof MethodStatement) {
                    hasMethodStatement = true;
                }
            }
            assertTrue(hasMethodStatement,
                    "Public method should always be accepted:\n" + tc.toCode());
        }
    }
}
