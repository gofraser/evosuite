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

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringBinaryExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.symbolic.vm.string.Types;

/**
 * Symbolic function implementation for StringBuilder.append.
 *
 * @author galeotti
 */
public abstract class StringBuilder_Append extends SymbolicFunction {

    private static final String APPEND = "append";

    protected static final String NULL_STRING = "null";

    protected String concStrBuilderToStringPre;

    /**
     * Appends an expression to the string builder.
     *
     * @param leftExpr the left expression
     * @param res      the result string builder
     * @return the new string value
     */
    protected abstract StringValue appendExpression(StringValue leftExpr,
                                                    StringBuilder res);

    /**
     * {@inheritDoc}
     */
    @Override
    public final Object executeFunction() {
        // string builder
        StringBuilder concStrBuilder = (StringBuilder) this.getConcReceiver();
        ReferenceConstant symbStrBuilder = this
                .getSymbReceiver();

        // return value
        StringBuilder res = (StringBuilder) this.getConcRetVal();
        ReferenceConstant symbRes = (ReferenceConstant) this.getSymbRetVal();

        StringValue leftExpr = this.env.heap.getField(
                Types.JAVA_LANG_STRING_BUILDER,
                SymbolicHeap.$STRING_BUILDER_CONTENTS, concStrBuilder,
                symbStrBuilder, concStrBuilderToStringPre);

        // append string expression
        StringValue newStrExpr = appendExpression(leftExpr, res);

        // store to symbolic heap
        env.heap.putField(Types.JAVA_LANG_STRING_BUILDER,
                SymbolicHeap.$STRING_BUILDER_CONTENTS, concStrBuilder,
                symbRes, newStrExpr);

        return symbRes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final IntegerConstraint beforeExecuteFunction() {

        StringBuilder concStrBuilder = (StringBuilder) this.getConcReceiver();
        if (concStrBuilder != null) {
            concStrBuilderToStringPre = concStrBuilder.toString();
        } else {
            concStrBuilderToStringPre = null;
        }
        return null;
    }

    /**
     * Constructs a StringBuilder_Append.
     *
     * @param env  the symbolic environment
     * @param desc the method descriptor
     */
    public StringBuilder_Append(SymbolicEnvironment env, String desc) {
        super(env, Types.JAVA_LANG_STRING_BUILDER, APPEND, desc);
    }

    /**
     * Symbolic function implementation for StringBuilder.append(char).
     */
    public static final class Append_C extends StringBuilder_Append {

        /**
         * Constructs an Append_C.
         *
         * @param env the symbolic environment
         */
        public Append_C(SymbolicEnvironment env) {
            super(env, Types.CHAR_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            IntegerValue symbChar = this.getSymbIntegerArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_CHAR, symbChar, res.toString());

            return newStrExpr;
        }

    }

    /**
     * Symbolic function implementation for StringBuilder.append(String).
     */
    public static final class Append_S extends StringBuilder_Append {

        /**
         * Constructs an Append_S.
         *
         * @param env the symbolic environment
         */
        public Append_S(SymbolicEnvironment env) {
            super(env, Types.STR_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            String concStr = (String) this.getConcArgument(0);
            ReferenceExpression symbStr = this.getSymbArgument(0);

            StringValue rightExpr;
            if (concStr == null) {
                rightExpr = ExpressionFactory
                        .buildNewStringConstant(NULL_STRING);
            } else {
                ReferenceConstant symbString = (ReferenceConstant) symbStr;
                rightExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concStr, symbString,
                        concStr);

            }

            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_STRING, rightExpr, res.toString());

            return newStrExpr;
        }
    }

    /**
     * Symbolic function implementation for StringBuilder.append(int).
     */
    public static final class Append_I extends StringBuilder_Append {

        /**
         * Constructs an Append_I.
         *
         * @param env the symbolic environment
         */
        public Append_I(SymbolicEnvironment env) {
            super(env, Types.INT_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            IntegerValue symbInteger = this.getSymbIntegerArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_INTEGER, symbInteger, res.toString());

            return newStrExpr;
        }

    }

    /**
     * Symbolic function implementation for StringBuilder.append(long).
     */
    public static final class Append_L extends StringBuilder_Append {

        /**
         * Constructs an Append_L.
         *
         * @param env the symbolic environment
         */
        public Append_L(SymbolicEnvironment env) {
            super(env, Types.LONG_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            IntegerValue symbLong = this.getSymbIntegerArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_INTEGER, symbLong, res.toString());

            return newStrExpr;
        }

    }

    /**
     * Symbolic function implementation for StringBuilder.append(boolean).
     */
    public static final class Append_B extends StringBuilder_Append {

        /**
         * Constructs an Append_B.
         *
         * @param env the symbolic environment
         */
        public Append_B(SymbolicEnvironment env) {
            super(env, Types.BOOLEAN_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {
            IntegerValue symbBoolean = this.getSymbIntegerArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_BOOLEAN, symbBoolean, res.toString());

            return newStrExpr;
        }
    }

    /**
     * Symbolic function implementation for StringBuilder.append(float).
     */
    public static final class Append_F extends StringBuilder_Append {

        /**
         * Constructs an Append_F.
         *
         * @param env the symbolic environment
         */
        public Append_F(SymbolicEnvironment env) {
            super(env, Types.FLOAT_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            RealValue symbFloat = this.getSymbRealArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_REAL, symbFloat, res.toString());

            return newStrExpr;
        }
    }

    /**
     * Symbolic function implementation for StringBuilder.append(double).
     */
    public static final class Append_D extends StringBuilder_Append {

        /**
         * Constructs an Append_D.
         *
         * @param env the symbolic environment
         */
        public Append_D(SymbolicEnvironment env) {
            super(env, Types.DOUBLE_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            RealValue symbDouble = this.getSymbRealArgument(0);

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_REAL, symbDouble, res.toString());

            return newStrExpr;
        }
    }

    /**
     * Symbolic function implementation for StringBuilder.append(Object).
     */
    public static final class Append_O extends StringBuilder_Append {

        /**
         * Constructs an Append_O.
         *
         * @param env the symbolic environment
         */
        public Append_O(SymbolicEnvironment env) {
            super(env, Types.OBJECT_TO_STRBUILDER_DESCRIPTOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               StringBuilder res) {

            Object concObject = this.getConcArgument(0);

            StringValue rightExpr;
            if (concObject != null && concObject instanceof StringBuilder) {
                /* TODO: What if value instance of StringBuilder */
                throw new UnsupportedOperationException("Implement Me!");
            } else {
                String valueOf = String.valueOf(concObject);
                if (valueOf == null) {
                    valueOf = NULL_STRING;
                }
                rightExpr = ExpressionFactory.buildNewStringConstant(valueOf);
            }

            // append string expression
            StringValue newStrExpr = new StringBinaryExpression(leftExpr,
                    Operator.APPEND_STRING, rightExpr, res.toString());

            return newStrExpr;
        }
    }

}
