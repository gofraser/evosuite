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
import java.util.List;
import java.util.Set;

/**
 * Base class of the expressions with a left and a right operand. Subclasses
 * with further operands pass them to the constructor so that they count
 * towards the size. Two expressions are equal if they have the same class and
 * equal operands.
 *
 * @param <T> the type of the concrete value
 * @param <L> the type of the left operand
 */
public abstract class AbstractBinaryExpression<T, L> extends AbstractCompositeExpression<T> {

    private static final long serialVersionUID = -7243180561927348802L;

    private final Expression<L> left;
    private final Expression<?> right;

    protected AbstractBinaryExpression(T concreteValue, Expression<L> left,
                                       Expression<?> right) {
        super(concreteValue, 1 + left.getSize() + right.getSize(),
                left.containsSymbolicVariable() || right.containsSymbolicVariable());
        this.left = left;
        this.right = right;
    }

    protected AbstractBinaryExpression(T concreteValue, Expression<L> left,
                                       Expression<?> right, List<Expression<?>> others) {
        super(concreteValue, 1 + left.getSize() + right.getSize() + countSizes(others),
                left.containsSymbolicVariable() || right.containsSymbolicVariable()
                        || containsSymbolicVariable(others));
        this.left = left;
        this.right = right;
    }

    private static int countSizes(List<Expression<?>> list) {
        int size = 0;
        for (Expression<?> e : list) {
            size += e.getSize();
        }
        return size;
    }

    private static boolean containsSymbolicVariable(List<Expression<?>> list) {
        for (Expression<?> e : list) {
            if (e.containsSymbolicVariable()) {
                return true;
            }
        }
        return false;
    }

    public Expression<L> getLeftOperand() {
        return left;
    }

    public Expression<?> getRightOperand() {
        return right;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        AbstractBinaryExpression<?, ?> other = (AbstractBinaryExpression<?, ?>) obj;
        return left.equals(other.left) && right.equals(other.right);
    }

    @Override
    public int hashCode() {
        return left.hashCode() + right.hashCode();
    }

    @Override
    public Set<Variable<?>> getVariables() {
        Set<Variable<?>> variables = new HashSet<>();
        variables.addAll(left.getVariables());
        variables.addAll(right.getVariables());
        return variables;
    }

    @Override
    public Set<Object> getConstants() {
        Set<Object> result = new HashSet<>();
        result.addAll(left.getConstants());
        result.addAll(right.getConstants());
        return result;
    }
}
