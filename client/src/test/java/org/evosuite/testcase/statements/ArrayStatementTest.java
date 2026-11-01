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

package org.evosuite.testcase.statements;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.utils.generic.GenericArrayTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

public class ArrayStatementTest {

    private interface PropertyX<T> {
    }

    @Test
    public void determineDimensionsIgnoresArraysInsideGenericArguments() {
        // Property<Property<Property<String[]>[]>[]>[]  -- only ONE real array dimension
        // (the outermost []); the inner X[] occurrences are just generic value types.
        Type propOfStringArray = TypeUtils.parameterize(PropertyX.class, String[].class);
        Type arr1 = GenericArrayTypeImpl.createArrayType(propOfStringArray);
        Type propOfArr1 = TypeUtils.parameterize(PropertyX.class, arr1);
        Type arr2 = GenericArrayTypeImpl.createArrayType(propOfArr1);
        Type propOfArr2 = TypeUtils.parameterize(PropertyX.class, arr2);
        Type arr3 = GenericArrayTypeImpl.createArrayType(propOfArr2);

        Assertions.assertEquals(1, ArrayStatement.determineDimensions(arr3));
        Assertions.assertEquals(0, ArrayStatement.determineDimensions(propOfArr2));
    }

    @Test
    public void determineDimensionsCountsPlainMultiDimArrays() {
        Assertions.assertEquals(0, ArrayStatement.determineDimensions(String.class));
        Assertions.assertEquals(1, ArrayStatement.determineDimensions(String[].class));
        Assertions.assertEquals(3, ArrayStatement.determineDimensions(String[][][].class));
    }

    @Test
    public void createRandomCapsTotalElementsForManyDimensions() throws Exception {
        Method createRandom = ArrayStatement.class.getDeclaredMethod("createRandom", int.class);
        createRandom.setAccessible(true);

        for (int dimensions : new int[]{7, 10, 15, 25}) {
            for (int trial = 0; trial < 50; trial++) {
                int[] lengths = (int[]) createRandom.invoke(null, dimensions);
                long product = 1L;
                for (int length : lengths) {
                    product *= Math.max(1, length);
                }
                Assertions.assertTrue(product <= Properties.MAX_ARRAY_ELEMENTS,
                        "dimensions=" + dimensions + " produced product=" + product);
            }
        }
    }

    @Test
    public void createRandomLeavesFewDimensionsUnaffectedByTheCap() throws Exception {
        Method createRandom = ArrayStatement.class.getDeclaredMethod("createRandom", int.class);
        createRandom.setAccessible(true);

        for (int trial = 0; trial < 50; trial++) {
            int[] lengths = (int[]) createRandom.invoke(null, 2);
            for (int length : lengths) {
                Assertions.assertTrue(length < Properties.MAX_ARRAY);
            }
        }
    }
}
