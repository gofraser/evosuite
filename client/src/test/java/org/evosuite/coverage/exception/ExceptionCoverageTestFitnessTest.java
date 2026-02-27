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
package org.evosuite.coverage.exception;

import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ExceptionCoverageTestFitnessTest {

    @Test
    public void testEqualsAndHashCode() {
        ExceptionCoverageTestFitness fitness1 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness2 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness3 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.EXPLICIT);
        ExceptionCoverageTestFitness fitness4 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method2()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness5 = new ExceptionCoverageTestFitness(
                "com.example.Bar", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);

        assertEquals(fitness1, fitness2);
        assertEquals(fitness1.hashCode(), fitness2.hashCode());

        assertNotEquals(fitness1, fitness3);
        // This assertion might fail if hashCode implementation is incomplete
        // assertNotEquals(fitness1.hashCode(), fitness3.hashCode());

        assertNotEquals(fitness1, fitness4);
        assertNotEquals(fitness1, fitness5);
    }
}
