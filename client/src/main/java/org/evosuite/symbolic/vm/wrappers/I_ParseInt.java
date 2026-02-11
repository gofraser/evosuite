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
package org.evosuite.symbolic.vm.wrappers;

import org.evosuite.symbolic.expr.Comparator;
import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.StringToIntegerCast;
import org.evosuite.symbolic.expr.bv.StringUnaryToIntegerExpression;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function for Integer.parseInt.
 *
 * @author galeotti
 */
public final class I_ParseInt extends SymbolicFunction {

    private static final String PARSE_INT = "parseInt";

    public I_ParseInt(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_INTEGER, PARSE_INT,
                Types.STR_TO_INT_DESCRIPTOR);
    }

    @Override
    public Object executeFunction() {

        ReferenceConstant symbStringRef = (ReferenceConstant) this
                .getSymbArgument(0);
        String concString = (String) this.getConcArgument(0);

        int concInteger = this.getConcIntRetVal();

        StringValue symbStringValue = env.heap.getField(
                org.evosuite.symbolic.vm.regex.Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concString, symbStringRef,
                concString);

        long longValue = concInteger;

        StringToIntegerCast parseIntValue = new StringToIntegerCast(
                symbStringValue, longValue);

        return parseIntValue;
    }

    @Override
    public IntegerConstraint beforeExecuteFunction() {
        String concString = (String) this.getConcArgument(0);

        try {
            Integer.parseInt(concString);
            return null;
        } catch (NumberFormatException ex) {

            ReferenceConstant symbStringRef = (ReferenceConstant) this
                    .getSymbArgument(0);
            StringValue symbStringValue = env.heap.getField(
                    org.evosuite.symbolic.vm.regex.Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE, concString, symbStringRef,
                    concString);

            long conV = 0;
            StringUnaryToIntegerExpression isIntegerExpression = new StringUnaryToIntegerExpression(
                    symbStringValue, Operator.IS_INTEGER, conV);

            IntegerConstraint integerConstraint = new IntegerConstraint(
                    isIntegerExpression, Comparator.EQ, new IntegerConstant(0));
            return integerConstraint;
        }

    }

}
