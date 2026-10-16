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
package org.evosuite.testcase.statements;

import javax.swing.table.DefaultTableModel;
import java.util.Collection;

/**
 * Estimates how many cells a call on a {@link DefaultTableModel} materialises.
 *
 * <p>The model keeps a {@code Vector} per row, each sized to the column count, so every
 * operation that changes either dimension allocates rows x columns slots inside the JDK, where
 * no bytecode instrumentation can budget it. The two dimensions come from different places: the
 * int argument of the call, the current state of the model, or the sizes of the vectors and
 * arrays passed in. Looking at any one of them misses the cases that matter, for example
 * {@code new DefaultTableModel(0, 887907)} followed by {@code setNumRows(1403)}, where each
 * value is harmless on its own and the product is 1.2 billion cells.</p>
 */
final class TableModelAllocation {

    /**
     * The largest value an int argument may have for a table or its model.
     *
     * <p>A dimension is not harmless just because the other one is zero: {@code new JTable(0,
     * 996806)} creates a column object, a header cell and a name for each of the million
     * columns (measured at about 400 bytes each), and the allocation happens inside the JDK,
     * where nothing can budget it. No test needs a table that wide.</p>
     */
    static final int MAX_DIMENSION = 100_000;

    private TableModelAllocation() {
    }

    /**
     * Returns the largest value one int argument may have in a call on {@code declaring}: the
     * general limit, or {@link #MAX_DIMENSION} if that is lower and the class is a table or a
     * table model.
     */
    static int dimensionLimit(Class<?> declaring, int generalLimit) {
        if (declaring != null && (javax.swing.JTable.class.isAssignableFrom(declaring)
                || DefaultTableModel.class.isAssignableFrom(declaring))) {
            return Math.min(generalLimit, MAX_DIMENSION);
        }
        return generalLimit;
    }

    /**
     * Returns the number of cells the model would hold after the call, or 0 if the call does
     * not resize it.
     *
     * @param model      the receiver
     * @param methodName the name of the invoked {@code DefaultTableModel} method
     * @param inputs     the actual arguments of the call
     */
    static long estimateCells(DefaultTableModel model, String methodName, Object[] inputs) {
        long rows;
        long cols;
        try {
            rows = Math.max(0, model.getRowCount());
            cols = Math.max(0, model.getColumnCount());
        } catch (RuntimeException e) {
            return 0;
        }
        switch (methodName) {
            case "setNumRows":
            case "setRowCount":
                rows = intArg(inputs, 0, rows);
                break;
            case "setColumnCount":
                cols = intArg(inputs, 0, cols);
                break;
            case "addColumn":
                cols = cols + 1;
                break;
            case "setColumnIdentifiers":
                if (inputs.length == 1) {
                    cols = sizeOf(inputs[0], cols);
                }
                break;
            case "setDataVector":
                if (inputs.length == 2) {
                    rows = sizeOf(inputs[0], rows);
                    cols = sizeOf(inputs[1], cols);
                }
                break;
            default:
                return 0;
        }
        return Math.max(rows, 0) * Math.max(cols, 0);
    }

    private static long intArg(Object[] inputs, int index, long fallback) {
        Integer value = index < inputs.length ? IntArguments.widened(inputs[index]) : null;
        if (value != null) {
            return value;
        }
        return fallback;
    }

    private static long sizeOf(Object o, long fallback) {
        if (o instanceof Collection) {
            return ((Collection<?>) o).size();
        }
        if (o != null && o.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(o);
        }
        return fallback;
    }
}
