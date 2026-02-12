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
package org.evosuite.graphs.cdg;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * This class serves as a convenience data structure within {@link DominatorTree}.
 *
 * <p>For every node within a CFG for which the immediateDominators are to be
 * computed this class holds auxiliary information needed during the computation
 * inside the DominatorTree.
 *
 * <p>After that computation instances of this class hold the connection between
 * CFG nodes and their immediateDominators.
 *
 * <p>Look at {@link DominatorTree} for more detailed information.
 *
 * @author Andre Mis
 */
class DominatorNode<V> {

    final V node;

    /**
     * DFS number.
     */
    int dfsNumber = 0;

    /**
     * parent of node within spanning tree of DFS inside {@link DominatorTree}.
     */
    DominatorNode<V> parent;

    // computed dominators
    DominatorNode<V> semiDominator;
    DominatorNode<V> immediateDominator;

    // auxiliary field needed for dominator computation
    /**
     * Set of nodes for which this node is the semi-dominator.
     */
    Set<DominatorNode<V>> bucket = new LinkedHashSet<>();

    // data structure needed to represented forest produced during cfg.DominatorTree computation
    DominatorNode<V> ancestor;
    DominatorNode<V> label;

    DominatorNode(V node) {
        if (node == null) {
            throw new IllegalArgumentException("Node cannot be null");
        }
        this.node = node;
        this.label = this;
    }

    void link(DominatorNode<V> v) {
        ancestor = v;
    }

    DominatorNode<V> eval() {
        if (ancestor == null) {
            return this;
        }

        compress();

        return label;
    }

    void compress() {
        if (ancestor == null) {
            throw new IllegalStateException("may only be called when ancestor is set");
        }

        if (ancestor.ancestor != null) {
            ancestor.compress();
            if (ancestor.label.semiDominator.dfsNumber < label.semiDominator.dfsNumber) {
                label = ancestor.label;
            }

            ancestor = ancestor.ancestor;
        }
    }

    DominatorNode<V> getFromBucket() {
        for (DominatorNode<V> r : bucket) {
            return r;
        }

        return null;
    }

    /**
     * Checks if this node is the root node of the CFG (DFS number 1).
     *
     * @return true if this is the root node.
     */
    public boolean isRootNode() {
        // DFS traversal assigns 1 to the root.
        return dfsNumber == 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "DTNode " + dfsNumber + " - " + node;
    }
}
