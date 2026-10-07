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

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import javax.swing.table.DefaultTableModel;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code DefaultTableModel} holds a Vector per row sized to the column count, so each
 * dimension is harmless on its own and the product is what exhausts the heap. The guard must
 * look at the model's current state and not only at the int argument of the call.
 */
public class TableModelAllocationGuardTest {

    private static final PrintStream OUT = new PrintStream(new java.io.ByteArrayOutputStream());

    private final DefaultTestCase tc = new DefaultTestCase();
    private final Scope scope = new Scope();

    private VariableReference intVar(int value) throws Exception {
        IntPrimitiveStatement st = new IntPrimitiveStatement(tc, value);
        VariableReference ref = tc.addStatement(st);
        assertNull(st.execute(scope, OUT));
        return ref;
    }

    private VariableReference model(int rows, int cols) throws Exception {
        GenericConstructor c = new GenericConstructor(
                DefaultTableModel.class.getConstructor(int.class, int.class),
                DefaultTableModel.class);
        ConstructorStatement st = new ConstructorStatement(tc, c,
                Arrays.asList(intVar(rows), intVar(cols)));
        VariableReference ref = tc.addStatement(st);
        assertNull(st.execute(scope, OUT));
        return ref;
    }

    private Throwable call(VariableReference callee, String name, Class<?>[] types,
                           VariableReference... args) throws Exception {
        GenericMethod m = new GenericMethod(DefaultTableModel.class.getMethod(name, types),
                DefaultTableModel.class);
        MethodStatement st = new MethodStatement(tc, m, callee, Arrays.asList(args));
        tc.addStatement(st);
        return st.execute(scope, OUT);
    }

    private static void assertRefused(Throwable t) {
        assertTrue(t instanceof TooManyResourcesException
                        || t instanceof CodeUnderTestException
                        && t.getCause() instanceof TooManyResourcesException,
                "expected the allocation to be refused but got " + t);
    }

    @Test
    public void setNumRowsOnWideModelIsRefused() throws Exception {
        // 0 x 5000 is cheap; 1000 rows of 5000 columns is five million cells
        VariableReference m = model(0, 5000);
        assertRefused(call(m, "setNumRows", new Class<?>[]{int.class}, intVar(1000)));
        assertEquals(0, ((DefaultTableModel) scope.getObject(m)).getRowCount());
    }

    @Test
    public void setNumRowsWithinBudgetStillRuns() throws Exception {
        VariableReference m = model(0, 50);
        assertNull(call(m, "setNumRows", new Class<?>[]{int.class}, intVar(100)));
        assertEquals(100, ((DefaultTableModel) scope.getObject(m)).getRowCount());
    }

    @Test
    public void estimateCoversTheOtherResizingCalls() {
        DefaultTableModel wide = new DefaultTableModel(0, 5000);
        DefaultTableModel tall = new DefaultTableModel(5000, 0);
        assertEquals(5_000_000L,
                TableModelAllocation.estimateCells(wide, "setNumRows", new Object[]{1000}));
        assertEquals(5_000_000L,
                TableModelAllocation.estimateCells(wide, "setRowCount", new Object[]{1000}));
        assertEquals(5_000_000L,
                TableModelAllocation.estimateCells(tall, "setColumnCount", new Object[]{1000}));
        assertEquals(5_000_000L, TableModelAllocation.estimateCells(wide, "setDataVector",
                new Object[]{new Vector<>(Collections.nCopies(1000, new Vector<>())),
                        new Vector<>(Collections.nCopies(5000, "c"))}));
        assertEquals(5_000_000L, TableModelAllocation.estimateCells(tall, "setColumnIdentifiers",
                new Object[]{new Object[1000]}));
        assertEquals(0L, TableModelAllocation.estimateCells(wide, "getRowCount", new Object[0]));
    }

    @Test
    public void addColumnAfterTheColumnVectorWasGrownThroughAnAliasIsRefused() {
        // The constructor keeps a reference to the column vector, so growing it afterwards
        // widens the model without any call on the model itself
        Vector<String> names = new Vector<>();
        DefaultTableModel model = new DefaultTableModel(names, 5000);
        names.setSize(5000);
        assertEquals(25_005_000L,
                TableModelAllocation.estimateCells(model, "addColumn", new Object[]{"c", null}));
    }
}
