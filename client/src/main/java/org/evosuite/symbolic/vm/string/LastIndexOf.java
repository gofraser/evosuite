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
import org.evosuite.symbolic.expr.bv.StringBinaryToIntegerExpression;
import org.evosuite.symbolic.expr.bv.StringMultipleToIntegerExpression;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Symbolic function implementation for String.lastIndexOf.
 *
 * @author galeotti
 */
public abstract class LastIndexOf extends SymbolicFunction {

    private static final String LAST_INDEX_OF = "lastIndexOf";

    /**
     * Constructs a LastIndexOf.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public LastIndexOf(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING, LAST_INDEX_OF, desc);
    }

    /**
     * Symbolic function implementation for String.lastIndexOf(int).
     */
    public static final class LastIndexOf_C extends LastIndexOf {

        /**
         * Constructs a LastIndexOf_C.
         *
         * @param env the symbolic environment
         */
        public LastIndexOf_C(SymbolicEnvironment env) {
            super(env, Types.INT_TO_INT_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            String concLeft = (String) this.getConcReceiver();
            ReferenceConstant symbLeft = this.getSymbReceiver();

            StringValue leftExpr = env.heap
                    .getField(Types.JAVA_LANG_STRING,
                            SymbolicHeap.$STRING_VALUE, concLeft, symbLeft,
                            concLeft);

            IntegerValue rightExpr = this.getSymbIntegerArgument(0);
            int res = this.getConcIntRetVal();
            if (leftExpr.containsSymbolicVariable()
                    || rightExpr.containsSymbolicVariable()) {
                StringBinaryToIntegerExpression strBExpr = new StringBinaryToIntegerExpression(
                        leftExpr, Operator.LASTINDEXOFC, rightExpr, (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.lastIndexOf(int, int).
     */
    public static final class LastIndexOf_CI extends LastIndexOf {

        /**
         * Constructs a LastIndexOf_CI.
         *
         * @param env the symbolic environment
         */
        public LastIndexOf_CI(SymbolicEnvironment env) {
            super(env, Types.INT_INT_TO_INT_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            String concLeft = (String) this.getConcReceiver();
            ReferenceConstant symbLeft = this.getSymbReceiver();

            StringValue leftExpr = env.heap
                    .getField(Types.JAVA_LANG_STRING,
                            SymbolicHeap.$STRING_VALUE, concLeft, symbLeft,
                            concLeft);

            IntegerValue rightExpr = this.getSymbIntegerArgument(0);
            IntegerValue fromIndexExpr = this.getSymbIntegerArgument(1);

            int res = this.getConcIntRetVal();
            if (leftExpr.containsSymbolicVariable()
                    || rightExpr.containsSymbolicVariable()
                    || fromIndexExpr.containsSymbolicVariable()) {
                StringMultipleToIntegerExpression strBExpr = new StringMultipleToIntegerExpression(
                        leftExpr, Operator.LASTINDEXOFCI, rightExpr,
                        new ArrayList<>(Collections
                                .singletonList(fromIndexExpr)),
                        (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.lastIndexOf(String).
     */
    public static final class LastIndexOf_S extends LastIndexOf {

        /**
         * Constructs a LastIndexOf_S.
         *
         * @param env the symbolic environment
         */
        public LastIndexOf_S(SymbolicEnvironment env) {
            super(env, Types.STR_TO_INT_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            String concLeft = (String) this.getConcReceiver();
            ReferenceConstant symbLeft = this.getSymbReceiver();

            StringValue leftExpr = env.heap
                    .getField(Types.JAVA_LANG_STRING,
                            SymbolicHeap.$STRING_VALUE, concLeft, symbLeft,
                            concLeft);

            String concRight = (String) this.getConcArgument(0);
            ReferenceConstant symbRight = (ReferenceConstant) this
                    .getSymbArgument(0);

            StringValue rightExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concRight, symbRight,
                    concRight);

            int res = this.getConcIntRetVal();
            if (leftExpr.containsSymbolicVariable()
                    || rightExpr.containsSymbolicVariable()) {
                StringBinaryToIntegerExpression strBExpr = new StringBinaryToIntegerExpression(
                        leftExpr, Operator.LASTINDEXOFS, rightExpr, (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }

    }

    /**
     * Symbolic function implementation for String.lastIndexOf(String, int).
     */
    public static final class LastIndexOf_SI extends LastIndexOf {

        /**
         * Constructs a LastIndexOf_SI.
         *
         * @param env the symbolic environment
         */
        public LastIndexOf_SI(SymbolicEnvironment env) {
            super(env, Types.STR_INT_TO_INT_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            String concLeft = (String) this.getConcReceiver();
            ReferenceConstant symbLeft = this.getSymbReceiver();

            StringValue leftExpr = env.heap
                    .getField(Types.JAVA_LANG_STRING,
                            SymbolicHeap.$STRING_VALUE, concLeft, symbLeft,
                            concLeft);

            String concRight = (String) this.getConcArgument(0);
            ReferenceExpression symbRight = this.getSymbArgument(0);
            IntegerValue fromIndexExpr = this.getSymbIntegerArgument(1);

            int res = this.getConcIntRetVal();

            if (symbRight instanceof ReferenceConstant) {
                ReferenceConstant symbNonNullRight = (ReferenceConstant) symbRight;
                StringValue rightExpr = env.heap.getField(
                        Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                        concRight, symbNonNullRight, concRight);

                if (leftExpr.containsSymbolicVariable()
                        || rightExpr.containsSymbolicVariable()
                        || fromIndexExpr.containsSymbolicVariable()) {

                    StringMultipleToIntegerExpression strBExpr = new StringMultipleToIntegerExpression(
                            leftExpr, Operator.LASTINDEXOFSI, rightExpr,
                            new ArrayList<>(Collections
                                    .singletonList(fromIndexExpr)),
                            (long) res);

                    return strBExpr;
                }
            }

            return this.getSymbIntegerRetVal();
        }

    }

}
