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

    private TableModelAllocation() {
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
        if (index < inputs.length && inputs[index] instanceof Integer) {
            return (Integer) inputs[index];
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
