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
package org.evosuite.symbolic.vm.math;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.fp.RealUnaryExpression;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;

/**
 * Symbolic function for a {@code java.lang.Math} method that maps a float or
 * double to a float or double, such as {@code sin} or {@code ulp}.
 */
public final class RealUnaryFunction extends SymbolicFunction {

    private final Operator op;

    public RealUnaryFunction(SymbolicEnvironment env, String name, String desc, Operator op) {
        super(env, Types.JAVA_LANG_MATH, name, desc);
        this.op = op;
    }

    @Override
    public Object executeFunction() {
        RealValue arg = this.getSymbRealArgument(0);
        if (arg.containsSymbolicVariable()) {
            double res = ((Number) this.getConcRetVal()).doubleValue();
            return new RealUnaryExpression(arg, op, res);
        }
        return this.getSymbRealRetVal();
    }

}
