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
package org.evosuite.symbolic.expr.fp;

import org.evosuite.symbolic.expr.*;
import org.evosuite.symbolic.expr.bv.IntegerValue;

public final class IntegerToRealCast extends AbstractUnaryExpression<Double, Long> implements
        RealValue, Cast<Long> {

    private static final long serialVersionUID = -3070453617714122236L;

    public IntegerToRealCast(IntegerValue expr, Double concValue) {
        super(concValue, expr);
    }

    @Override
    public Expression<Long> getArgument() {
        return getOperand();
    }

    @Override
    public String toString() {
        return "((REAL)" + getOperand() + ")";
    }

    @Override
    public <K, V> K accept(ExpressionVisitor<K, V> v, V arg) {
        return v.visit(this, arg);
    }
}
