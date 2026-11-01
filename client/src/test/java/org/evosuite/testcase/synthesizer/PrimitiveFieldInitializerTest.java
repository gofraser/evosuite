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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveFieldInitializerTest {

    @Test
    void appliesExactSwitchCaseValueToIntegerField() {
        IntPrimitiveStatement statement =
                new IntPrimitiveStatement(new DefaultTestCase());

        assertTrue(PrimitiveFieldInitializer.setIntegerHint(statement, "I", 448));
        assertEquals(448, statement.getValue());
    }

    @Test
    void convertsSwitchCaseValueToNarrowPrimitiveField() {
        ShortPrimitiveStatement statement =
                new ShortPrimitiveStatement(new DefaultTestCase());

        assertTrue(PrimitiveFieldInitializer.setIntegerHint(statement, "S", 123));
        assertEquals((short) 123, statement.getValue());
    }
}
