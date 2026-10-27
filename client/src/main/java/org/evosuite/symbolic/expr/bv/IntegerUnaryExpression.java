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

import org.evosuite.symbolic.expr.*;

public final class IntegerUnaryExpression extends AbstractOperatorUnaryExpression<Long, Long> implements
        IntegerValue, UnaryExpression<Long> {

    private static final long serialVersionUID = 1966395070897274841L;

    public IntegerUnaryExpression(Expression<Long> e, Operator op2, Long con) {
        super(con, e, op2);
    }

    @Override
    public String toString() {
        return "(" + getOperator() + "(" + getOperand() + "))";
    }

    @Override
    public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
        return v.visit(this, arg);
    }
}
