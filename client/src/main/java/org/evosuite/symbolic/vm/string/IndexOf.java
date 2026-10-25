/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Symbolic function implementation for String.indexOf and
 * String.lastIndexOf, which differ only in the method name and operators.
 *
 * @author galeotti
 */
public abstract class IndexOf extends SymbolicFunction {

    private static final String INDEX_OF = "indexOf";

    private static final String LAST_INDEX_OF = "lastIndexOf";

    /**
     * Constructs an IndexOf.
     *
     * @param env  the symbolic environment
     * @param name the method name
     * @param desc the method descriptor
     */
    private IndexOf(SymbolicEnvironment env, String name, String desc) {
        super(env, Types.JAVA_LANG_STRING, name, desc);
    }

    /**
     * Returns the symbolic functions for the indexOf methods of String.
     *
     * @param env the symbolic environment
     * @return the functions for int, String, (int, int) and (String, int)
     * arguments
     */
    public static List<SymbolicFunction> indexOfFunctions(
            SymbolicEnvironment env) {
        return functions(env, INDEX_OF, Operator.INDEXOFC,
                Operator.INDEXOFCI, Operator.INDEXOFS, Operator.INDEXOFSI);
    }

    /**
     * Returns the symbolic functions for the lastIndexOf methods of String.
     *
     * @param env the symbolic environment
     * @return the functions for int, String, (int, int) and (String, int)
     * arguments
     */
    public static List<SymbolicFunction> lastIndexOfFunctions(
            SymbolicEnvironment env) {
        return functions(env, LAST_INDEX_OF, Operator.LASTINDEXOFC,
                Operator.LASTINDEXOFCI, Operator.LASTINDEXOFS,
                Operator.LASTINDEXOFSI);
    }

    private static List<SymbolicFunction> functions(SymbolicEnvironment env,
                                                    String name, Operator opC,
                                                    Operator opCI,
                                                    Operator opS,
                                                    Operator opSI) {
        return Arrays.asList(new IndexOf_C(env, name, opC),
                new IndexOf_S(env, name, opS),
                new IndexOf_CI(env, name, opCI),
                new IndexOf_SI(env, name, opSI));
    }

    /**
     * Symbolic function implementation for String.indexOf(int).
     */
    private static final class IndexOf_C extends IndexOf {

        private final Operator op;

        /**
         * Constructs an IndexOf_C.
         *
         * @param env  the symbolic environment
         * @param name the method name
         * @param op   the operator of the resulting expression
         */
        private IndexOf_C(SymbolicEnvironment env, String name,
                          Operator op) {
            super(env, name, Types.INT_TO_INT_DESCRIPTOR);
            this.op = op;
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
                        leftExpr, op, rightExpr, (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.indexOf(int, int).
     */
    private static final class IndexOf_CI extends IndexOf {

        private final Operator op;

        /**
         * Constructs an IndexOf_CI.
         *
         * @param env  the symbolic environment
         * @param name the method name
         * @param op   the operator of the resulting expression
         */
        private IndexOf_CI(SymbolicEnvironment env, String name,
                         Operator op) {
            super(env, name, Types.INT_INT_TO_INT_DESCRIPTOR);
            this.op = op;
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
                        leftExpr, op, rightExpr,
                        new ArrayList<>(Collections
                                .singletonList(fromIndexExpr)),
                        (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.indexOf(String).
     */
    private static final class IndexOf_S extends IndexOf {

        private final Operator op;

        /**
         * Constructs an IndexOf_S.
         *
         * @param env  the symbolic environment
         * @param name the method name
         * @param op   the operator of the resulting expression
         */
        private IndexOf_S(SymbolicEnvironment env, String name,
                         Operator op) {
            super(env, name, Types.STR_TO_INT_DESCRIPTOR);
            this.op = op;
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
                        leftExpr, op, rightExpr, (long) res);

                return strBExpr;
            }

            return this.getSymbIntegerRetVal();
        }

    }

    /**
     * Symbolic function implementation for String.indexOf(String, int).
     */
    private static final class IndexOf_SI extends IndexOf {

        private final Operator op;

        /**
         * Constructs an IndexOf_SI.
         *
         * @param env  the symbolic environment
         * @param name the method name
         * @param op   the operator of the resulting expression
         */
        private IndexOf_SI(SymbolicEnvironment env, String name,
                         Operator op) {
            super(env, name, Types.STR_INT_TO_INT_DESCRIPTOR);
            this.op = op;
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
                            leftExpr, op, rightExpr,
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
