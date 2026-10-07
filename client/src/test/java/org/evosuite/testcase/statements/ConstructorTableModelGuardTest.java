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

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.table.TableModel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The allocation guard of a constructor that takes a TableModel queries the model's
 * dimensions. A refusal because the model is huge must reach the caller, while a model
 * that cannot be queried is simply let through.
 */
public class ConstructorTableModelGuardTest {

    private static void guard(TableModel model) throws Throwable {
        TestCase tc = new DefaultTestCase();
        VariableReference arg = tc.addStatement(new NullStatement(tc, TableModel.class));
        ConstructorStatement statement = new ConstructorStatement(tc,
                new GenericConstructor(JTable.class.getConstructor(TableModel.class), JTable.class),
                Collections.singletonList(arg));
        Method guard = ConstructorStatement.class.getDeclaredMethod("rejectLargeIntInputs", Object[].class);
        guard.setAccessible(true);
        try {
            guard.invoke(statement, (Object) new Object[]{model});
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    public void hugeModelIsRefused() {
        TableModel model = mock(TableModel.class);
        when(model.getRowCount()).thenReturn(Integer.MAX_VALUE);
        when(model.getColumnCount()).thenReturn(3);
        CodeUnderTestException e = assertThrows(CodeUnderTestException.class, () -> guard(model));
        assertInstanceOf(TooManyResourcesException.class, e.getCause());
    }

    @Test
    public void tooManyCellsAreRefused() {
        TableModel model = mock(TableModel.class);
        when(model.getRowCount()).thenReturn(50_000);
        when(model.getColumnCount()).thenReturn(50_000);
        assertThrows(CodeUnderTestException.class, () -> guard(model));
    }

    @Test
    public void smallModelIsAllowed() {
        TableModel model = mock(TableModel.class);
        when(model.getRowCount()).thenReturn(3);
        when(model.getColumnCount()).thenReturn(2);
        assertDoesNotThrow(() -> guard(model));
    }

    @Test
    public void modelThatCannotBeQueriedIsAllowed() {
        TableModel model = mock(TableModel.class);
        when(model.getRowCount()).thenThrow(new IllegalStateException("no dimensions"));
        assertDoesNotThrow(() -> guard(model));
    }
}
