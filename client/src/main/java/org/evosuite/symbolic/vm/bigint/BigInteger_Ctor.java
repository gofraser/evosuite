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
package org.evosuite.symbolic.vm.bigint;

import org.evosuite.symbolic.expr.bv.StringToIntegerCast;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.math.BigInteger;

/**
 * Symbolic function for BigInteger constructor.
 *
 * @author galeotti
 */
public final class BigInteger_Ctor extends SymbolicFunction {

    public BigInteger_Ctor(SymbolicEnvironment env) {
        super(env, Types.JAVA_MATH_BIG_INTEGER, Types.INIT, Types.STRING_TO_VOID);
    }


    @Override
    public Object executeFunction() {
        String concString = (String) this.getConcArgument(0);
        ReferenceConstant strRef = (ReferenceConstant) this.getSymbArgument(0);

        StringValue symbString = this.env.heap.getField(
                Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                concString, strRef, concString);

        if (symbString.containsSymbolicVariable()) {

            ReferenceConstant symbBigInteger = (ReferenceConstant) env
                    .topFrame().operandStack.peekRef();

            BigInteger bigInteger = new BigInteger(concString);
            long concVal = bigInteger.longValue();

            StringToIntegerCast bigIntegerValue = new StringToIntegerCast(
                    symbString, concVal);

            env.heap.putField(Types.JAVA_MATH_BIG_INTEGER,
                    SymbolicHeap.$BIG_INTEGER_CONTENTS,
                    null /* concBigInteger */, symbBigInteger,
                    bigIntegerValue);
        }

        // return void
        return null;
    }
}
