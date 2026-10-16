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
package org.evosuite;

import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSuiteGeneratorPrefixTest {

    @BeforeEach
    void setUp() {
        Properties.getInstance().resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    private static TestChromosome test(int... values) {
        DefaultTestCase test = new DefaultTestCase();
        for (int value : values) {
            test.addStatement(new IntPrimitiveStatement(test, value));
        }
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        return chromosome;
    }

    private static TestFitnessFunction goal(int line) {
        return new LineCoverageTestFitness("Foo", "bar()V", line);
    }

    private static void addPrimitiveAssertion(TestChromosome test, int position, int value) {
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(test.getTestCase().getStatement(position).getReturnValue());
        assertion.setValue(value);
        test.getTestCase().getStatement(position).addAssertion(assertion);
    }

    @Test
    void prefixesAreMergedIntoTheTestThatContainsThem() {
        TestChromosome longer = test(1, 2, 3);
        TestChromosome prefix = test(1, 2);
        TestChromosome duplicate = test(1, 2, 3);
        TestChromosome unrelated = test(1, 5);
        longer.getTestCase().addCoveredGoal(goal(1));
        prefix.getTestCase().addCoveredGoal(goal(2));
        duplicate.getTestCase().addCoveredGoal(goal(3));
        unrelated.getTestCase().addCoveredGoal(goal(4));
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(prefix);
        suite.addTest(longer);
        suite.addTest(unrelated);
        suite.addTest(duplicate);

        new TestSuiteGenerator().removePrefixTests(suite);

        // The prefix and the duplicate fold into the first equal-or-longer
        // test, in the suite's original order, with their goals credited to it.
        assertEquals(2, suite.size());
        assertSame(longer, suite.getTestChromosome(0));
        assertSame(unrelated, suite.getTestChromosome(1));
        assertEquals(3, longer.getTestCase().getCoveredGoals().size());
        assertTrue(longer.getTestCase().getCoveredGoals().containsAll(
                java.util.Arrays.asList(goal(1), goal(2), goal(3))));
        assertEquals(4, suite.getCoveredGoals().size());
    }

    @Test
    void prefixAssertionsSurviveOnTheMergedTest() {
        // The review's reproduction: a short test with one assertion, and a longer
        // test without any. Before the fix, removing the prefix also discarded its
        // only assertion; the merged test must keep it, at the same position.
        TestChromosome prefix = test(1);
        addPrimitiveAssertion(prefix, 0, 1);
        TestChromosome longer = test(1, 2);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(prefix);
        suite.addTest(longer);

        new TestSuiteGenerator().removePrefixTests(suite);

        assertEquals(1, suite.size());
        assertSame(longer, suite.getTestChromosome(0));
        assertEquals(1, longer.getTestCase().getStatement(0).getAssertions().size());
        Assertion merged = longer.getTestCase().getStatement(0).getAssertions().iterator().next();
        assertEquals(1, merged.getValue());
        assertEquals(0, longer.getTestCase().getStatement(1).getAssertions().size());
    }

    @Test
    void duplicateAssertionsAreNotAddedTwice() {
        // Both tests already carry an equal assertion at the shared position;
        // addAssertions' existing contains() guard must stop a second copy.
        TestChromosome prefix = test(1);
        addPrimitiveAssertion(prefix, 0, 1);
        TestChromosome longer = test(1, 2);
        addPrimitiveAssertion(longer, 0, 1);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(prefix);
        suite.addTest(longer);

        new TestSuiteGenerator().removePrefixTests(suite);

        assertEquals(1, suite.size());
        assertEquals(1, longer.getTestCase().getStatement(0).getAssertions().size());
    }

    @Test
    void prefixAssertionsMergeAlongsideTheLongerTestsOwnAssertions() {
        // The prefix's assertion sits on a statement the longer test also has, and
        // the longer test carries further assertions beyond the prefix's length.
        // Both sets of assertions must be present on the merged test afterward.
        TestChromosome prefix = test(1, 2);
        addPrimitiveAssertion(prefix, 0, 1);
        TestChromosome longer = test(1, 2, 3);
        addPrimitiveAssertion(longer, 2, 3);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(prefix);
        suite.addTest(longer);

        new TestSuiteGenerator().removePrefixTests(suite);

        assertEquals(1, suite.size());
        assertSame(longer, suite.getTestChromosome(0));
        assertEquals(1, longer.getTestCase().getStatement(0).getAssertions().size());
        assertEquals(0, longer.getTestCase().getStatement(1).getAssertions().size());
        assertEquals(1, longer.getTestCase().getStatement(2).getAssertions().size());
    }

    @Test
    void prefixesAreKeptWhenTestsMayCallEachOther() {
        Properties.CALL_PROBABILITY = 0.5;
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test(1, 2));
        suite.addTest(test(1, 2, 3));

        new TestSuiteGenerator().removePrefixTests(suite);

        assertEquals(2, suite.size());
    }
}
