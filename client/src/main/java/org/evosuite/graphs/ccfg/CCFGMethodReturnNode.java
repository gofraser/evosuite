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
package org.evosuite.graphs.ccfg;

import org.evosuite.graphs.cfg.BytecodeInstruction;

public class CCFGMethodReturnNode extends CCFGNode {

    private final BytecodeInstruction callInstruction;

    public CCFGMethodReturnNode(BytecodeInstruction callInstruction) {
        this.callInstruction = callInstruction;
    }

    public String getMethod() {
        return callInstruction.getMethodName();
    }

    public BytecodeInstruction getCallInstruction() {
        return callInstruction;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((callInstruction == null) ? 0 : callInstruction.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        CCFGMethodReturnNode other = (CCFGMethodReturnNode) obj;
        if (callInstruction == null) {
            return other.callInstruction == null;
        } else {
            return callInstruction.equals(other.callInstruction);
        }
    }

    @Override
    public String toString() {
        return "RETURN from " + callInstruction.toString();
    }
}
