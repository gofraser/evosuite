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

import java.util.ArrayList;
import java.util.Set;

/**
 * Base class of the operator expressions with further operands after the left
 * and the right one.
 *
 * @param <T> the type of the concrete value
 * @param <L> the type of the left operand
 */
public abstract class AbstractMultipleExpression<T, L> extends AbstractOperatorBinaryExpression<T, L> {

    private static final long serialVersionUID = -3305718294026481170L;

    private final ArrayList<Expression<?>> other;

    protected AbstractMultipleExpression(T concreteValue, Expression<L> left, Operator op,
                                         Expression<?> right,
                                         ArrayList<Expression<?>> other) {
        super(concreteValue, left, op, right, other);
        this.other = other;
    }

    public ArrayList<Expression<?>> getOther() {
        return other;
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
                && other.equals(((AbstractMultipleExpression<?, ?>) obj).other);
    }

    @Override
    public int hashCode() {
        return super.hashCode() + other.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder rest = new StringBuilder();
        for (Expression<?> expression : other) {
            rest.append(' ').append(expression);
        }
        return "(" + getLeftOperand() + getOperator()
                + (getRightOperand() == null ? "" : getRightOperand()) + rest + ")";
    }

    @Override
    public Set<Variable<?>> getVariables() {
        Set<Variable<?>> variables = super.getVariables();
        for (Expression<?> e : other) {
            variables.addAll(e.getVariables());
        }
        return variables;
    }

    @Override
    public Set<Object> getConstants() {
        Set<Object> result = super.getConstants();
        for (Expression<?> e : other) {
            result.addAll(e.getConstants());
        }
        return result;
    }
}
