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
package org.evosuite.symbolic.vm.string;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.StringBinaryToIntegerExpression;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for String.compareTo.
 *
 * @author galeotti
 */
public final class CompareTo extends SymbolicFunction {

    private static final String COMPARE_TO = "compareTo";

    /**
     * Constructs a CompareTo.
     *
     * @param env the symbolic environment
     */
    public CompareTo(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING, COMPARE_TO,
                Types.STR_TO_INT_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {

        String concLeft = (String) this.getConcReceiver();
        ReferenceConstant symbLeft = this.getSymbReceiver();

        StringValue leftExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concLeft, symbLeft, concLeft);

        String concRight = (String) this.getConcArgument(0);
        ReferenceConstant symbRight = (ReferenceConstant) this
                .getSymbArgument(0);

        StringValue rightExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concRight, symbRight, concRight);

        int res = this.getConcIntRetVal();

        if (leftExpr.containsSymbolicVariable()
                || rightExpr.containsSymbolicVariable()) {
            StringBinaryToIntegerExpression strBExpr = new StringBinaryToIntegerExpression(
                    leftExpr, Operator.COMPARETO, rightExpr, (long) res);

            return strBExpr;
        } else {

            return this.getSymbIntegerRetVal();
        }
    }
}
