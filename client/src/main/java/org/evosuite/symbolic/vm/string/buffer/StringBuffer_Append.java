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
package org.evosuite.symbolic.vm.string.buffer;

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

/**

 * Symbolic function implementation for StringBuffer.append.

 *

 * @author galeotti

 */

public abstract class StringBuffer_Append extends SymbolicFunction {



    private static final String APPEND = "append";



    /**

     * Constructs a StringBuffer_Append.

     *

     * @param env  the symbolic environment

     * @param desc the method descriptor

     */

    public StringBuffer_Append(SymbolicEnvironment env, String desc) {

        super(env, Types.JAVA_LANG_STRING_BUFFER, APPEND, desc);

    }



    protected String stringValBeforeExecution;



    /**

     * {@inheritDoc}

     */

    @Override

    public IntegerConstraint beforeExecuteFunction() {

        StringBuffer concStrBuffer = (StringBuffer) this.getConcReceiver();

        if (concStrBuffer != null) {

            stringValBeforeExecution = concStrBuffer.toString();

        } else {

            stringValBeforeExecution = null;

        }

        return null;

    }



    /**

     * Symbolic function implementation for StringBuffer.append(boolean).

     */

    public static class StringBufferAppend_B extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_B.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_B(SymbolicEnvironment env) {

            super(env, Types.Z_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            IntegerValue symbBoolean = this.getSymbIntegerArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_BOOLEAN, symbBoolean, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(char).

     */

    public static class StringBufferAppend_C extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_C.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_C(SymbolicEnvironment env) {

            super(env, Types.C_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            IntegerValue symbChar = this.getSymbIntegerArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_CHAR, symbChar, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(int).

     */

    public static class StringBufferAppend_I extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_I.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_I(SymbolicEnvironment env) {

            super(env, Types.I_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            IntegerValue symbInt = this.getSymbIntegerArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_INTEGER, symbInt, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(long).

     */

    public static class StringBufferAppend_L extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_L.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_L(SymbolicEnvironment env) {

            super(env, Types.L_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            IntegerValue symbLong = this.getSymbIntegerArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_INTEGER, symbLong, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(float).

     */

    public static class StringBufferAppend_F extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_F.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_F(SymbolicEnvironment env) {

            super(env, Types.F_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            RealValue symbFloat = this.getSymbRealArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_REAL, symbFloat, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(double).

     */

    public static class StringBufferAppend_D extends StringBuffer_Append {



        /**

         * Constructs a StringBufferAppend_D.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_D(SymbolicEnvironment env) {

            super(env, Types.D_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            RealValue symbDouble = this.getSymbRealArgument(0);



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_REAL, symbDouble, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }



    /**

     * Symbolic function implementation for StringBuffer.append(String).

     */

    public static class StringBufferAppend_STR extends StringBuffer_Append {



        private static final String NULL_STRING = "null";



        /**

         * Constructs a StringBufferAppend_STR.

         *

         * @param env the symbolic environment

         */

        public StringBufferAppend_STR(SymbolicEnvironment env) {

            super(env, Types.STR_TO_STRING_BUFFER);

        }



        /**

         * {@inheritDoc}

         */

        @Override

        public Object executeFunction() {



            ReferenceConstant symbStrBuffer = this.getSymbReceiver();

            StringBuffer concStrBuffer = (StringBuffer) this

                    .getConcReceiver();



            StringValue leftExpr = this.env.heap.getField(

                    Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, stringValBeforeExecution);



            ReferenceExpression symbStr = this.getSymbArgument(0);

            String concStr = (String) this.getConcArgument(0);



            StringValue symbStrValue;

            if (concStr == null) {

                symbStrValue = ExpressionFactory

                        .buildNewStringConstant(NULL_STRING);

            } else {

                ReferenceConstant symbNonNullStr = (ReferenceConstant) symbStr;

                symbStrValue = env.heap.getField(Types.JAVA_LANG_STRING,

                        SymbolicHeap.$STRING_VALUE, concStr,

                        symbNonNullStr, concStr);

            }



            // append string expression

            String concValue = concStrBuffer.toString();

            StringValue appendExpr = new StringBinaryExpression(leftExpr,

                    Operator.APPEND_STRING, symbStrValue, concValue);



            // store to symbolic heap

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,

                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,

                    symbStrBuffer, appendExpr);



            // append returns the StringBuffer

            return symbStrBuffer;

        }

    }

}
