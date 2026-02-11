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
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

import java.util.regex.Matcher;

/**
 * Symbolic function for Matcher.matches.
 *
 * @author galeotti
 */
public final class Matcher_Matches extends SymbolicFunction {

    private static final String MATCHES = "matches";

    public Matcher_Matches(SymbolicEnvironment env) {
        super(env, Types.JAVA_UTIL_REGEX_MATCHER, MATCHES, Types.TO_BOOLEAN);
    }

    @Override
    public Object executeFunction() {
        Matcher concMatcher = (Matcher) this.getConcReceiver();
        ReferenceConstant symbMatcher = this
                .getSymbReceiver();
        boolean res = this.getConcBooleanRetVal();

        String concRegex = concMatcher.pattern().pattern();
        StringValue symbInput = (StringValue) env.heap.getField(
                Types.JAVA_UTIL_REGEX_MATCHER, SymbolicHeap.$MATCHER_INPUT,
                concMatcher, symbMatcher);

        if (symbInput != null && symbInput.containsSymbolicVariable()) {
            int concreteValue = res ? 1 : 0;
            StringConstant symbRegex = ExpressionFactory
                    .buildNewStringConstant(concRegex);
            StringBinaryComparison strComp = new StringBinaryComparison(symbRegex,
                    Operator.PATTERNMATCHES, symbInput, (long) concreteValue);

            return strComp;
        } else {
            return this.getSymbIntegerRetVal();
        }
    }
}
