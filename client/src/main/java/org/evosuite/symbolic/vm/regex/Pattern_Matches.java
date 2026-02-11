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
package org.evosuite.symbolic.vm.regex;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.StringBinaryComparison;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function for Pattern.matches.
 *
 * @author galeotti
 */
public final class Pattern_Matches extends SymbolicFunction {

    private static final String MATCHES = "matches";

    public Pattern_Matches(SymbolicEnvironment env) {
        super(env, Types.JAVA_UTIL_REGEX_PATTERN, MATCHES,
                Types.STR_CHARSEQ_TO_BOOLEAN);
    }

    @Override
    public Object executeFunction() {

        // argument 0
        String regexStr = (String) this.getConcArgument(0);
        ReferenceConstant regexRef = (ReferenceConstant) this.getSymbArgument(0);

        // argument 1
        CharSequence inputCharSequence = (CharSequence) this.getConcArgument(1);
        ReferenceExpression inputRef = this.getSymbArgument(1);

        // return value
        boolean res = this.getConcBooleanRetVal();

        // symbolic execution
        StringValue symbRegex = env.heap.getField(Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, regexStr, regexRef, regexStr);

        StringValue symbInput = getSymbInput(inputCharSequence, inputRef);

        if (symbInput != null && symbInput.containsSymbolicVariable()) {

            int concreteValue = res ? 1 : 0;

            StringBinaryComparison strComp = new StringBinaryComparison(symbRegex,
                    Operator.PATTERNMATCHES, symbInput, (long) concreteValue);

            return strComp;
        } else {
            return this.getSymbIntegerRetVal();
        }

    }

    private StringValue getSymbInput(CharSequence inputCharSequence,
                                     ReferenceExpression inputRef) {
        StringValue symbInput;
        if (inputRef instanceof ReferenceConstant) {
            ReferenceConstant inputStrRef = (ReferenceConstant) inputRef;
            assert inputCharSequence != null;

            if (inputCharSequence instanceof String) {

                String string = (String) inputCharSequence;
                symbInput = env.heap.getField(Types.JAVA_LANG_STRING,
                        SymbolicHeap.$STRING_VALUE, string, inputStrRef,
                        string);

            } else if (inputCharSequence instanceof StringBuilder) {

                StringBuilder stringBuffer = (StringBuilder) inputCharSequence;
                symbInput = env.heap.getField(Types.JAVA_LANG_STRING_BUILDER,
                        SymbolicHeap.$STRING_BUILDER_CONTENTS, stringBuffer,
                        inputStrRef, stringBuffer.toString());
            } else {
                symbInput = null;
            }
        } else {
            symbInput = null;
        }
        return symbInput;
    }
}
