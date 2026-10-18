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
package org.evosuite.testcase;

import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestReferenceValidatorTest {

    @Test
    void nullAndEmptyTestsHaveNoProblems() {
        assertTrue(TestReferenceValidator.findOrphanedVariableReferences(null).isEmpty());
        assertTrue(TestReferenceValidator.findUnsafelyCopyableStatements(null).isEmpty());
        DefaultTestCase empty = new DefaultTestCase();
        assertTrue(TestReferenceValidator.findOrphanedVariableReferences(empty).isEmpty());
        assertTrue(TestReferenceValidator.findUnsafelyCopyableStatements(empty).isEmpty());
    }

    @Test
    void testParserDelegatesToTheValidator() {
        DefaultTestCase empty = new DefaultTestCase();
        assertEquals(TestReferenceValidator.findOrphanedVariableReferences(empty),
                TestParser.findOrphanedVariableReferences(empty));
        assertEquals(TestReferenceValidator.findUnsafelyCopyableStatements(empty),
                TestParser.findUnsafelyCopyableStatements(empty));
    }
}
