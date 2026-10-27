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

import java.util.HashSet;
import java.util.Set;

/**
 * Base class of the expressions with a single operand, such as casts. Two
 * expressions are equal if they have the same class and equal operands.
 *
 * @param <T> the type of the concrete value
 * @param <A> the type of the operand
 */
public abstract class AbstractUnaryExpression<T, A> extends AbstractCompositeExpression<T> {

    private static final long serialVersionUID = -2619471938540176325L;

    private final Expression<A> operand;

    protected AbstractUnaryExpression(T concreteValue, Expression<A> operand) {
        super(concreteValue, 1 + operand.getSize(), operand.containsSymbolicVariable());
        this.operand = operand;
    }

    public Expression<A> getOperand() {
        return operand;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        return operand.equals(((AbstractUnaryExpression<?, ?>) obj).operand);
    }

    @Override
    public int hashCode() {
        return operand.hashCode();
    }

    @Override
    public Set<Variable<?>> getVariables() {
        return new HashSet<>(operand.getVariables());
    }

    @Override
    public Set<Object> getConstants() {
        return operand.getConstants();
    }
}
