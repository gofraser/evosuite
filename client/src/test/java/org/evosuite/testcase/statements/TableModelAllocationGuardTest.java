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
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
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

    private VariableReference shortVar(short value) throws Exception {
        ShortPrimitiveStatement st = new ShortPrimitiveStatement(tc, value);
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

    /**
     * A short variable passed to an int parameter reaches reflection as a Short; the guards
     * only counted Integer arguments, so {@code new DefaultTableModel(short0, int0)} with
     * 14676 x 672421 cells ran into the heap.
     */
    @Test
    public void aShortDimensionIsCountedLikeAnInt() throws Exception {
        GenericConstructor c = new GenericConstructor(
                DefaultTableModel.class.getConstructor(int.class, int.class),
                DefaultTableModel.class);
        ConstructorStatement st = new ConstructorStatement(tc, c,
                Arrays.asList(shortVar((short) 14676), intVar(672421)));
        tc.addStatement(st);
        assertRefused(st.execute(scope, OUT));

        VariableReference m = model(0, 5000);
        assertRefused(call(m, "setNumRows", new Class<?>[]{int.class}, shortVar((short) 1000)));
        assertEquals(0, ((DefaultTableModel) scope.getObject(m)).getRowCount());
    }

    /**
     * A table with no rows still creates an object per column: {@code new JTable(0, 996806)}
     * stays under the general capacity limit of a million but is not cheap.
     */
    @Test
    public void aTableWithNoRowsAndVeryManyColumnsIsRefused() throws Exception {
        GenericConstructor c = new GenericConstructor(
                javax.swing.JTable.class.getConstructor(int.class, int.class),
                javax.swing.JTable.class);
        ConstructorStatement st = new ConstructorStatement(tc, c,
                Arrays.asList(intVar(0), intVar(TableModelAllocation.MAX_DIMENSION + 1)));
        tc.addStatement(st);
        assertRefused(st.execute(scope, OUT));

        ConstructorStatement modest = new ConstructorStatement(tc, c,
                Arrays.asList(intVar(0), intVar(50)));
        tc.addStatement(modest);
        assertNull(modest.execute(scope, OUT));
    }

    @Test
    public void aModelResizedToVeryManyColumnsIsRefused() throws Exception {
        VariableReference m = model(0, 0);
        assertRefused(call(m, "setColumnCount", new Class<?>[]{int.class},
                intVar(TableModelAllocation.MAX_DIMENSION + 1)));
        assertEquals(0, ((DefaultTableModel) scope.getObject(m)).getColumnCount());
    }

    /**
     * The factories of a raster are static and allocate bank index and sample arrays from
     * their int arguments, so they need the guard as much as a constructor does.
     */
    @Test
    public void aRasterFactoryWithAHugeImageIsRefused() throws Exception {
        GenericMethod m = new GenericMethod(java.awt.image.Raster.class.getMethod(
                "createBandedRaster", int.class, int.class, int.class, int.class,
                java.awt.Point.class), java.awt.image.Raster.class);
        VariableReference origin = tc.addStatement(
                new NullStatement(tc, java.awt.Point.class));
        assertNull(tc.getStatement(origin.getStPosition()).execute(scope, OUT));
        MethodStatement huge = new MethodStatement(tc, m, null,
                Arrays.asList(intVar(java.awt.image.DataBuffer.TYPE_BYTE), intVar(2000),
                        intVar(2000), intVar(1), origin));
        tc.addStatement(huge);
        assertRefused(huge.execute(scope, OUT));

        MethodStatement small = new MethodStatement(tc, m, null,
                Arrays.asList(intVar(java.awt.image.DataBuffer.TYPE_BYTE), intVar(20),
                        intVar(20), intVar(1), origin));
        tc.addStatement(small);
        assertNull(small.execute(scope, OUT));
    }
}
