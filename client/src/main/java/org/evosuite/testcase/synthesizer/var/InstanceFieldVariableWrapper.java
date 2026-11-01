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

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.synthesizer.VariableInTest;

import java.util.Map;

public class InstanceFieldVariableWrapper extends FieldVariableWrapper {

    protected InstanceFieldVariableWrapper(DepVariable var) {
        super(var);
    }

    @Override
    public VarRelevance generateOrFindStatement(TestCase test, boolean isLeaf, VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue) {
        if (variable == null || variable.callerObject == null) {
            return null;
        }

        return super.generateOrFindStatement(test, isLeaf, variable, map, b, allowNullValue);
    }
}
