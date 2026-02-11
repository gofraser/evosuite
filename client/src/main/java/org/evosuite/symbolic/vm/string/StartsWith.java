/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3.0 of
 * the License, or (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with EvoSuite. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.symbolic.vm.string;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.bv.StringBinaryComparison;
import org.evosuite.symbolic.expr.bv.StringMultipleComparison;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Symbolic function implementation for String.startsWith.
 *
 * @author galeotti
 */
public abstract class StartsWith extends SymbolicFunction {

    private static final String STARTS_WITH = "startsWith";

    /**
     * Constructs a StartsWith.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public StartsWith(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING, STARTS_WITH, desc);
    }

    /**
     * Symbolic function implementation for String.startsWith(String).
     */
    public static final class StartsWith_S extends StartsWith {

        /**
         * Constructs a StartsWith_S.
         *
         * @param env the symbolic environment
         */
        public StartsWith_S(SymbolicEnvironment env) {
            super(env, Types.STR_TO_BOOL_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            // receiver
            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();
            // prefix argument
            ReferenceExpression symbPrefix = this.getSymbArgument(0);
            String concPrefix = (String) this.getConcArgument(0);

            // return value
            boolean res = this.getConcBooleanRetVal();

            StringValue stringReceiverExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbReceiver, concReceiver);

            if (symbPrefix instanceof ReferenceConstant) {
                ReferenceConstant nonNullSymbPrefix = (ReferenceConstant) symbPrefix;

                StringValue prefixExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concPrefix, nonNullSymbPrefix, concPrefix);

                if (stringReceiverExpr.containsSymbolicVariable()
                        || prefixExpr.containsSymbolicVariable()) {
                    int conV = res ? 1 : 0;

                    StringBinaryComparison strTExpr = new StringBinaryComparison(stringReceiverExpr,
                            Operator.STARTSWITH, prefixExpr, (long) conV);

                    return strTExpr;
                }

            }
            return this.getSymbIntegerRetVal();
        }

    }

    /**
     * Symbolic function implementation for String.startsWith(String, int).
     */
    public static final class StartsWith_SI extends StartsWith {

        /**
         * Constructs a StartsWith_SI.
         *
         * @param env the symbolic environment
         */
        public StartsWith_SI(SymbolicEnvironment env) {
            super(env, Types.STR_INT_TO_BOOL_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            // receiver
            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();
            // prefix argument
            ReferenceExpression symbPrefix = this.getSymbArgument(0);
            String concPrefix = (String) this.getConcArgument(0);
            // toffset argument
            IntegerValue offsetExpr = this.getSymbIntegerArgument(1);

            // return value
            boolean res = this.getConcBooleanRetVal();

            StringValue stringReceiverExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbReceiver, concReceiver);

            if (symbPrefix instanceof ReferenceConstant) {
                ReferenceConstant nonNullSymbPrefix = (ReferenceConstant) symbPrefix;

                StringValue prefixExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concPrefix, nonNullSymbPrefix, concPrefix);

                if (stringReceiverExpr.containsSymbolicVariable() || prefixExpr.containsSymbolicVariable()
                        || offsetExpr.containsSymbolicVariable()) {
                    int conV = res ? 1 : 0;

                    StringMultipleComparison strTExpr =
                            new StringMultipleComparison(stringReceiverExpr, Operator.STARTSWITH, prefixExpr,
                                    new ArrayList<>(Collections.singletonList(offsetExpr)), (long) conV);

                    return strTExpr;
                }

            }
            return this.getSymbIntegerRetVal();
        }
    }
}
