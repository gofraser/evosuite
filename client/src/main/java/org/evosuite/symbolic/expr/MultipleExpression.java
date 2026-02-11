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
package org.evosuite.symbolic.expr;

import java.util.ArrayList;


public interface MultipleExpression<T extends Object> {

    /**
     * Returns the operator of the expression.
     *
     * @return a {@link org.evosuite.symbolic.expr.Operator} object.
     */
    Operator getOperator();

    /**
     * Returns the left operand of the expression.
     *
     * @return a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    Expression<T> getLeftOperand();

    /**
     * Returns the right operand of the expression.
     *
     * @return a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    Expression<?> getRightOperand();

    /**
     * Returns the list of other operands.
     *
     * @return the list of other expressions.
     */
    ArrayList<Expression<?>> getOther();


}
