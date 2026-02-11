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
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringMultipleExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Symbolic function implementation for String.replace.
 *
 * @author galeotti
 */
public abstract class Replace extends SymbolicFunction {

    private static final String REPLACE = "replace";

    /**
     * Constructs a Replace.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public Replace(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING, REPLACE, desc);
    }

    /**
     * Symbolic function implementation for String.replace(char, char).
     */
    public static final class Replace_C extends Replace {

        /**
         * Constructs a Replace_C.
         *
         * @param env the symbolic environment
         */
        public Replace_C(SymbolicEnvironment env) {
            super(env, Types.CHAR_CHAR_TO_STR_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            // string receiver
            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();

            // old char
            IntegerValue oldCharExpr = this.getSymbIntegerArgument(0);

            // new char
            IntegerValue newCharExpr = this.getSymbIntegerArgument(1);

            // return value
            ReferenceExpression symbRetVal = this.getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            StringValue stringReceiverExpr = env.heap.getField(
                    Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                    concReceiver, symbReceiver, concReceiver);

            if (symbRetVal instanceof ReferenceConstant) {

                ReferenceConstant nonNullSymbRetVal = (ReferenceConstant) symbRetVal;

                StringMultipleExpression symbValue = new StringMultipleExpression(
                        stringReceiverExpr, Operator.REPLACEC, oldCharExpr,
                        new ArrayList<>(Collections
                                .singletonList(newCharExpr)),
                        concRetVal);

                env.heap.putField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concRetVal,
                        nonNullSymbRetVal, symbValue);

            }

            return this.getSymbRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.replace(CharSequence, CharSequence).
     */
    public static final class Replace_CS extends Replace {

        /**
         * Constructs a Replace_CS.
         *
         * @param env the symbolic environment
         */
        public Replace_CS(SymbolicEnvironment env) {
            super(env, Types.CHARSEQ_CHARSEQ_TO_STR_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            // string receiver
            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();

            // old string
            ReferenceExpression symbOldStr = this.getSymbArgument(0);
            CharSequence concOldCharSequence = (CharSequence) this
                    .getConcArgument(0);

            // new string
            ReferenceExpression symbNewStr = this.getSymbArgument(1);
            CharSequence concNewCharSequence = (CharSequence) this
                    .getConcArgument(1);

            // return value
            ReferenceExpression symbRetVal = this.getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            StringValue stringReceiverExpr = env.heap.getField(
                    Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                    concReceiver, symbReceiver, concReceiver);

            if (symbOldStr instanceof ReferenceConstant
                    && symbNewStr instanceof ReferenceConstant
                    && symbRetVal instanceof ReferenceConstant) {

                ReferenceConstant nonNullSymbOldStr = (ReferenceConstant) symbOldStr;
                ReferenceConstant nonNullSymbNewStr = (ReferenceConstant) symbNewStr;
                ReferenceConstant nonNullSymbRetVal = (ReferenceConstant) symbRetVal;

                if (concOldCharSequence instanceof String
                        && concNewCharSequence instanceof String) {

                    String concOldStr = (String) concOldCharSequence;

                    StringValue oldStringExpr = env.heap.getField(
                            Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                            concOldStr, nonNullSymbOldStr, concOldStr);

                    String concNewStr = (String) concNewCharSequence;

                    StringValue newStringExpr = env.heap.getField(
                            Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                            concNewStr, nonNullSymbNewStr, concNewStr);

                    StringMultipleExpression symbValue = new StringMultipleExpression(
                            stringReceiverExpr, Operator.REPLACECS,
                            oldStringExpr, new ArrayList<>(
                            Collections.singletonList(newStringExpr)),
                            concRetVal);

                    env.heap.putField(Types.JAVA_LANG_STRING,
                            SymbolicHeap.$STRING_VALUE, concRetVal,
                            nonNullSymbRetVal, symbValue);

                }
            }

            return symbRetVal;
        }
    }

}
