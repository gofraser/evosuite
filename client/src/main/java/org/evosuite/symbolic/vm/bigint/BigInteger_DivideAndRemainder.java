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

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.lang.reflect.Array;
import java.math.BigInteger;

/**
 * Symbolic function for BigInteger.divideAndRemainder.
 *
 * @author galeotti
 */
public final class BigInteger_DivideAndRemainder extends SymbolicFunction {

    private static final String DIVIDE_AND_REMAINDER = "divideAndRemainder";
    private static final int REMAINDER_ARRAY_INDEX = 1;
    private static final int QUOTIENT_ARRAY_INDEX = 0;

    public BigInteger_DivideAndRemainder(SymbolicEnvironment env) {
        super(env, Types.JAVA_MATH_BIG_INTEGER, DIVIDE_AND_REMAINDER,
                Types.BIG_INTEGER_TO_BIG_INTEGER_ARRAY);
    }

    @Override
    public Object executeFunction() {
        BigInteger concLeftBigInteger = (BigInteger) this.getConcReceiver();
        ReferenceConstant symbLeftBigInteger = this.getSymbReceiver();

        BigInteger concRightBigInteger = (BigInteger) this
                .getConcArgument(0);
        ReferenceConstant symbRightBigInteger = (ReferenceConstant) this
                .getSymbArgument(0);

        Object res = this.getConcRetVal();
        ReferenceExpression symbRes = this.getSymbRetVal();

        if (res != null && concLeftBigInteger != null
                && concRightBigInteger != null) {

            IntegerValue leftBigIntegerExpr = this.env.heap.getField(
                    Types.JAVA_MATH_BIG_INTEGER,
                    SymbolicHeap.$BIG_INTEGER_CONTENTS, concLeftBigInteger,
                    symbLeftBigInteger, concLeftBigInteger.longValue());

            IntegerValue rightBigIntegerExpr = this.env.heap.getField(
                    Types.JAVA_MATH_BIG_INTEGER,
                    SymbolicHeap.$BIG_INTEGER_CONTENTS, concRightBigInteger,
                    symbRightBigInteger, concRightBigInteger.longValue());

            if (leftBigIntegerExpr.containsSymbolicVariable()
                    || rightBigIntegerExpr.containsSymbolicVariable()) {

                // quotient
                BigInteger concQuotient = (BigInteger) Array.get(res,
                        QUOTIENT_ARRAY_INDEX);

                ReferenceConstant symbQuotient = (ReferenceConstant) this.env.heap
                        .getReference(concQuotient);

                IntegerValue symbDivValue = ExpressionFactory.div(
                        leftBigIntegerExpr, rightBigIntegerExpr,
                        concQuotient.longValue());

                this.env.heap.putField(Types.JAVA_MATH_BIG_INTEGER,
                        SymbolicHeap.$BIG_INTEGER_CONTENTS, concQuotient,
                        symbQuotient, symbDivValue);

                // remainder
                BigInteger concRemainder = (BigInteger) Array.get(res,
                        REMAINDER_ARRAY_INDEX);

                ReferenceConstant symbRemainder = (ReferenceConstant) this.env.heap
                        .getReference(concRemainder);

                IntegerValue symbRemValue = ExpressionFactory.rem(
                        leftBigIntegerExpr, rightBigIntegerExpr,
                        concRemainder.longValue());

                this.env.heap.putField(Types.JAVA_MATH_BIG_INTEGER,
                        SymbolicHeap.$BIG_INTEGER_CONTENTS, concRemainder,
                        symbRemainder, symbRemValue);

            }
        }

        return symbRes;
    }

}
