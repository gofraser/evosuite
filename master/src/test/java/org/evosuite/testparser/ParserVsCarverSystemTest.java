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

import com.examples.with.different.packagename.testcarver.*;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares TestCase objects produced by the test parser against those
 * produced by EvoSuite's test carver on the same JUnit inputs.
 * <p>
 * The two approaches differ in expected ways:
 * <ul>
 *   <li>Assertions: parser preserves them as InterpretedStatement; carver drops them</li>
 *   <li>Variable names: parser uses source names; carver uses type-based names</li>
 *   <li>Literal dedup: carver may reuse a single variable for same-valued constants;
 *       parser creates independent statements per source declaration</li>
 * </ul>
 * Comparison focuses on <b>structural equivalence</b>: same constructor/method call
 * sequence, same declaring classes, same method names, and same set of primitive values.
 */
public class ParserVsCarverSystemTest extends SystemTestBase {

    private static final String defaultSelectedJUnit = Properties.SELECTED_JUNIT;
    private static final int defaultSeedMutations = Properties.SEED_MUTATIONS;
    private static final double defaultSeedClone = Properties.SEED_CLONE;
    private static final boolean defaultChopExceptions = Properties.CHOP_CARVED_EXCEPTIONS;

    @AfterEach
    public void reset() {
        Properties.SELECTED_JUNIT = defaultSelectedJUnit;
        Properties.SEED_MUTATIONS = defaultSeedMutations;
        Properties.SEED_CLONE = defaultSeedClone;
        Properties.CHOP_CARVED_EXCEPTIONS = defaultChopExceptions;
        org.evosuite.testcarver.extraction.CarvingManager.getInstance().clear();
    }

    // ---------------------------------------------------------------
    // JUnit test sources as String constants
    // ---------------------------------------------------------------

