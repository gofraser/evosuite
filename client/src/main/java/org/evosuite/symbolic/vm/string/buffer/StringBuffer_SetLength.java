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
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringMultipleExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Symbolic function implementation for StringBuffer.setLength.
 *
 * @author galeotti
 */
public final class StringBuffer_SetLength extends SymbolicFunction {

    private static final String SET_LENGTH = "setLength";

    /**
     * Constructs a StringBuffer_SetLength.
     *
     * @param env the symbolic environment
     */
    public StringBuffer_SetLength(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING_BUFFER, SET_LENGTH,
                Types.INT_TO_VOID_DESCRIPTOR);
    }

    private String preConcValue = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {
        ReferenceConstant symbStrBuffer = this.getSymbReceiver();
        StringBuffer concStrBuffer = (StringBuffer) this.getConcReceiver();

        IntegerValue newSymbLength = this.getSymbIntegerArgument(0);
        int newConcLength = this.getConcIntArgument(0);

        // retrieve symbolic value from heap
        String concValue = concStrBuffer.toString();
        StringValue symbValue = env.heap.getField(
                Types.JAVA_LANG_STRING_BUFFER,
                SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,
                symbStrBuffer, preConcValue);

        if (symbValue.containsSymbolicVariable()
                || newSymbLength.containsSymbolicVariable()) {

            StringValue newSymbValue = null;
            if (!newSymbLength.containsSymbolicVariable() && newConcLength == 0) {
                // StringBuffer contents equals to "" string
                newSymbValue = new StringConstant("");
            } else {
                // StringBuffer contents equ
                newSymbValue = new StringMultipleExpression(symbValue,
                        Operator.SUBSTRING, new IntegerConstant(0),
                        new ArrayList<>(Collections
                                .singletonList(newSymbLength)),
                        concValue);
            }

            env.heap.putField(Types.JAVA_LANG_STRING_BUFFER,
                    SymbolicHeap.$STRING_BUFFER_CONTENTS, concStrBuffer,
                    symbStrBuffer, newSymbValue);

        }

        // return void
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntegerConstraint beforeExecuteFunction() {
        StringBuffer concStrBuffer = (StringBuffer) this.getConcReceiver();
        if (concStrBuffer != null) {
            preConcValue = concStrBuffer.toString();
        } else {
            preConcValue = null;
        }
        return null;
    }
}
