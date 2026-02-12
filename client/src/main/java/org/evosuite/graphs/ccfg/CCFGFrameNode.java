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
package org.evosuite.graphs.ccfg;

public class CCFGFrameNode extends CCFGNode {

    private final ClassControlFlowGraph.FrameNodeType type;

    /**
     * Constructor for CCFGFrameNode.
     *
     * @param type a {@link org.evosuite.graphs.ccfg.ClassControlFlowGraph.FrameNodeType} object.
     */
    public CCFGFrameNode(ClassControlFlowGraph.FrameNodeType type) {
        this.type = type;
    }

    /**
     * Getter for the field <code>type</code>.
     *
     * @return a {@link org.evosuite.graphs.ccfg.ClassControlFlowGraph.FrameNodeType} object.
     */
    public ClassControlFlowGraph.FrameNodeType getType() {
        return type;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
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
        CCFGFrameNode other = (CCFGFrameNode) obj;
        if (type != other.type) {
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Frame " + type.toString();
    }

}
