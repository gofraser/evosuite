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
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.symbolic.vm.string.Types;

/**
 * Symbolic function implementation for StringBuilder.toString.
 *
 * @author galeotti
 */
public final class StringBuilder_ToString extends SymbolicFunction {

    private static final String TO_STRING = "toString";

    /**
     * Constructs a StringBuilder_ToString.
     *
     * @param env the symbolic environment
     */
    public StringBuilder_ToString(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING_BUILDER, TO_STRING,
                Types.TO_STR_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {
        ReferenceConstant symbStrBuilder = this
                .getSymbReceiver();

        // receiver
        StringBuilder concStrBuilder = (StringBuilder) this.getConcReceiver();

        // return value
        String res = (String) this.getConcRetVal();

        if (res != null) {
            ReferenceConstant symbRetVal = (ReferenceConstant) this
                    .getSymbRetVal();

            StringValue symbValue = env.heap.getField(
                    Types.JAVA_LANG_STRING_BUILDER,
                    SymbolicHeap.$STRING_BUILDER_CONTENTS, concStrBuilder,
                    symbStrBuilder, concStrBuilder.toString());

            String concReceiver = res;
            env.heap.putField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbRetVal,
                    symbValue);
        }

        return this.getSymbRetVal();
    }
}
