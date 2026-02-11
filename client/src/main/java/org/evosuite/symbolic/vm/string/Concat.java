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
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringBinaryExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for String.concat.
 *
 * @author galeotti
 */
public final class Concat extends SymbolicFunction {

    private static final String CONCAT = "concat";

    /**
     * Constructs a Concat.
     *
     * @param env the symbolic environment
     */
    public Concat(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING, CONCAT, Types.STR_TO_STR_DESCRIPTOR);
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

        String res = (String) this.getConcRetVal();
        if (res != null) {
            StringBinaryExpression symbValue = new StringBinaryExpression(
                    leftExpr, Operator.CONCAT, rightExpr, res);

            ReferenceConstant symbReceiver = (ReferenceConstant) env.topFrame().operandStack
                    .peekRef();
            String concReceiver = res;
            env.heap.putField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbReceiver,
                    symbValue);
        }
        return this.getSymbRetVal();
    }

}
