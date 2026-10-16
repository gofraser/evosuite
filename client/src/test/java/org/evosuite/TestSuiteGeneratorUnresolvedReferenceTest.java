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
package org.evosuite;

import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.TestParser;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test in the final suite whose assignment uses a value no statement of the test defines
 * made the minimizer's deep clone throw, which aborted post-processing of the whole suite: it
 * was written without minimisation, assertions or the JUnit check. Post-processing now drops
 * such tests before anything clones them.
 */
public class TestSuiteGeneratorUnresolvedReferenceTest {

    @SuppressWarnings("unused")
    public static class Holder {
        public int value;
        public static int shared;
    }

    private ClientNodeImpl<?> previousClientNode;

    @BeforeEach
    public void setUp() throws Exception {
        Properties.getInstance().resetToDefaults();
        Properties.INLINE = false;
        Properties.MINIMIZE = true;
        Properties.COVERAGE = false;
        Properties.ASSERTIONS = false;
        Properties.JUNIT_TESTS = false;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.LINE};
        TimeController.resetSingleton();
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        previousClientNode = (ClientNodeImpl<?>) field.get(ClientServices.getInstance());
        field.set(ClientServices.getInstance(), new DummyClientNodeImpl());
    }

    @AfterEach
    public void tearDown() throws Exception {
        Properties.getInstance().resetToDefaults();
        TimeController.resetSingleton();
        Field field = ClientServices.class.getDeclaredField("clientNode");
        field.setAccessible(true);
        field.set(ClientServices.getInstance(), previousClientNode);
    }

    @Test
    public void testWithOrphanedAssignmentValueIsDroppedBeforeMinimization() throws Exception {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(testAssigningValueOf(new DefaultTestCase()));

        new TestSuiteGenerator().postProcessTests(suite);

        assertEquals(0, suite.size());
    }

    @Test
    public void testWithLaterDefinedAssignmentValueIsDroppedBeforeMinimization() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        DefaultTestCase broken = testAssigningValueOf(test);
        // The value now exists, but only after the assignment that uses it.
        test.addStatement(new IntPrimitiveStatement(test, 5));
        broken.getStatement(1).replace(((AssignmentStatement) broken.getStatement(1)).getValue(),
                test.getStatement(2).getReturnValue());
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(broken);

        new TestSuiteGenerator().postProcessTests(suite);

        assertEquals(0, suite.size());
    }

    @Test
    public void testAssigningAnInstanceFieldValueIsKept() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference target = newHolder(test);
        VariableReference other = newHolder(test);
        test.addStatement(new AssignmentStatement(test, valueField(test, target),
                valueField(test, other)));

        assertKeptAndCopyable(test);
    }

    @Test
    public void testAssigningAStaticFieldValueIsKept() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference target = newHolder(test);
        FieldReference shared = new FieldReference(test,
                new GenericField(Holder.class.getField("shared"), Holder.class));
        test.addStatement(new AssignmentStatement(test, valueField(test, target), shared));

        assertKeptAndCopyable(test);
    }

    @Test
    public void testAssigningAnArrayElementValueIsKept() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference target = newHolder(test);
        ArrayReference array = (ArrayReference) test.addStatement(
                new ArrayStatement(test, int[].class, 2));
        test.addStatement(new AssignmentStatement(test, valueField(test, target),
                new ArrayIndex(test, array, 1)));

        assertKeptAndCopyable(test);
    }

    @Test
    public void testAssigningAFieldOfALaterDefinedObjectIsDropped() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference target = newHolder(test);
        ConstructorStatement later = new ConstructorStatement(test,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                Collections.emptyList());
        test.addStatement(new AssignmentStatement(test, valueField(test, target),
                valueField(test, later.getReturnValue())));
        // The source object of the assigned field only exists after the assignment.
        test.addStatement(later);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);

        new TestSuiteGenerator().removeTestsWithUnresolvedReferences(suite);

        assertEquals(0, suite.size());
    }

    private static VariableReference newHolder(DefaultTestCase test) throws Exception {
        return test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                Collections.emptyList()));
    }

    private static FieldReference valueField(DefaultTestCase test, VariableReference holder)
            throws Exception {
        return new FieldReference(test,
                new GenericField(Holder.class.getField("value"), Holder.class), holder);
    }

    /**
     * The test survives the check, and a statement-by-statement positional copy of it (what
     * the check guards) really succeeds.
     */
    private static void assertKeptAndCopyable(DefaultTestCase test) {
        assertTrue(TestParser.findUnsafelyCopyableStatements(test).isEmpty(),
                () -> String.valueOf(TestParser.findUnsafelyCopyableStatements(test)));
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(test);

        new TestSuiteGenerator().removeTestsWithUnresolvedReferences(suite);

        assertEquals(1, suite.size());
        DefaultTestCase copy = new DefaultTestCase();
        for (Statement statement : test) {
            copy.addStatement(statement.copy(copy, 0));
        }
        assertEquals(test.size(), copy.size());
    }

    /**
     * Builds {@code holder0 = new Holder(); holder0.value = int0;} in {@code test}, with
     * {@code int0} not defined by any statement of the test.
     */
    private static DefaultTestCase testAssigningValueOf(DefaultTestCase test) throws Exception {
        VariableReference holder = test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                Collections.emptyList()));
        // A statement of this test that was never added to it (or was removed from it).
        VariableReference foreign = new IntPrimitiveStatement(test, 5).getReturnValue();
        FieldReference target = new FieldReference(test,
                new GenericField(Holder.class.getField("value"), Holder.class), holder);
        test.addStatement(new AssignmentStatement(test, target, foreign));
        return test;
    }
}
