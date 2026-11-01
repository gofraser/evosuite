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

package org.evosuite.testcase.synthesizer;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.ConstructionFailedException;
import org.evosuite.utils.generic.GenericField;

public class NonPrimitiveFieldInitializer extends FieldInitializer {

    /**
     * Adds a constructed instance of the field's class to the test.
     * @param testFactory the factory used to insert statements
     * @param test the test case to extend
     * @param fieldType the name of the field's class
     * @param genericField the field being assigned
     * @param insertionPosition the position at which to insert statements
     * @param fieldVar the reference to the field
     * @param allowNullValue whether a null value is acceptable
     * @return the reference to the new instance, or null if none could be constructed
     * @throws ConstructionFailedException if the instance cannot be constructed
     */
    public VariableReference assignField(TestFactory testFactory, TestCase test, String fieldType,
            GenericField genericField,
            int insertionPosition, FieldReference fieldVar, boolean allowNullValue) throws ConstructionFailedException {

        VariableReference constructorVarRef = 
                ConstructionPathSynthesizer.addConstructorForClass(testFactory, test, insertionPosition, fieldType,
                        allowNullValue);
        if (constructorVarRef == null) {
            return null;
        }

        return constructorVarRef;
    }
}
