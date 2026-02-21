package org.evosuite.testcase.utils;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestChromosomeUtilsTest {

    @Test
    public void testHasMethodCall_True() {
        TestChromosome chromosome = Mockito.mock(TestChromosome.class);
        TestCase testCase = Mockito.mock(TestCase.class);
        Mockito.when(chromosome.getTestCase()).thenReturn(testCase);

        MethodStatement methodStatement = Mockito.mock(MethodStatement.class);
        Properties.TARGET_CLASS = "Target";
        Mockito.when(methodStatement.getDeclaringClassName()).thenReturn("Target");

        Mockito.when(testCase.iterator()).thenReturn(Arrays.<Statement>asList(methodStatement).iterator());

        Assertions.assertTrue(TestChromosomeUtils.hasMethodCall(chromosome));
    }

    @Test
    public void testHasMethodCall_False() {
        TestChromosome chromosome = Mockito.mock(TestChromosome.class);
        TestCase testCase = Mockito.mock(TestCase.class);
        Mockito.when(chromosome.getTestCase()).thenReturn(testCase);

        PrimitiveStatement primitiveStatement = Mockito.mock(PrimitiveStatement.class);

        Mockito.when(testCase.iterator()).thenReturn(Arrays.<Statement>asList(primitiveStatement).iterator());

        Assertions.assertFalse(TestChromosomeUtils.hasMethodCall(chromosome));
    }

    @Test
    public void testRemoveUnusedVariables() {
        TestChromosome chromosome = Mockito.mock(TestChromosome.class);
        TestCase testCase = Mockito.mock(TestCase.class);
        Mockito.when(chromosome.getTestCase()).thenReturn(testCase);
        Mockito.when(chromosome.size()).thenReturn(2);

        PrimitiveStatement unused = Mockito.mock(PrimitiveStatement.class);
        VariableReference varUnused = Mockito.mock(VariableReference.class);
        Mockito.when(unused.getReturnValue()).thenReturn(varUnused);

        PrimitiveStatement used = Mockito.mock(PrimitiveStatement.class);
        VariableReference varUsed = Mockito.mock(VariableReference.class);
        Mockito.when(used.getReturnValue()).thenReturn(varUsed);

        List<Statement> statements = new ArrayList<>();
        statements.add(unused);
        statements.add(used);

        Mockito.when(testCase.iterator()).thenReturn(statements.iterator());

        // Mock hasReferences
        Mockito.when(testCase.hasReferences(varUnused)).thenReturn(false);
        Mockito.when(testCase.hasReferences(varUsed)).thenReturn(true);

        boolean changed = TestChromosomeUtils.removeUnusedVariables(chromosome);

        Assertions.assertTrue(changed);
        Mockito.verify(testCase).remove(0); // unused is at index 0
        Mockito.verify(testCase, Mockito.never()).remove(1); // used is at index 1
    }
}
