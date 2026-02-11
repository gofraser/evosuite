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
import org.evosuite.symbolic.expr.bv.StringBinaryComparison;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for String.equalsIgnoreCase.
 *
 * @author galeotti
 */
public final class EqualsIgnoreCase extends SymbolicFunction {

    private static final String EQUALS_IGNORE_CASE = "equalsIgnoreCase";

    /**
     * Constructs an EqualsIgnoreCase.
     *
     * @param env the symbolic environment
     */
    public EqualsIgnoreCase(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING, EQUALS_IGNORE_CASE,
                Types.STR_TO_BOOL_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {

        String concLeft = (String) this.getConcReceiver();
        ReferenceConstant symbLeft = this.getSymbReceiver();

        String concRight = (String) this.getConcArgument(0);
        ReferenceExpression symbRight = this.getSymbArgument(0);

        boolean res = this.getConcBooleanRetVal();

        StringValue leftExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concLeft, symbLeft, concLeft);

        if (symbRight instanceof ReferenceConstant && concRight != null) {
            ReferenceConstant refConstantRight = (ReferenceConstant) symbRight;

            StringValue rightExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concRight,
                    refConstantRight, concRight);

            if (leftExpr.containsSymbolicVariable()
                    || rightExpr.containsSymbolicVariable()) {
                int conV = res ? 1 : 0;
                StringBinaryComparison strBExpr = new StringBinaryComparison(leftExpr,
                        Operator.EQUALSIGNORECASE, rightExpr, (long) conV);
                return strBExpr;
            }

        }

        return this.getSymbIntegerRetVal();
    }
}
