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
package org.evosuite.testcase.variable.name;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import static org.evosuite.testcase.variable.name.VariableNameAssertions.assertValidIdentifierName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TypeBasedVariableNameStrategyTest {

    private final TestCase testCase = mock(TestCase.class);

    private final TypeBasedVariableNameStrategy strategy = new TypeBasedVariableNameStrategy();

    /*
     * 1. When a type ends with a digit, a underscore should be added between the variable name and id.
     */
    @Test
    void testNameEndingWithNumber() {

        class ClassUnderTest1 {}

        // 1.
        GenericClass<?> genericClass = GenericClassFactory.get(ClassUnderTest1.class);
        VariableReference variable = new VariableReferenceImpl(testCase, genericClass);
        String actualName = strategy.createNameForVariable(variable);
        assertValidIdentifierName(actualName);
        assertTrue(actualName.endsWith("ClassUnderTest1_0"), actualName);
    }

    /*
     * 1. When a ArrayReference is passed, the name should include "Array".
     * 2. When a VariableReferenceImpl is passed with a array type the name should include "Array".
     */
    @Test
    void testNameWithArrayCreation() {

        VariableReference variable;
        String actualName;

        // 1.
        variable = new ArrayReference(testCase, GenericClassFactory.get(String.class), 1);
        actualName = strategy.createNameForVariable(variable);
        assertValidIdentifierName(actualName);
        assertEquals("stringArray0", actualName);

        // 2.
        variable = new VariableReferenceImpl(testCase, GenericClassFactory.get(String[].class));
        actualName = strategy.createNameForVariable(variable);
        assertValidIdentifierName(actualName);
        assertEquals("stringArray1", actualName);
    }

}
