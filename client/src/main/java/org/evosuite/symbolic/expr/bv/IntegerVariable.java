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
package org.evosuite.symbolic.expr.bv;

import org.evosuite.symbolic.expr.AbstractExpression;
import org.evosuite.symbolic.expr.ExpressionVisitor;
import org.evosuite.symbolic.expr.Variable;

import java.util.HashSet;
import java.util.Set;

public final class IntegerVariable extends AbstractExpression<Long> implements
        IntegerValue, Variable<Long> {

    private static final long serialVersionUID = 6302073364874210525L;

    private final String name;

    private final long minValue;

    private final long maxValue;

    public IntegerVariable(String name, long conV, long minValue, long maxValue) {
        super(conV, 1, true);
        this.name = name;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public void setConcreteValue(Long con) {
        this.concreteValue = con;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Long getMinValue() {
        return minValue;
    }

    @Override
    public Long getMaxValue() {
        return maxValue;
    }

    @Override
    public String toString() {
        return this.name + "(" + concreteValue + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof IntegerVariable) {
            IntegerVariable v = (IntegerVariable) obj;
            return this.getName().equals(v.getName());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public Set<Variable<?>> getVariables() {
        Set<Variable<?>> variables = new HashSet<>();
        variables.add(this);
        return variables;
    }

    @Override
    public Set<Object> getConstants() {
        // Do not include original values?
        return new HashSet<>();
    }

    @Override
    public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
        return v.visit(this, arg);
    }
}
