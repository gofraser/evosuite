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
import org.evosuite.symbolic.expr.str.StringUnaryExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for String.trim.
 *
 * @author galeotti
 */
public final class Trim extends SymbolicFunction {

    private static final String TRIM = "trim";

    /**
     * Constructs a Trim.
     *
     * @param env the symbolic environment
     */
    public Trim(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING, TRIM, Types.TO_STR_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {

        // object receiver
        ReferenceConstant symbStr = this.getSymbReceiver();
        String concStr = (String) this.getConcReceiver();

        // return value
        String concRetVal = (String) this.getConcRetVal();
        ReferenceConstant symbRetVal = (ReferenceConstant) this.getSymbRetVal();

        StringValue stringExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concStr, symbStr, concStr);
        StringUnaryExpression symbValue = new StringUnaryExpression(
                stringExpr, Operator.TRIM, concRetVal);

        env.heap.putField(Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                concRetVal, symbRetVal, symbValue);

        return this.getSymbRetVal();
    }
}
