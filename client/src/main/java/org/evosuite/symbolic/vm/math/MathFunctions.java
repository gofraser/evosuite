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
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The symbolic models of the {@code java.lang.Math} methods.
 */
public final class MathFunctions {

    private final SymbolicEnvironment env;
    private final List<SymbolicFunction> functions = new ArrayList<>();

    private MathFunctions(SymbolicEnvironment env) {
        this.env = env;
    }

    /**
     * Creates the models of all supported {@code java.lang.Math} methods.
     *
     * @param env the symbolic environment the models operate on
     * @return the models
     */
    public static List<SymbolicFunction> create(SymbolicEnvironment env) {
        MathFunctions math = new MathFunctions(env);
        math.addAll();
        return math.functions;
    }

    private void addAll() {
        integerUnary("abs", Types.I2I_DESCRIPTOR, Operator.ABS);
        integerUnary("abs", Types.L2L_DESCRIPTOR, Operator.ABS);
        realUnary("abs", Types.F2F_DESCRIPTOR, Operator.ABS);
        realUnary("abs", Types.D2D_DESCRIPTOR, Operator.ABS);
        realUnary("acos", Operator.ACOS);
        realUnary("asin", Operator.ASIN);
        realUnary("atan", Operator.ATAN);
        realBinary("atan2", Types.DD2D_DESCRIPTOR, Operator.ATAN2);
        realUnary("cbrt", Operator.CBRT);
        realUnary("ceil", Operator.CEIL);
        realBinary("copySign", Types.FF2F_DESCRIPTOR, Operator.COPYSIGN);
        realBinary("copySign", Types.DD2D_DESCRIPTOR, Operator.COPYSIGN);
        realUnary("cos", Operator.COS);
        realUnary("cosh", Operator.COSH);
        realUnary("exp", Operator.EXP);
        realUnary("expm1", Operator.EXPM1);
        realUnary("floor", Operator.FLOOR);
        realToInteger("getExponent", Types.F2I_DESCRIPTOR, Operator.GETEXPONENT);
        realToInteger("getExponent", Types.D2I_DESCRIPTOR, Operator.GETEXPONENT);
        realBinary("hypot", Types.DD2D_DESCRIPTOR, Operator.HYPOT);
        realBinary("IEEEremainder", Types.DD2D_DESCRIPTOR, Operator.IEEEREMAINDER);
        realUnary("log", Operator.LOG);
        realUnary("log10", Operator.LOG10);
        realUnary("log1p", Operator.LOG1P);
        integerBinary("min", Types.II2I_DESCRIPTOR, Operator.MIN);
        integerBinary("min", Types.LL2L_DESCRIPTOR, Operator.MIN);
        realBinary("min", Types.FF2F_DESCRIPTOR, Operator.MIN);
        realBinary("min", Types.DD2D_DESCRIPTOR, Operator.MIN);
        integerBinary("max", Types.II2I_DESCRIPTOR, Operator.MAX);
        integerBinary("max", Types.LL2L_DESCRIPTOR, Operator.MAX);
        realBinary("max", Types.FF2F_DESCRIPTOR, Operator.MAX);
        realBinary("max", Types.DD2D_DESCRIPTOR, Operator.MAX);
        realBinary("nextAfter", Types.FD2F_DESCRIPTOR, Operator.NEXTAFTER);
        realBinary("nextAfter", Types.DD2D_DESCRIPTOR, Operator.NEXTAFTER);
        realUnary("nextUp", Types.F2F_DESCRIPTOR, Operator.NEXTUP);
        realUnary("nextUp", Types.D2D_DESCRIPTOR, Operator.NEXTUP);
        realBinary("pow", Types.DD2D_DESCRIPTOR, Operator.POW);
        realUnary("rint", Operator.RINT);
        realToInteger("round", Types.F2I_DESCRIPTOR, Operator.ROUND);
        realToInteger("round", Types.D2L_DESCRIPTOR, Operator.ROUND);
        realBinary("scalb", Types.FI2F_DESCRIPTOR, Operator.SCALB);
        realBinary("scalb", Types.DI2D_DESCRIPTOR, Operator.SCALB);
        realUnary("signum", Types.F2F_DESCRIPTOR, Operator.SIGNUM);
        realUnary("signum", Types.D2D_DESCRIPTOR, Operator.SIGNUM);
        realUnary("sin", Operator.SIN);
        realUnary("sinh", Operator.SINH);
        realUnary("sqrt", Operator.SQRT);
        realUnary("tan", Operator.TAN);
        realUnary("tanh", Operator.TANH);
        realUnary("toDegrees", Operator.TODEGREES);
        realUnary("toRadians", Operator.TORADIANS);
        realUnary("ulp", Types.F2F_DESCRIPTOR, Operator.ULP);
        realUnary("ulp", Types.D2D_DESCRIPTOR, Operator.ULP);
    }

    /** A unary method on doubles. */
    private void realUnary(String name, Operator op) {
        realUnary(name, Types.D2D_DESCRIPTOR, op);
    }

    private void realUnary(String name, String desc, Operator op) {
        functions.add(new RealUnaryFunction(env, name, desc, op));
    }

    private void realToInteger(String name, String desc, Operator op) {
        functions.add(new RealUnaryToIntegerFunction(env, name, desc, op));
    }

    private void realBinary(String name, String desc, Operator op) {
        functions.add(new RealBinaryFunction(env, name, desc, op));
    }

    private void integerUnary(String name, String desc, Operator op) {
        functions.add(new IntegerUnaryFunction(env, name, desc, op));
    }

    private void integerBinary(String name, String desc, Operator op) {
        functions.add(new IntegerBinaryFunction(env, name, desc, op));
    }

}
