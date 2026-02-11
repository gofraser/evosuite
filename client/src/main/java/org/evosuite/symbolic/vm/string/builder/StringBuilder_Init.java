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
package org.evosuite.symbolic.vm.string.builder;

import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.symbolic.vm.string.Types;

/**
 * Symbolic function implementation for StringBuilder constructors.
 *
 * @author galeotti
 */
public final class StringBuilder_Init extends SymbolicFunction {

    private static final String INIT = "<init>";

    /**
     * Constructs a StringBuilder_Init.
     *
     * @param env the symbolic environment
     */
    public StringBuilder_Init(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING_BUILDER, INIT,
                Types.STR_TO_VOID_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {

        // symbolic receiver (new object)
        ReferenceConstant symbStrBuilder = this
                .getSymbReceiver();

        // string argument
        String concStr = (String) this.getConcArgument(0);
        ReferenceExpression symbStr = this.getSymbArgument(0);

        if (symbStr instanceof ReferenceConstant) {
            ReferenceConstant nonNullSymbString = (ReferenceConstant) symbStr;
            assert concStr != null;

            StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concStr, nonNullSymbString,
                    concStr);

            // update symbolic heap
            env.heap.putField(Types.JAVA_LANG_STRING_BUILDER,
                    SymbolicHeap.$STRING_BUILDER_CONTENTS, null,
                    symbStrBuilder, strExpr);
        }

        // return void
        return null;
    }

}
