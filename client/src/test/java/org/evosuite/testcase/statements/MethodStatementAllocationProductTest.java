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
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guards multiply the int arguments of a call on an allocation-sensitive class. With four
 * or more arguments the product overflows a long, and 65536 to the fourth power wraps to
 * zero, so the call used to pass although it asks for 2^64 slots.
 */
public class MethodStatementAllocationProductTest {

    private static final PrintStream OUT = new PrintStream(new java.io.ByteArrayOutputStream());

    /** A collection, hence allocation-sensitive, with a call that takes four dimensions. */
    public static class Grid extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;

        public void resize(int a, int b, int c, int d) {
        }
    }

    private final DefaultTestCase tc = new DefaultTestCase();
    private final Scope scope = new Scope();

    private VariableReference intVar(int value) throws Exception {
        IntPrimitiveStatement st = new IntPrimitiveStatement(tc, value);
        VariableReference ref = tc.addStatement(st);
        assertNull(st.execute(scope, OUT));
        return ref;
    }

    private Throwable resize(int a, int b, int c, int d) throws Exception {
        ConstructorStatement create = new ConstructorStatement(tc,
                new GenericConstructor(Grid.class.getConstructor(), Grid.class),
                Collections.emptyList());
        VariableReference grid = tc.addStatement(create);
        assertNull(create.execute(scope, OUT));
        MethodStatement call = new MethodStatement(tc,
                new GenericMethod(Grid.class.getMethod("resize", int.class, int.class, int.class, int.class),
                        Grid.class),
                grid, Arrays.asList(intVar(a), intVar(b), intVar(c), intVar(d)));
        tc.addStatement(call);
        return call.execute(scope, OUT);
    }

    @Test
    public void productThatWrapsToZeroIsRefused() throws Exception {
        Throwable t = resize(65536, 65536, 65536, 65536);

        assertTrue(t instanceof CodeUnderTestException
                        && t.getCause() instanceof TooManyResourcesException,
                "expected the allocation to be refused but got " + t);
    }

    @Test
    public void smallProductStillRuns() throws Exception {
        assertNull(resize(2, 3, 4, 5));
    }
}