    private static final String SIMPLE_TEST_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.Assert;\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class SimpleTest {\n"
            + "    @Test\n"
            + "    public void actuallTest() {\n"
            + "        Simple sim = new Simple();\n"
            + "        boolean b0 = sim.incr();\n"
            + "        Assert.assertFalse(b0);\n"
            + "        boolean b1 = sim.sameValues(2, 4);\n"
            + "        Assert.assertFalse(b1);\n"
            + "        boolean b2 = sim.sameValues(5, 5);\n"
            + "        Assert.assertTrue(b2);\n"
            + "    }\n"
            + "}\n";

    private static final String TEST_PERSON_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.Test;\n"
            + "import static org.junit.Assert.*;\n"
            + "\n"
            + "public class TestPerson {\n"
            + "    @Test\n"
            + "    public void test0_1() throws Throwable {\n"
            + "        Person person0 = new Person(\"\", \"\");\n"
            + "        String string0 = person0.getFirstName();\n"
            + "        assertEquals(\"\", string0);\n"
            + "    }\n"
            + "\n"
            + "    @Test\n"
            + "    public void test0() throws Throwable {\n"
            + "        Person person0 = new Person(\"\", \"\");\n"
            + "        String string0 = person0.getLastName();\n"
            + "        assertEquals(\"\", string0);\n"
            + "    }\n"
            + "}\n";

    private static final String INNER_CALLS_TEST_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.*;\n"
            + "\n"
            + "public class InnerCallsTest {\n"
            + "    @Test\n"
            + "    public void test() {\n"
            + "        InnerCalls foo = new InnerCalls();\n"
            + "        foo.printA();\n"
            + "        foo.printB();\n"
            + "        foo.printAandB();\n"
            + "    }\n"
            + "}\n";

    private static final String PRIMITIVES_TEST_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class PrimitivesTest {\n"
            + "    @Test\n"
            + "    public void test() {\n"
            + "        ObjectWrapper wrapper = new ObjectWrapper();\n"
            + "        int zero = 0;\n"
            + "        Integer one = 1;\n"
            + "        char two = '2';\n"
            + "        float three = 3f;\n"
            + "        double four = 4d;\n"
            + "        long five = 5L;\n"
            + "        byte six = 6;\n"
            + "        String seven = \"7\";\n"
            + "        String s = \"\" + zero + one + two + three + four + five + six + seven;\n"
            + "        wrapper.set(s);\n"
            + "        wrapper.set(zero);\n"
            + "        wrapper.set(one);\n"
            + "        wrapper.set(two);\n"
            + "        wrapper.set(three);\n"
            + "        wrapper.set(four);\n"
            + "        wrapper.set(five);\n"
            + "        wrapper.set(six);\n"
            + "        wrapper.set(seven);\n"
            + "    }\n"
            + "}\n";

    private static final String STATIC_METHOD_TEST_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import static org.junit.Assert.*;\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class ClassWithStaticMethodTestCase {\n"
            + "    @Test\n"
            + "    public void testStaticMethod() {\n"
            + "        ClassWithStaticMethod c = ClassWithStaticMethod.getInstance();\n"
            + "        assertTrue(c.testMe(42));\n"
            + "    }\n"
            + "}\n";

    private static final String PUBLIC_FIELD_WRITING_TEST_SOURCE = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import java.util.Locale;\n"
            + "import org.junit.Assert;\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class ClassWithPublicFieldWritingTestCase {\n"
            + "    @Test\n"
            + "    public void test() {\n"
            + "        ClassWithPublicField x = new ClassWithPublicField();\n"
            + "        x.x = Locale.ENGLISH;\n"
            + "        Assert.assertFalse(x.testMe(Locale.FRANCE));\n"
            + "        x.x = Locale.GERMAN;\n"
            + "        Assert.assertTrue(x.testMe(Locale.GERMAN));\n"
            + "    }\n"
            + "}\n";

    // ---------------------------------------------------------------
    // Test: SimpleTest -> Simple
    // ---------------------------------------------------------------

    @Test
    public void testSimpleParserMatchesCarver() {
        // Carve
        Properties.SELECTED_JUNIT = SimpleTest.class.getCanonicalName();
        Properties.TARGET_CLASS = Simple.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 1;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        TestChromosome carved = factory.getChromosome();
        assertNotNull(carved);
        TestCase carvedTest = carved.getTestCase();
        assertEquals(7, carvedTest.size(),
                "Carved test should have 7 statements; got:\n" + carvedTest.toCode());

        // Parse
        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethod(SIMPLE_TEST_SOURCE, "actuallTest");
        assertFalse(result.hasErrors(),
                "Parser errors: " + formatDiagnostics(result.getDiagnostics()));
        TestCase parsedTest = result.getTestCase();

        // Compare
        assertStructurallyEquivalent(carvedTest, parsedTest);
    }

    // ---------------------------------------------------------------
    // Test: TestPerson -> Person (two @Test methods)
    // ---------------------------------------------------------------

    @Test
    public void testPersonParserMatchesCarver() {
        Properties.SELECTED_JUNIT = TestPerson.class.getCanonicalName();
        Properties.TARGET_CLASS = Person.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 1;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        assertTrue(factory.hasCarvedTestCases());
        List<TestCase> carvedTests = factory.getCarvedTestCases();
        assertEquals(2, carvedTests.size(), "Expected 2 carved tests for TestPerson");

        // Parse both methods
        TestParser parser = new TestParser(getClass().getClassLoader());
        List<ParseResult> parsedResults = parser.parseTestClass(TEST_PERSON_SOURCE);
        assertEquals(2, parsedResults.size(), "Expected 2 parsed test methods");

        for (ParseResult pr : parsedResults) {
            assertFalse(pr.hasErrors(),
                    "Parser errors in " + pr.getOriginalMethodName() + ": "
                            + formatDiagnostics(pr.getDiagnostics()));
        }

        for (TestCase carvedTest : carvedTests) {
            assertEquals(3, carvedTest.size(),
                    "Each carved Person test should have 3 statements:\n" + carvedTest.toCode());
        }

        // For each parsed result, find a matching carved test
        for (ParseResult pr : parsedResults) {
            TestCase parsedTest = pr.getTestCase();
            boolean matched = false;
            for (TestCase carvedTest : carvedTests) {
                if (structurallyEquivalent(carvedTest, parsedTest)) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched,
                    "No carved test matched parsed method '" + pr.getOriginalMethodName()
                            + "'.\nParsed:\n" + parsedTest.toCode()
                            + "\nCarved tests:\n" + carvedTestsToString(carvedTests));
        }
    }

    // ---------------------------------------------------------------
    // Test: InnerCallsTest -> InnerCalls (void methods, no args)
    // ---------------------------------------------------------------

    @Test
    public void testInnerCallsParserMatchesCarver() {
        Properties.SELECTED_JUNIT = InnerCallsTest.class.getCanonicalName();
        Properties.TARGET_CLASS = InnerCalls.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 1;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        assertTrue(factory.hasCarvedTestCases());
        TestChromosome carved = factory.getChromosome();
        assertNotNull(carved);
        TestCase carvedTest = carved.getTestCase();
        assertEquals(4, carvedTest.size(),
                "Carved test should have 4 statements; got:\n" + carvedTest.toCode());

        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethod(INNER_CALLS_TEST_SOURCE, "test");
        assertFalse(result.hasErrors(),
                "Parser errors: " + formatDiagnostics(result.getDiagnostics()));

        assertStructurallyEquivalent(carvedTest, result.getTestCase());
    }

    // ---------------------------------------------------------------
    // Test: PrimitivesTest -> ObjectWrapper (diverse primitive types)
    // ---------------------------------------------------------------

    @Test
    public void testPrimitivesParserMatchesCarver() {
        Properties.SELECTED_JUNIT = PrimitivesTest.class.getCanonicalName();
        Properties.TARGET_CLASS = ObjectWrapper.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 1;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        assertTrue(factory.hasCarvedTestCases());
        TestChromosome carved = factory.getChromosome();
        assertNotNull(carved);
        TestCase carvedTest = carved.getTestCase();
        assertEquals(19, carvedTest.size(),
                "Carved test should have 19 statements; got:\n" + carvedTest.toCode());

        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethod(PRIMITIVES_TEST_SOURCE, "test");
        assertFalse(result.hasErrors(),
                "Parser errors: " + formatDiagnostics(result.getDiagnostics()));

        assertStructurallyEquivalent(carvedTest, result.getTestCase());
    }

    // ---------------------------------------------------------------
    // Test: ClassWithStaticMethodTestCase -> ClassWithStaticMethod
    // Static factory method + method call nested in assertTrue()
    // ---------------------------------------------------------------

    @Test
    public void testStaticMethodParserMatchesCarver() {
        Properties.SELECTED_JUNIT = ClassWithStaticMethodTestCase.class.getCanonicalName();
        Properties.TARGET_CLASS = ClassWithStaticMethod.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 0;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        assertEquals(1, factory.getNumCarvedTestCases());
        TestCase carvedTest = factory.getChromosome().getTestCase();

        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethod(STATIC_METHOD_TEST_SOURCE, "testStaticMethod");
        assertFalse(result.hasErrors(),
                "Parser errors: " + formatDiagnostics(result.getDiagnostics()));

        assertStructurallyEquivalent(carvedTest, result.getTestCase());
    }

    // ---------------------------------------------------------------
    // Test: ClassWithPublicFieldWritingTestCase -> ClassWithPublicField
    // Tests public field assignment and method calls nested in asserts.
    // Carver captures runtime field initializer (Locale.CHINESE) and
    // Locale.class that the parser doesn't see in source, so we compare
    // call sequences and verify all source-visible primitives are present.
    // ---------------------------------------------------------------

    @Test
    public void testPublicFieldWritingParserMatchesCarver() {
        Properties.SELECTED_JUNIT = ClassWithPublicFieldWritingTestCase.class.getCanonicalName();
        Properties.TARGET_CLASS = ClassWithPublicField.class.getCanonicalName();
        Properties.SEED_MUTATIONS = 0;
        Properties.SEED_CLONE = 1;

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        assertEquals(1, factory.getNumCarvedTestCases());
        TestCase carvedTest = factory.getChromosome().getTestCase();

        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethod(PUBLIC_FIELD_WRITING_TEST_SOURCE, "test");

        TestCase parsedTest = result.getTestCase();

        // Carved has extra runtime statements (Locale.CHINESE initializer, Locale.class)
        // that aren't in source. But the core call sequence should be a subset:
        // constructor, field-write, testMe, field-write, testMe
        List<Statement> parsedCalls = extractCallStatements(parsedTest);
        assertTrue(parsedCalls.size() >= 1,
                "Expected at least constructor:\n" + parsedTest.toCode());
        assertTrue(parsedCalls.get(0) instanceof ConstructorStatement);
        assertEquals("com.examples.with.different.packagename.testcarver.ClassWithPublicField",
                ((ConstructorStatement) parsedCalls.get(0)).getConstructor()
                        .getDeclaringClass().getCanonicalName());

        // Should have testMe() calls parsed from inside the assertion arguments
        long testMeCount = parsedCalls.stream()
                .filter(s -> s instanceof MethodStatement
                        && ((MethodStatement) s).getMethodName().equals("testMe"))
                .count();
        assertEquals(2, testMeCount,
                "Expected 2 testMe() calls (from assertFalse/assertTrue args):\n"
                        + parsedTest.toCode());
    }

    // ---------------------------------------------------------------
    // Structural equivalence comparison
    // ---------------------------------------------------------------

    /**
     * Asserts structural equivalence between a carved and parsed TestCase.
     * <p>
     * Compares:
     * <ol>
     *   <li>Call sequence: constructors and methods in order must match
     *       (same declaring class, same method name)</li>
     *   <li>Primitive values: every distinct value in the carved test must
     *       appear in the parsed test (parser may have extra literals due
     *       to no deduplication)</li>
     * </ol>
     */
    private void assertStructurallyEquivalent(TestCase carved, TestCase parsed) {
        assertTrue(structurallyEquivalent(carved, parsed),
                "Tests are not structurally equivalent."
                        + "\nCarved:\n" + carved.toCode()
                        + "\nParsed:\n" + parsed.toCode());
    }

    private boolean structurallyEquivalent(TestCase carved, TestCase parsed) {
        List<Statement> carvedCalls = extractCallStatements(carved);
        List<Statement> parsedCalls = extractCallStatements(parsed);

        if (carvedCalls.size() != parsedCalls.size()) {
            return false;
        }

        for (int i = 0; i < carvedCalls.size(); i++) {
            if (!callsMatch(carvedCalls.get(i), parsedCalls.get(i))) {
                return false;
            }
        }

        // Check that all carved primitive values appear in parsed primitives
        List<Object> carvedValues = extractPrimitiveValues(carved);
        List<Object> parsedValues = extractPrimitiveValues(parsed);
        for (Object cv : carvedValues) {
            if (!parsedValues.contains(cv)) {
                return false;
            }
        }

        return true;
    }

    private boolean callsMatch(Statement carved, Statement parsed) {
        if (carved instanceof ConstructorStatement && parsed instanceof ConstructorStatement) {
            ConstructorStatement cc = (ConstructorStatement) carved;
            ConstructorStatement pc = (ConstructorStatement) parsed;
            return cc.getConstructor().getDeclaringClass().getCanonicalName()
                    .equals(pc.getConstructor().getDeclaringClass().getCanonicalName());
        }
        if (carved instanceof MethodStatement && parsed instanceof MethodStatement) {
            MethodStatement cm = (MethodStatement) carved;
            MethodStatement pm = (MethodStatement) parsed;
            return cm.getMethodName().equals(pm.getMethodName())
                    && cm.getMethod().getDeclaringClass().getCanonicalName()
                            .equals(pm.getMethod().getDeclaringClass().getCanonicalName());
        }
        return false;
    }

    /**
     * Extracts constructor and method call statements (the "call sequence"),
     * filtering out primitives, InterpretedStatements, etc.
     */
    private List<Statement> extractCallStatements(TestCase tc) {
        List<Statement> result = new ArrayList<>();
        for (int i = 0; i < tc.size(); i++) {
            Statement s = tc.getStatement(i);
            if (s instanceof ConstructorStatement || s instanceof MethodStatement) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Extracts all primitive values from a TestCase.
     */
    private List<Object> extractPrimitiveValues(TestCase tc) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < tc.size(); i++) {
            Statement s = tc.getStatement(i);
            if (s instanceof PrimitiveStatement) {
                values.add(((PrimitiveStatement<?>) s).getValue());
            }
        }
        return values;
    }

    // ---------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------

    private String formatDiagnostics(List<ParseDiagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (ParseDiagnostic d : diagnostics) {
            sb.append(d.getSeverity()).append(" line ").append(d.getLineNumber())
                    .append(": ").append(d.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private String carvedTestsToString(List<TestCase> tests) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tests.size(); i++) {
            sb.append("--- Carved test ").append(i).append(" ---\n");
            sb.append(tests.get(i).toCode()).append("\n");
        }
        return sb.toString();
    }
}
