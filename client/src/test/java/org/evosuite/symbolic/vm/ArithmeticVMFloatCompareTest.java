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
package org.evosuite.symbolic.vm;

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The NaN result of the floating point comparisons is -1 for the "l" opcodes
 * and +1 for the "g" opcodes.
 */
public class ArithmeticVMFloatCompareTest {

    private static long run(boolean wide, double left, double right, Consumer<ArithmeticVM> op)
            throws Exception {
        SymbolicEnvironment env = new SymbolicEnvironment(null);
        ArithmeticVM vm = new ArithmeticVM(env, new PathConditionCollector());
        env.pushFrame(new MethodFrame(
                ArithmeticVMFloatCompareTest.class.getDeclaredMethod("run", boolean.class, double.class,
                        double.class, Consumer.class), 4));
        RealValue l = ExpressionFactory.buildNewRealConstant(left);
        RealValue r = ExpressionFactory.buildNewRealConstant(right);
        if (wide) {
            env.topFrame().operandStack.pushFp64(l);
            env.topFrame().operandStack.pushFp64(r);
        } else {
            env.topFrame().operandStack.pushFp32(l);
            env.topFrame().operandStack.pushFp32(r);
        }
        op.accept(vm);
        IntegerValue result = env.topFrame().operandStack.popBv32();
        return result.getConcreteValue();
    }

    private static void check(double left, double right, long ordinary) throws Exception {
        boolean nan = Double.isNaN(left) || Double.isNaN(right);
        assertEquals(nan ? -1 : ordinary, run(false, left, right, ArithmeticVM::FCMPL));
        assertEquals(nan ? 1 : ordinary, run(false, left, right, ArithmeticVM::FCMPG));
        assertEquals(nan ? -1 : ordinary, run(true, left, right, ArithmeticVM::DCMPL));
        assertEquals(nan ? 1 : ordinary, run(true, left, right, ArithmeticVM::DCMPG));
    }

    @Test
    public void nanOnLeft() throws Exception {
        check(Double.NaN, 1.0, 0);
    }

    @Test
    public void nanOnRight() throws Exception {
        check(1.0, Double.NaN, 0);
    }

    @Test
    public void nanOnBothSides() throws Exception {
        check(Double.NaN, Double.NaN, 0);
    }

    @Test
    public void ordinaryValues() throws Exception {
        check(1.0, 2.0, -1);
        check(2.0, 1.0, 1);
        check(2.0, 2.0, 0);
    }
}
