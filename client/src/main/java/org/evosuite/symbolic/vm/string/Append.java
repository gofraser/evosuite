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
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringBinaryExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.Arrays;
import java.util.List;

import static org.evosuite.symbolic.vm.string.buffer.Types.C_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.D_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.F_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.I_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.JAVA_LANG_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.L_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.STR_TO_STRING_BUFFER;
import static org.evosuite.symbolic.vm.string.buffer.Types.Z_TO_STRING_BUFFER;

/**
 * Symbolic function implementation for StringBuilder.append and
 * StringBuffer.append. The two classes differ only in the owner type, the heap
 * field holding the contents and the method descriptors, so the heap
 * get/append/put sequence is shared.
 *
 * @author galeotti
 */
public abstract class Append extends SymbolicFunction {

    private static final String APPEND = "append";

    private static final String NULL_STRING = "null";

    private final String contentsField;

    private final boolean resultIsReceiver;

    private String concReceiverToStringPre;

    /**
     * Constructs an Append.
     *
     * @param env              the symbolic environment
     * @param owner            the owner type, StringBuilder or StringBuffer
     * @param contentsField    the heap field holding the contents
     * @param desc             the method descriptor
     * @param resultIsReceiver whether the heap is updated through (and the
     *                         function returns) the symbolic receiver instead
     *                         of the symbolic return value
     */
    private Append(SymbolicEnvironment env, String owner,
                   String contentsField, String desc,
                   boolean resultIsReceiver) {
        super(env, owner, APPEND, desc);
        this.contentsField = contentsField;
        this.resultIsReceiver = resultIsReceiver;
    }

    /**
     * Appends an expression to the contents.
     *
     * @param leftExpr the contents before the call
     * @param res      the concrete contents after the call
     * @return the new string value
     */
    protected abstract StringValue appendExpression(StringValue leftExpr,
                                                    String res);

    /**
     * {@inheritDoc}
     */
    @Override
    public final Object executeFunction() {
        Object concReceiver = this.getConcReceiver();
        ReferenceConstant symbReceiver = this.getSymbReceiver();

        ReferenceConstant symbRes;
        Object concRes;
        if (resultIsReceiver) {
            symbRes = symbReceiver;
            concRes = concReceiver;
        } else {
            symbRes = (ReferenceConstant) this.getSymbRetVal();
            concRes = this.getConcRetVal();
        }

        StringValue leftExpr = this.env.heap.getField(getOwner(),
                contentsField, concReceiver, symbReceiver,
                concReceiverToStringPre);

        StringValue newStrExpr = appendExpression(leftExpr,
                concRes.toString());

        env.heap.putField(getOwner(), contentsField, concReceiver, symbRes,
                newStrExpr);

        return symbRes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final IntegerConstraint beforeExecuteFunction() {
        Object concReceiver = this.getConcReceiver();
        if (concReceiver != null) {
            concReceiverToStringPre = concReceiver.toString();
        } else {
            concReceiverToStringPre = null;
        }
        return null;
    }

    /**
     * Symbolic function implementation for append(boolean), append(char),
     * append(int) and append(long).
     */
    private static final class AppendInteger extends Append {

        private final Operator op;

        private AppendInteger(SymbolicEnvironment env, String owner,
                              String contentsField, String desc,
                              boolean resultIsReceiver, Operator op) {
            super(env, owner, contentsField, desc, resultIsReceiver);
            this.op = op;
        }

        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               String res) {
            IntegerValue symbInteger = this.getSymbIntegerArgument(0);
            return new StringBinaryExpression(leftExpr, op, symbInteger, res);
        }
    }

    /**
     * Symbolic function implementation for append(float) and append(double).
     */
    private static final class AppendReal extends Append {

