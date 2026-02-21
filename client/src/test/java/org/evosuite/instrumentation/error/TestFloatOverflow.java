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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestFloatOverflow {

    private float x;
    private float y;

    // Creates the test data
    public static Collection<Object[]> data() {
        Object[] values = new Object[]{(-Float.MAX_VALUE), (-Float.MAX_VALUE) / 2, 0, Float.MAX_VALUE / 2, Float.MAX_VALUE};
        List<Object[]> valuePairs = new ArrayList<>();
        for (Object val1 : values) {
            for (Object val2 : values) {
                valuePairs.add(new Object[]{val1, val2});
            }
        }
        return valuePairs;
    }

    public void initTestFloatOverflow(float x, float y) {
        this.x = x;
        this.y = y;
    }


    private void assertOverflow(double doubleResult, int distance, float floatResult) {
        if (doubleResult > Float.MAX_VALUE) {
            assertTrue(distance <= 0, "Expected negative value for " + x + " and " + y + ": " + distance);
            assertEquals(Float.POSITIVE_INFINITY, floatResult, 0.0F, "Expected result to be infinity for " + x + " and " + y + ": " + floatResult);

        } else {
            assertTrue(distance > 0, "Expected positive value for " + x + " and " + y + ": " + distance);
        }
    }


    @MethodSource("data")
    @ParameterizedTest
    public void testAddOverflow(float x, float y) {
        initTestFloatOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.FADD);
        assertOverflow((double) x + (double) y, result, x + y);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testSubOverflow(float x, float y) {
        initTestFloatOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.FSUB);
        assertOverflow((double) x - (double) y, result, x - y);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testMulOverflow(float x, float y) {
        initTestFloatOverflow(x, y);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.FMUL);
        assertOverflow((double) x * (double) y, result, x * y);
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testDivOverflow(float x, float y) {
        initTestFloatOverflow(x, y);
        Assumptions.assumeTrue(y != 0F);
        int result = ErrorConditionChecker.overflowDistance(x, y, Opcodes.FDIV);
        assertOverflow((double) x / (double) y, result, x / y);
    }
}
