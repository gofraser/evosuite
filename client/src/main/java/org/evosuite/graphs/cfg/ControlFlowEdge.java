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
package org.evosuite.graphs.cfg;

import org.evosuite.coverage.branch.Branch;
import org.jgrapht.graph.DefaultEdge;

public class ControlFlowEdge extends DefaultEdge {

    private static final long serialVersionUID = -5009449930477928101L;

    private ControlDependency cd;
    private boolean isExceptionEdge;

    public ControlFlowEdge() {
        this.cd = null;
        this.isExceptionEdge = false;
    }

    public ControlFlowEdge(boolean isExceptionEdge) {
        this.isExceptionEdge = isExceptionEdge;
    }

    public ControlFlowEdge(ControlDependency cd, boolean isExceptionEdge) {
        this.cd = cd;
        this.isExceptionEdge = isExceptionEdge;
    }


    /**
     * Sort of a copy constructor.
     */
    public ControlFlowEdge(ControlFlowEdge clone) {
        if (clone != null) {
            this.cd = clone.cd;
            this.isExceptionEdge = clone.isExceptionEdge;
        }
    }

    public ControlDependency getControlDependency() {
        return cd;
    }

    public boolean hasControlDependency() {
        return cd != null;
    }

    public Branch getBranchInstruction() {
        if (cd == null) {
            return null;
        }

        return cd.getBranch();
    }

    public boolean isExceptionEdge() {
        return isExceptionEdge;
    }

    public boolean getBranchExpressionValue() {
        if (hasControlDependency()) {
            return cd.getBranchExpressionValue();
        }

        return true;
    }

    @Override
    public String toString() {
        String r = "";
        if (isExceptionEdge) {
            r += "E ";
        }
        if (cd != null) {
            r += cd.toString();
        }
        return r;
    }
}
