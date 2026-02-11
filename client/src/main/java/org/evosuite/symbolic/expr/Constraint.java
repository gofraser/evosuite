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

import org.evosuite.symbolic.expr.constraint.ConstraintVisitor;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

public abstract class Constraint<T extends Object> implements Serializable {

    private static final long serialVersionUID = 7547747352755232472L;

    /**
     * Returns the comparator used in this constraint.
     *
     * @return a {@link org.evosuite.symbolic.expr.Comparator} object.
     */
    public abstract Comparator getComparator();

    /**
     * Returns the left operand of the constraint.
     *
     * @return a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    public abstract Expression<?> getLeftOperand();

    /**
     * Returns the right operand of the constraint.
     *
     * @return a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    public abstract Expression<?> getRightOperand();

    private int hash = 0;

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        if (hash == 0) {
            hash = getLeftOperand().hashCode() + getComparator().hashCode()
                    + getRightOperand().hashCode();
        }
        return hash;
    }

    protected int size = 0;

    /**
     * Returns the size of the constraint.
     *
     * @return the constraint size.
     */
    public int getSize() {
        if (size == 0) {
            size = 1 + getLeftOperand().getSize() + getRightOperand().getSize();
        }
        return size;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Constraint<?>)) {
            return false;
        }

        Constraint<?> other = (Constraint<?>) obj;
        return this.getComparator().equals(other.getComparator())
                // && this.getSize() == other.getSize()
                && this.getLeftOperand().equals(other.getLeftOperand())
                && this.getRightOperand().equals(other.getRightOperand());
    }

    /**
     * Returns whether the constraint is solvable.
     *
     * @return true if solvable, false otherwise.
     */
    public boolean isSolveable() {
        if (getLeftOperand().equals(getRightOperand())) {
            return getComparator() != Comparator.LT
                    && getComparator() != Comparator.GT
                    && getComparator() != Comparator.NE;
        }
        return true;
    }

    /**
     * Returns the negation of this constraint.
     *
     * @return the negated constraint.
     */
    public abstract Constraint<T> negate();

    /**
     * Returns a normalized value between 0 and 1.
     *
     * @param x the value to normalize
     * @return a normalized double value
     */
    protected static double normalize(double x) {
        return x / (x + 1.0);
    }

    /**
     * Returns the set of symbolic variables in the constraint.
     *
     * @return the set of variables.
     */
    public Set<Variable<?>> getVariables() {
        Set<Variable<?>> result = new HashSet<>();
        result.addAll(this.getLeftOperand().getVariables());
        result.addAll(this.getRightOperand().getVariables());
        return result;
    }

    /**
     * Returns the set of constants in the constraint.
     *
     * @return the set of constants.
     */
    public Set<Object> getConstants() {
        Set<Object> result = new HashSet<>();
        result.addAll(this.getLeftOperand().getConstants());
        result.addAll(this.getRightOperand().getConstants());
        return result;
    }

    /**
     * Accepts a constraint visitor.
     *
     * @param v the visitor
     * @param arg the argument
     * @param <K> the return type
     * @param <V> the argument type
     * @return the visitor result
     */
    public abstract <K, V> K accept(ConstraintVisitor<K, V> v, V arg);
}
