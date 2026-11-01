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

package org.evosuite.testcase.synthesizer;

import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.variable.VariableReference;

import java.util.List;

/**
 * This class describes to cover a node in the object construction graph,
 * (1) which caller object, <code>var</code>, in the test case should be used and
 * (2) what other nodes should be covered when constructing statements based on <code>var</code>.
 *
 * <p>Some node cannot correspond to any variable in test source code, but their children can.
 * For example, arraylist.elementData[i] has a path like: arraylist -> elementData => [].
 * the elementData in arraylist cannot be accessed, but we still need to find the correspondence
 * between arraylist.elementData[i] and some variables in the source code.
 *
 * @author Yun Lin
 */
public class VariableInTest {
    /**
     * The variable to construct the statement of a node.
     */
    public VariableReference callerObject;
    /**
     * a list of nodes in the graph should be covered by constructing a statement. Its size will be always larger than
     * or equal to 1.
     *
     * <p>The last node is the original node.
     */
    public List<DepVariableWrapper> nodePath;

    /**
     * Creates the pairing of a node path with the test variable that is used to construct it.
     *
     * @param variable the variable used to construct the statement of the node
     * @param nodePath the nodes to cover when constructing the statement
     */
    public VariableInTest(VariableReference variable, List<DepVariableWrapper> nodePath) {
        super();
        this.callerObject = variable;
        this.nodePath = nodePath;
    }

    /**
     * Returns the first node of the node path.
     *
     * @return the node whose statement is constructed from the caller object
     */
    public DepVariableWrapper getNode() {
        return nodePath.get(0);
    }

    /**
     * Checks whether we need to cover its parent nodes when visiting this node.
     *
     * @return true if the node path consists of a single node
     */
    public boolean isDirentNodeAccess() {
        return nodePath.size() == 1;
    }
}
