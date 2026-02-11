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
package org.evosuite.symbolic.vm.wrappers;

import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;

/**
 * Symbolic function for Float.floatValue.
 *
 * @author galeotti
 */
public final class F_FloatValue extends SymbolicFunction {

    private static final String FLOAT_VALUE = "floatValue";

    public F_FloatValue(SymbolicEnvironment env) {
        super(env, Types.JAVA_LANG_FLOAT, FLOAT_VALUE, Types.TO_FLOAT);
    }

    @Override
    public Object executeFunction() {
        ReferenceConstant symbFloat = this.getSymbReceiver();
        Float concFloat = (Float) this.getConcReceiver();

        float concFloatValue = this.getConcFloatRetVal();

        RealValue symbIntValue = env.heap.getField(Types.JAVA_LANG_FLOAT,
                SymbolicHeap.$FLOAT_VALUE, concFloat, symbFloat,
                concFloatValue);

        return symbIntValue;
    }

}
