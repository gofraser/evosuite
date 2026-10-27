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
package org.evosuite.symbolic.expr;

import java.util.List;

/**
 * Base class of the expressions that apply an {@link Operator} to a left and a
 * right operand.
 *
 * @param <T> the type of the concrete value
 * @param <L> the type of the left operand
 */
public abstract class AbstractOperatorBinaryExpression<T, L> extends AbstractBinaryExpression<T, L> {

    private static final long serialVersionUID = 1590462837016274913L;

    private final Operator op;

    protected AbstractOperatorBinaryExpression(T concreteValue, Expression<L> left,
                                               Operator op, Expression<?> right) {
        super(concreteValue, left, right);
        this.op = op;
    }

    protected AbstractOperatorBinaryExpression(T concreteValue, Expression<L> left,
                                               Operator op, Expression<?> right,
                                               List<Expression<?>> others) {
        super(concreteValue, left, right, others);
        this.op = op;
    }

    public Operator getOperator() {
        return op;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && op.equals(((AbstractOperatorBinaryExpression<?, ?>) obj).op);
    }

    @Override
    public int hashCode() {
        return super.hashCode() + op.hashCode();
    }

    @Override
    public String toString() {
        return "(" + getLeftOperand() + op + getRightOperand() + ")";
    }
}
