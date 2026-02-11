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
 * Symbolic function implementation for String.replaceFirst.
 *
 * @author galeotti
 */
public final class ReplaceFirst extends SymbolicFunction {

    private static final String REPLACE_FIRST = "replaceFirst";

    /**
     * Constructs a ReplaceFirst.
     *
     * @param env the symbolic environment
     */
    public ReplaceFirst(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_STRING, REPLACE_FIRST,
                Types.STR_STR_TO_STR_DESCRIPTOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {

        // receiver
        ReferenceConstant symbReceiver = this.getSymbReceiver();
        String concReceiver = (String) this.getConcReceiver();
        // regex argument
        ReferenceExpression symbRegex = this.getSymbArgument(0);
        String concRegex = (String) this.getConcArgument(0);
        // replacement argument
        ReferenceExpression symbReplacement = this.getSymbArgument(1);
        String concReplacement = (String) this.getConcArgument(1);
        // return value
        String concRetVal = (String) this.getConcRetVal();
        ReferenceExpression symbRetVal = this.getSymbRetVal();

        StringValue stringReceiverExpr = env.heap.getField(
                Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                concReceiver, symbReceiver, concReceiver);

        if (symbRegex instanceof ReferenceConstant
                && symbReplacement instanceof ReferenceConstant) {

            ReferenceConstant nonNullSymbRegex = (ReferenceConstant) symbRegex;
            StringValue regexExpr = env.heap.getField(Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concRegex,
                    nonNullSymbRegex, concRegex);

            ReferenceConstant nonNullSymbReplacement = (ReferenceConstant) symbReplacement;
            StringValue replacementExpr = env.heap.getField(
                    Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE,
                    concReplacement, nonNullSymbReplacement,
                    concReplacement);

            if (symbRetVal instanceof ReferenceConstant) {
                ReferenceConstant nonNullSymbRetVal = (ReferenceConstant) symbRetVal;

                StringMultipleExpression symbValue = new StringMultipleExpression(
                        stringReceiverExpr, Operator.REPLACEFIRST, regexExpr,
                        new ArrayList<>(Collections
                                .singletonList(replacementExpr)),
                        concRetVal);

                env.heap.putField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, concRetVal,
                        nonNullSymbRetVal, symbValue);
            }

        }
        return symbRetVal;
    }

}
