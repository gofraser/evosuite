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
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.TestFactory;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that close coverage gaps in the parser: generic SUT classes,
 * inner-class constructors, inheritance hierarchies, and multi-source seeding.
 * <p>
 * These tests live in the {@code master} module because they need the example
 * classes on the classpath for reflection-based type resolution.
 */
public class ParserCoverageGapsSystemTest extends SystemTestBase {

    private static final String defaultSelectedJUnit = Properties.SELECTED_JUNIT;
    private static final TestFactory defaultTestFactory = Properties.TEST_FACTORY;
    private static final int defaultSeedMutations = Properties.SEED_MUTATIONS;
    private static final double defaultSeedClone = Properties.SEED_CLONE;
    private static final String defaultSeedTestSourceDir = Properties.SEED_TEST_SOURCE_DIR;

    @AfterEach
    public void reset() {
        Properties.SELECTED_JUNIT = defaultSelectedJUnit;
        Properties.TEST_FACTORY = defaultTestFactory;
        Properties.SEED_MUTATIONS = defaultSeedMutations;
        Properties.SEED_CLONE = defaultSeedClone;
        Properties.SEED_TEST_SOURCE_DIR = defaultSeedTestSourceDir;
    }

    // ------------------------------------------------------------------
    // Source constants
    // ------------------------------------------------------------------

    private static final String GENERIC_TWO_PARAM_TEST = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.Assert;\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class GenericObjectWrapperTwoParameterTest {\n"
            + "    @Test\n"
            + "    public void test01() {\n"
            + "        GenericObjectWrapperTwoParameter<String, String> wrapper = new GenericObjectWrapperTwoParameter<>();\n"
            + "        Assert.assertNull(wrapper.getValue());\n"
            + "        wrapper.setValue(\"Test\");\n"
            + "        Assert.assertEquals(\"Test\", wrapper.getValue());\n"
            + "        Assert.assertTrue(wrapper.isEqual(\"Test\"));\n"
            + "        Assert.assertFalse(wrapper.isEqual(\"Not\"));\n"
            + "    }\n"
            + "}\n";

    private static final String INNER_CONSTRUCTOR_TEST = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import org.junit.*;\n"
            + "\n"
            + "public class InnerConstructorTest {\n"
            + "    @Test\n"
            + "    public void test() {\n"
            + "        InnerConstructor c = new InnerConstructor();\n"
            + "        c.getFoo();\n"
            + "    }\n"
            + "}\n";

    private static final String INHERITANCE_TEST = ""
            + "package com.examples.with.different.packagename.testcarver;\n"
            + "\n"
            + "import static org.junit.Assert.assertTrue;\n"
            + "import org.junit.Test;\n"
            + "\n"
            + "public class ConcreteSubClassWithFieldsTestCase {\n"
            + "    @Test\n"
            + "    public void test1() {\n"
            + "        ConcreteSubClassWithFields foo = new ConcreteSubClassWithFields(42);\n"
            + "        assertTrue(foo.testMe(42));\n"
            + "    }\n"
            + "    @Test\n"
            + "    public void test2() {\n"
            + "        ConcreteSubClassWithFields foo = new ConcreteSubClassWithFields(42, 100);\n"
            + "        assertTrue(foo.testMe(42));\n"
            + "    }\n"
            + "}\n";

    // ------------------------------------------------------------------
    // Test: generic SUT class with two type parameters
    // ------------------------------------------------------------------

    @Test
    public void testParseGenericTwoParameterClass() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        List<ParseResult> results = parser.parseTestClass(GENERIC_TWO_PARAM_TEST);
        assertEquals(1, results.size());
        ParseResult r = results.get(0);
        assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        TestCase tc = r.getTestCase();

        boolean foundConstructor = false;
        List<String> methodNames = new ArrayList<>();
        for (int i = 0; i < tc.size(); i++) {
            Statement s = tc.getStatement(i);
            if (s instanceof ConstructorStatement) {
                String declClass = ((ConstructorStatement) s).getConstructor()
                        .getDeclaringClass().getCanonicalName();
                assertEquals("com.examples.with.different.packagename.testcarver.GenericObjectWrapperTwoParameter",
                        declClass);
                foundConstructor = true;
            } else if (s instanceof MethodStatement) {
                methodNames.add(((MethodStatement) s).getMethodName());
            }
        }
        assertTrue(foundConstructor,
                "Should find GenericObjectWrapperTwoParameter constructor:\n" + tc.toCode());
        assertTrue(methodNames.contains("getValue"),
                "Should have getValue():\n" + tc.toCode());
        assertTrue(methodNames.contains("setValue"),
                "Should have setValue():\n" + tc.toCode());
        assertTrue(methodNames.contains("isEqual"),
                "Should have isEqual():\n" + tc.toCode());
    }

