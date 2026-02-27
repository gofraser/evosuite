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
