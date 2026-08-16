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
package com.examples.with.different.packagename;

/**
 * Fixture for the array-allocation guard. Each method performs exactly one kind of allocation so
 * that the instrumented behaviour can be checked in isolation.
 */
public class ArrayAllocationVictim {

    public static int newArray(int size) {
        byte[] data = new byte[size];
        return data.length;
    }

    public static int aNewArray(int size) {
        Object[] data = new Object[size];
        return data.length;
    }

    public static int multiNewArray(int rows, int columns) {
        int[][] data = new int[rows][columns];
        return data.length;
    }

    public static int stringBuilderCapacity(int size) {
        StringBuilder builder = new StringBuilder(size);
        return builder.capacity();
    }

    /**
     * Mirrors a library that handles its own allocation failures (e.g. jxl buffering a workbook):
     * the allocation is refused, the library's own catch block deals with it, and execution
     * continues. Returns -1 when the allocation was refused.
     */
    public static int newArrayWithOwnCatch(int size) {
        try {
            byte[] data = new byte[size];
            return data.length;
        } catch (Throwable t) {
            return -1;
        }
    }
}
