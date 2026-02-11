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
package org.evosuite.symbolic.vm.math;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerUnaryExpression;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealUnaryExpression;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;

/**
 * Symbolic functions for Math.abs.
 *
 * @author galeotti
 */
public abstract class ABS {

    private static final String ABS_FUNCTION_NAME = "abs";

    /**
     * Symbolic function for Math.abs(double).
     */
    public static final class ABS_D extends SymbolicFunction {

        public ABS_D(SymbolicEnvironment env) {
            super(env, Types.JAVA_LANG_MATH, ABS_FUNCTION_NAME,
                    Types.D2D_DESCRIPTOR);
        }

        @Override
        public Object executeFunction() {
            double res = this.getConcDoubleRetVal();
            RealValue realExpression = this.getSymbRealArgument(0);

            RealValue symVal;
            if (realExpression.containsSymbolicVariable()) {
                symVal = new RealUnaryExpression(realExpression, Operator.ABS,
                        res);
            } else {
                symVal = this.getSymbRealRetVal();
            }
            return symVal;
        }

    }

    /**
     * Symbolic function for Math.abs(float).
     */
    public static final class ABS_F extends SymbolicFunction {

        public ABS_F(SymbolicEnvironment env) {
            super(env, Types.JAVA_LANG_MATH, ABS_FUNCTION_NAME,
                    Types.F2F_DESCRIPTOR);
        }

        @Override
        public Object executeFunction() {
            float res = this.getConcFloatRetVal();
            RealValue realExpression = this.getSymbRealArgument(0);

            RealValue symVal;
            if (realExpression.containsSymbolicVariable()) {
                symVal = new RealUnaryExpression(realExpression, Operator.ABS,
                        (double) res);
            } else {
                symVal = this.getSymbRealRetVal();
            }
            return symVal;
        }
    }

    /**
     * Symbolic function for Math.abs(int).
     */
    public static final class ABS_I extends SymbolicFunction {

        public ABS_I(SymbolicEnvironment env) {
            super(env, Types.JAVA_LANG_MATH, ABS_FUNCTION_NAME,
                    Types.I2I_DESCRIPTOR);
        }

        @Override
        public Object executeFunction() {
            int res = this.getConcIntRetVal();
            IntegerValue intExpression = this.getSymbIntegerArgument(0);
            IntegerValue symVal;
            if (intExpression.containsSymbolicVariable()) {
                symVal = new IntegerUnaryExpression(intExpression,
                        Operator.ABS, (long) res);
            } else {
                symVal = this.getSymbIntegerRetVal();
            }
            return symVal;
        }

    }

    /**
     * Symbolic function for Math.abs(long).
     */
    public static final class ABS_L extends SymbolicFunction {

        public ABS_L(SymbolicEnvironment env) {
            super(env, Types.JAVA_LANG_MATH, ABS_FUNCTION_NAME,
                    Types.L2L_DESCRIPTOR);
        }

        @Override
        public Object executeFunction() {
            long res = this.getConcLongRetVal();
            IntegerValue intExpression = this.getSymbIntegerArgument(0);
            IntegerValue symVal;
            if (intExpression.containsSymbolicVariable()) {
                symVal = new IntegerUnaryExpression(intExpression,
                        Operator.ABS, res);
            } else {
                symVal = this.getSymbIntegerRetVal();
            }
            return symVal;
        }

    }

}
