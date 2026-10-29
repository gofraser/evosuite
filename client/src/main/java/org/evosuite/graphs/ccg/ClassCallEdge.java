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
package org.evosuite.graphs.ccg;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.jgrapht.graph.DefaultEdge;

import java.util.Objects;

public class ClassCallEdge extends DefaultEdge {

    private static final long serialVersionUID = 7136724698608115327L;

    private final BytecodeInstruction callInstruction;

    /**
     * Constructor for ClassCallEdge.
     */
    public ClassCallEdge(BytecodeInstruction callInstruction) {
        this.callInstruction = callInstruction;
    }

    /**
     * Getter for the field <code>callInstruction</code>.
     */
    public BytecodeInstruction getCallInstruction() {
        return callInstruction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(callInstruction);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ClassCallEdge other = (ClassCallEdge) obj;
        return Objects.equals(callInstruction, other.callInstruction);
    }

    @Override
    public String toString() {
        return callInstruction.toString();
    }

}
