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
package org.evosuite.instrumentation.error;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestIntOverflow {

    private int x;
    private int y;

    // Creates the test data
    public static Collection<Object[]> data() {
        Object[] values = new Object[]{Integer.MIN_VALUE, Integer.MIN_VALUE / 2, 0, Integer.MAX_VALUE / 2, Integer.MAX_VALUE};
        List<Object[]> valuePairs = new ArrayList<>();
        for (Object val1 : values) {
            for (Object val2 : values) {
                valuePairs.add(new Object[]{val1, val2});
            }
        }
        return valuePairs;
    }

    public void initTestIntOverflow(int x, int y) {
        this.x = x;
        this.y = y;
    }


    private void assertOverflow(long longResult, int intResult) {
        if (longResult > Integer.MAX_VALUE) {
            assertTrue(intResult <= 0, "Expected negative value for " + x + " and " + y + ": " + intResult);
        } else {
            assertTrue(intResult > 0, "Expected positive value for " + x + " and " + y + ": " + intResult);
        }
    }


    @MethodSource("data")
    @ParameterizedTest
    public void testAddOverflow(int x, int y) {
        initTestIntOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.IADD);
        assertOverflow((long) x + (long) y, result);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testSubOverflow(int x, int y) {
        initTestIntOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.ISUB);
        assertOverflow((long) x - (long) y, result);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testMulOverflow(int x, int y) {
        initTestIntOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.IMUL);
        assertOverflow((long) x * (long) y, result);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testDivOverflow(int x, int y) {
        initTestIntOverflow(x, y);
        Assumptions.assumeTrue(y != 0);

        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.IDIV);
        assertOverflow((long) x / (long) y, result);
    }
}
