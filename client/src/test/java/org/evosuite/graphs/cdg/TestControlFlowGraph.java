/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.graphs.cdg;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlFlowGraph;
import org.evosuite.graphs.cfg.ControlFlowEdge;

public class TestControlFlowGraph extends ControlFlowGraph<Integer> {

    private Integer entryPoint = null;

    public TestControlFlowGraph(String className, String methodName) {
        super(className, methodName, 0);
    }

    public void setEntryPoint(Integer entry) {
        this.entryPoint = entry;
    }

    @Override
    public Integer determineEntryPoint() {
        if (entryPoint != null) {
            return entryPoint;
        }
        return super.determineEntryPoint();
    }

    @Override
    public BytecodeInstruction getInstruction(int instructionId) {
        return null;
    }

    @Override
    public boolean containsInstruction(BytecodeInstruction instruction) {
        return false;
    }

    @Override
    public String getCFGType() {
        return "TestCFG";
    }

    public void addNode(Integer i) {
        addVertex(i);
    }

    public void addTestEdge(Integer src, Integer target) {
        addEdge(src, target, new ControlFlowEdge());
    }
}
