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

import java.io.Serializable;
import java.util.Set;

public interface Expression<T extends Object> extends Serializable {

    /**
     * Returns the parent expression.
     *
     * @return a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    Expression<?> getParent();

    /**
     * Sets the parent expression.
     *
     * @param expr a {@link org.evosuite.symbolic.expr.Expression} object.
     */
    void setParent(Expression<?> expr);

    /**
     * Returns the concrete value of the expression.
     *
     * @return a {@link java.lang.Object} object.
     */
    T getConcreteValue();

    /**
     * Returns the size of the expression.
     *
     * @return the expression size.
     */
    int getSize();

    /**
     * Returns true if the expression contains a symbolic variable.
     *
     * @return true if it contains a symbolic variable, false otherwise.
     */
    boolean containsSymbolicVariable();

    /**
     * Returns the set of symbolic variables in the expression.
     *
     * @return the set of variables.
     */
    Set<Variable<?>> getVariables();

    /**
     * Returns the set of constant values in the expression.
     *
     * @return the set of constants.
     */
    Set<Object> getConstants();

    /**
     * Accepts an expression visitor.
     *
     * @param v the visitor
     * @param arg the argument
     * @param <K> the return type
     * @param <V> the argument type
     * @return the visitor result
     */
    <K, V> K accept(ExpressionVisitor<K, V> v, V arg);
}
