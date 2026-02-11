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
import org.evosuite.symbolic.expr.bv.StringUnaryToIntegerExpression;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringMultipleExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Symbolic function implementation for String.substring.
 *
 * @author galeotti
 */
public abstract class Substring extends SymbolicFunction {

    private static final String SUBSTRING = "substring";

    /**
     * Constructs a Substring.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public Substring(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING, SUBSTRING, desc);
    }

    /**
     * Symbolic function implementation for String.substring(int, int).
     */
    public static final class Substring_II extends Substring {

        /**
         * Constructs a Substring_II.
         *
         * @param env the symbolic environment
         */
        public Substring_II(SymbolicEnvironment env) {
            super(env, Types.INT_INT_TO_STR_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();

            IntegerValue beginIndexExpr = this.getSymbIntegerArgument(0);
            IntegerValue endIndexExpr = this.getSymbIntegerArgument(1);

            StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbReceiver,
                    concReceiver);

            ReferenceConstant symbRetVal = (ReferenceConstant) this
                    .getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            StringMultipleExpression symbValue = new StringMultipleExpression(
                    strExpr, Operator.SUBSTRING, beginIndexExpr,
                    new ArrayList<>(Collections
                            .singletonList(endIndexExpr)),
                    concRetVal);

            env.heap.putField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concRetVal, symbRetVal,
                    symbValue);

            return this.getSymbRetVal();
        }
    }

    /**
     * Symbolic function implementation for String.substring(int).
     */
    public static final class Substring_I extends Substring {

        /**
         * Constructs a Substring_I.
         *
         * @param env the symbolic environment
         */
        public Substring_I(SymbolicEnvironment env) {
            super(env, Types.INT_TO_STR_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object executeFunction() {

            ReferenceConstant symbReceiver = this.getSymbReceiver();
            String concReceiver = (String) this.getConcReceiver();

            IntegerValue beginIndexExpr = this.getSymbIntegerArgument(0);

            StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concReceiver, symbReceiver,
                    concReceiver);

            ReferenceConstant symbRetVal = (ReferenceConstant) this
                    .getSymbRetVal();
            String concRetVal = (String) this.getConcRetVal();

            IntegerValue lengthExpr = new StringUnaryToIntegerExpression(
                    strExpr, Operator.LENGTH, (long) concReceiver.length());

            StringMultipleExpression symbValue = new StringMultipleExpression(
                    strExpr, Operator.SUBSTRING, beginIndexExpr,
                    new ArrayList<>(Collections
                            .singletonList(lengthExpr)),
                    concRetVal);

            env.heap.putField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concRetVal, symbRetVal,
                    symbValue);

            return this.getSymbRetVal();
        }
    }

}
