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

package org.evosuite.testcase.synthesizer.var;

import org.evosuite.graphs.interprocedural.var.ArrayElementVariable;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.graphs.interprocedural.var.InstanceFieldVariable;
import org.evosuite.graphs.interprocedural.var.ParameterVariable;
import org.evosuite.graphs.interprocedural.var.StaticFieldVariable;
import org.evosuite.graphs.interprocedural.var.ThisVariable;

/**
 * Creates the wrapper matching the kind of a dependency variable.
 */
public class DepVariableWrapperFactory {

    /**
     * Wraps a dependency variable in the wrapper subclass that corresponds to its type.
     *
     * @param var the dependency variable to wrap
     * @return the wrapper for {@code var}
     */
    public static DepVariableWrapper createWrapperInstance(DepVariable var) {
        if (var instanceof ArrayElementVariable) {
            return new ArrayElementVariableWrapper(var);
        } else if (var instanceof InstanceFieldVariable) {
            return new InstanceFieldVariableWrapper(var);
        } else if (var instanceof StaticFieldVariable) {
            return new StaticFieldVariableWrapper(var);
        } else if (var instanceof ParameterVariable) {
            return new ParameterVariableWrapper(var);
        } else if (var instanceof ThisVariable) {
            return new ThisVariableWrapper(var);
        } else {
            return new OtherVariableWrapper(var);
        }
    }
}