    // ------------------------------------------------------------------
    // Test: inner-class constructor (InnerConstructor extends abstract)
    // ------------------------------------------------------------------

    @Test
    public void testParseInnerConstructorClass() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        List<ParseResult> results = parser.parseTestClass(INNER_CONSTRUCTOR_TEST);
        assertEquals(1, results.size());
        ParseResult r = results.get(0);
        assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        TestCase tc = r.getTestCase();

        assertEquals(2, tc.size(), "Expected constructor + getFoo():\n" + tc.toCode());
        assertInstanceOf(ConstructorStatement.class, tc.getStatement(0));
        String declClass = ((ConstructorStatement) tc.getStatement(0)).getConstructor()
                .getDeclaringClass().getCanonicalName();
        assertEquals("com.examples.with.different.packagename.testcarver.InnerConstructor",
                declClass);
        assertInstanceOf(MethodStatement.class, tc.getStatement(1));
        assertEquals("getFoo", ((MethodStatement) tc.getStatement(1)).getMethodName());
    }

    // ------------------------------------------------------------------
    // Test: 3-level inheritance hierarchy
    // ------------------------------------------------------------------

    @Test
    public void testParseInheritanceHierarchy() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        List<ParseResult> results = parser.parseTestClass(INHERITANCE_TEST);
        assertEquals(2, results.size(), "Should parse both @Test methods");

        for (ParseResult r : results) {
            assertFalse(r.hasErrors(), "Errors in " + r.getOriginalMethodName() + ": "
                    + r.getDiagnostics());
            TestCase tc = r.getTestCase();

            boolean foundConstructor = false;
            boolean foundTestMe = false;
            for (int i = 0; i < tc.size(); i++) {
                Statement s = tc.getStatement(i);
                if (s instanceof ConstructorStatement) {
                    String declClass = ((ConstructorStatement) s).getConstructor()
                            .getDeclaringClass().getCanonicalName();
                    assertEquals("com.examples.with.different.packagename.testcarver.ConcreteSubClassWithFields",
                            declClass);
                    foundConstructor = true;
                } else if (s instanceof MethodStatement
                        && ((MethodStatement) s).getMethodName().equals("testMe")) {
                    foundTestMe = true;
                }
            }
            assertTrue(foundConstructor, "Should find constructor in "
                    + r.getOriginalMethodName() + ":\n" + tc.toCode());
            assertTrue(foundTestMe, "Should find testMe() in "
                    + r.getOriginalMethodName() + ":\n" + tc.toCode());
        }
    }

    // ------------------------------------------------------------------
    // Test: multiple colon-separated seed sources via PARSED_JUNIT
    // ------------------------------------------------------------------

    @Test
    public void testMultipleSeedSources() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = DifficultClassWithoutCarving.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.TEST_FACTORY = TestFactory.PARSED_JUNIT;
        // Two seed classes separated by colon — only the second one is relevant to the target
        Properties.SELECTED_JUNIT =
                SimpleTest.class.getCanonicalName() + ":"
                + DifficultClassWithoutCarvingTest.class.getCanonicalName();
        Properties.SEED_TEST_SOURCE_DIR = "src/test/java";
        Properties.SEED_CLONE = 1.0;
        Properties.SEED_MUTATIONS = 1;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        assertEquals(1d, best.getCoverage(), 0.001, "Expected optimal coverage with multi-source seeding");
    }
}
