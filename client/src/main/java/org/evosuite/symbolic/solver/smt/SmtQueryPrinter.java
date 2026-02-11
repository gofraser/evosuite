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
package org.evosuite.symbolic.solver.smt;

import org.evosuite.symbolic.solver.SmtSort;
import org.evosuite.utils.StringUtil;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class SmtQueryPrinter {

    /**
     * Prints the query.
     *
     * @param query a {@link org.evosuite.symbolic.solver.smt.SmtQuery} object.
     * @return a {@link java.lang.String} object.
     */
    public String print(SmtQuery query) {
        StringBuffer buff = new StringBuffer();
        buff.append("\n");

        if (query.hasLogic()) {
            buff.append("(set-logic " + query.getLogic() + ")");
            buff.append("\n");
        }

        for (String optionName : query.getOptions()) {
            String optionValue = query.getOptionValue(optionName);
            buff.append(String.format("(set-option %s %s)%n", optionName, optionValue));
            buff.append("\n");
        }
        buff.append("\n");

        for (SmtConstantDeclaration constantDeclaration : query.getConstantDeclarations()) {
            String str = print(constantDeclaration);
            buff.append(str);
            buff.append("\n");
        }

        for (SmtFunctionDeclaration functionDeclaration : query.getFunctionDeclarations()) {
            String str = print(functionDeclaration);
            buff.append(str);
            buff.append("\n");
        }

        for (SmtFunctionDefinition functionDeclaration : query.getFunctionDefinitions()) {
            String str = print(functionDeclaration);
            buff.append(str);
            buff.append("\n");
        }

        for (SmtAssertion smtAssertion : query.getAssertions()) {
            String str = print(smtAssertion);
            buff.append(str);
            buff.append("\n");
        }

        buff.append("(check-sat)");
        buff.append("\n");

        buff.append("(get-model)");
        buff.append("\n");

        buff.append("(exit)");
        buff.append("\n");

        return buff.toString();

    }

    /**
     * Prints the assertion.
     *
     * @param smtAssertion a {@link org.evosuite.symbolic.solver.smt.SmtAssertion} object.
     * @return a {@link java.lang.String} object.
     */
    public String print(SmtAssertion smtAssertion) {
        SmtExprPrinter printer = new SmtExprPrinter();
        SmtExpr expr = smtAssertion.getFormula();
        String exprStr = expr.accept(printer, null);
        return String.format("(assert %s)", exprStr);
    }

    /**
     * Prints the function definition.
     *
     * @param functionDeclaration a {@link org.evosuite.symbolic.solver.smt.SmtFunctionDefinition} object.
     * @return a {@link java.lang.String} object.
     */
    public String print(SmtFunctionDefinition functionDeclaration) {
        return String.format("(define-fun %s)", functionDeclaration.getFunctionDefinition());
    }

    /**
     * Prints the function declaration.
     *
     * @param functionDeclaration a {@link org.evosuite.symbolic.solver.smt.SmtFunctionDeclaration} object.
     * @return a {@link java.lang.String} object.
     */
    public String print(SmtFunctionDeclaration functionDeclaration) {
        return String.format(
                "(declare-fun %s () %s)",
                functionDeclaration.getFunctionName(),
                buildSortsString(functionDeclaration.getFunctionSorts()));
    }

    /**
     * Prints the constant declaration.
     *
     * @param constantDeclaration a {@link org.evosuite.symbolic.solver.smt.SmtConstantDeclaration} object.
     * @return a {@link java.lang.String} object.
     */
    public String print(SmtConstantDeclaration constantDeclaration) {
        return String.format(
                "(declare-const %s %s)",
                constantDeclaration.getConstantName(),
                buildSortsString(constantDeclaration.getConstantSorts()));
    }

    /**
     * Transforms sorts into strings.
     *
     * @param sorts an array of {@link org.evosuite.symbolic.solver.SmtSort} objects.
     * @return a {@link java.lang.String} object.
     */
    private String buildSortsString(SmtSort[] sorts) {
        List<String> stringSorts = Arrays.stream(sorts).map(sort -> sort.getName()).collect(Collectors.toList());
        String str = StringUtil.joinStrings(StringUtil.SPACE_DELIMITER, stringSorts);

        if (stringSorts.size() > 1) {
            return "(" + str + ")";
        } else {
            return str;
        }
    }
}
