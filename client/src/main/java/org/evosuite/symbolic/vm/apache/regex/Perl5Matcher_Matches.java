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
package org.evosuite.symbolic.vm.apache.regex;

import org.apache.oro.text.regex.Pattern;
import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.StringBinaryComparison;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function implementation for Perl5Matcher.matches.
 *
 * @author galeotti
 */
public final class Perl5Matcher_Matches extends SymbolicFunction {

    private static final String MATCHES = "matches";

    /**
     * Constructs a Perl5Matcher_Matches.
     *
     * @param env the symbolic environment
     */
    public Perl5Matcher_Matches(SymbolicEnvironment env) {
        super(env, Types.ORG_APACHE_ORO_TEXT_REGEX_PERL5MATCHER, MATCHES,
                Types.STR_STR_TO_BOOLEAN);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object executeFunction() {
        // Perl5Matcher conc_matcher = (Perl5Matcher) this.getConcReceiver();
        // NonNullReference symb_matcher = (NonNullReference) this
        // .getSymbReceiver();
        boolean res = this.getConcBooleanRetVal();

        ReferenceConstant symbStringRef = (ReferenceConstant) this
                .getSymbArgument(0);
        // Reference symb_pattern_ref = this.getSymbArgument(1);

        String concString = (String) this.getConcArgument(0);
        Pattern concPattern = (Pattern) this.getConcArgument(1);

        StringValue symbStringValue = env.heap.getField(
                org.evosuite.symbolic.vm.regex.Types.JAVA_LANG_STRING,
                SymbolicHeap.$STRING_VALUE, concString, symbStringRef,
                concString);

        if (symbStringValue != null
                && symbStringValue.containsSymbolicVariable()) {
            int concreteValue = res ? 1 : 0;
            String patternStr = concPattern.getPattern();
            StringConstant symbPatternValue = ExpressionFactory
                    .buildNewStringConstant(patternStr);

            StringBinaryComparison strComp = new StringBinaryComparison(
                    symbPatternValue, Operator.APACHE_ORO_PATTERN_MATCHES,
                    symbStringValue, (long) concreteValue);

            return strComp;
        } else {
            return this.getSymbIntegerRetVal();
        }
    }
}
