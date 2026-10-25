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
package org.evosuite.symbolic.vm.wrappers;

import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.SymbolicFunction;

/**
 * Symbolic function for the constructor of a boxing wrapper: stores the
 * symbolic argument in the heap field of the receiver.
 */
final class BoxedInit extends SymbolicFunction {

    private final BoxedType type;

    BoxedInit(SymbolicEnvironment env, BoxedType type) {
        super(env, type.owner, Types.INIT, type.initDescriptor());
        this.type = type;
    }

    @Override
    public Object executeFunction() {
        Expression<?> value = type.isReal() ? this.getSymbRealArgument(0) : this.getSymbIntegerArgument(0);
        ReferenceConstant receiver = this.getSymbReceiver();
        env.heap.putField(type.owner, type.heapField, null, receiver, value);

        // return void
        return null;
    }

}