        private AppendReal(SymbolicEnvironment env, String owner,
                           String contentsField, String desc,
                           boolean resultIsReceiver) {
            super(env, owner, contentsField, desc, resultIsReceiver);
        }

        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               String res) {
            RealValue symbReal = this.getSymbRealArgument(0);
            return new StringBinaryExpression(leftExpr, Operator.APPEND_REAL,
                    symbReal, res);
        }
    }

    /**
     * Symbolic function implementation for append(String).
     */
    private static final class AppendString extends Append {

        private AppendString(SymbolicEnvironment env, String owner,
                             String contentsField, String desc,
                             boolean resultIsReceiver) {
            super(env, owner, contentsField, desc, resultIsReceiver);
        }

        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               String res) {
            String concStr = (String) this.getConcArgument(0);

            StringValue rightExpr;
            if (concStr == null) {
                rightExpr = ExpressionFactory
                        .buildNewStringConstant(NULL_STRING);
            } else {
                ReferenceConstant symbString = (ReferenceConstant) this
                        .getSymbArgument(0);
                rightExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concStr, symbString,
                        concStr);
            }

            return new StringBinaryExpression(leftExpr,
                    Operator.APPEND_STRING, rightExpr, res);
        }
    }

    /**
     * Symbolic function implementation for StringBuilder.append(Object).
     */
    private static final class AppendObject extends Append {

        private AppendObject(SymbolicEnvironment env) {
            super(env, Types.JAVA_LANG_STRING_BUILDER,
                    SymbolicHeap.$STRING_BUILDER_CONTENTS,
                    Types.OBJECT_TO_STRBUILDER_DESCRIPTOR, false);
        }

        @Override
        protected StringValue appendExpression(StringValue leftExpr,
                                               String res) {
            Object concObject = this.getConcArgument(0);

            if (concObject instanceof StringBuilder) {
                /* TODO: What if value instance of StringBuilder */
                throw new UnsupportedOperationException("Implement Me!");
            }

            StringValue rightExpr = ExpressionFactory
                    .buildNewStringConstant(String.valueOf(concObject));

            return new StringBinaryExpression(leftExpr,
                    Operator.APPEND_STRING, rightExpr, res);
        }
    }

    /**
     * Returns the symbolic functions for the append methods of StringBuilder.
     *
     * @param env the symbolic environment
     * @return the functions for boolean, char, double, float, int, long,
     * Object and String arguments
     */
    public static List<SymbolicFunction> stringBuilderFunctions(
            SymbolicEnvironment env) {
        final String owner = Types.JAVA_LANG_STRING_BUILDER;
        final String field = SymbolicHeap.$STRING_BUILDER_CONTENTS;
        return Arrays.asList(
                new AppendInteger(env, owner, field,
                        Types.BOOLEAN_TO_STRBUILDER_DESCRIPTOR, false,
                        Operator.APPEND_BOOLEAN),
                new AppendInteger(env, owner, field,
                        Types.CHAR_TO_STRBUILDER_DESCRIPTOR, false,
                        Operator.APPEND_CHAR),
                new AppendReal(env, owner, field,
                        Types.DOUBLE_TO_STRBUILDER_DESCRIPTOR, false),
                new AppendReal(env, owner, field,
                        Types.FLOAT_TO_STRBUILDER_DESCRIPTOR, false),
                new AppendInteger(env, owner, field,
                        Types.INT_TO_STRBUILDER_DESCRIPTOR, false,
                        Operator.APPEND_INTEGER),
                new AppendInteger(env, owner, field,
                        Types.LONG_TO_STRBUILDER_DESCRIPTOR, false,
                        Operator.APPEND_INTEGER),
                new AppendObject(env),
                new AppendString(env, owner, field,
                        Types.STR_TO_STRBUILDER_DESCRIPTOR, false));
    }

    /**
     * Returns the symbolic functions for the append methods of StringBuffer.
     *
     * @param env the symbolic environment
     * @return the functions for boolean, char, int, long, float, double and
     * String arguments
     */
    public static List<SymbolicFunction> stringBufferFunctions(
            SymbolicEnvironment env) {
        final String owner = JAVA_LANG_STRING_BUFFER;
        final String field = SymbolicHeap.$STRING_BUFFER_CONTENTS;
        return Arrays.asList(
                new AppendInteger(env, owner, field, Z_TO_STRING_BUFFER, true,
                        Operator.APPEND_BOOLEAN),
                new AppendInteger(env, owner, field, C_TO_STRING_BUFFER, true,
                        Operator.APPEND_CHAR),
                new AppendInteger(env, owner, field, I_TO_STRING_BUFFER, true,
                        Operator.APPEND_INTEGER),
                new AppendInteger(env, owner, field, L_TO_STRING_BUFFER, true,
                        Operator.APPEND_INTEGER),
                new AppendReal(env, owner, field, F_TO_STRING_BUFFER, true),
                new AppendReal(env, owner, field, D_TO_STRING_BUFFER, true),
                new AppendString(env, owner, field, STR_TO_STRING_BUFFER,
                        true));
    }
}
