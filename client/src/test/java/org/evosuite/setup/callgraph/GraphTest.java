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
package org.evosuite.setup.callgraph;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

public class GraphTest {

    @Test
    public void testRemoveVertex() {
        Graph<String> graph = new Graph<String>() {};
        graph.addEdge("A", "B");
        graph.addEdge("B", "C");

        assertTrue(graph.containsVertex("B"));
        assertTrue(graph.containsEdge("A", "B"));
        assertTrue(graph.containsEdge("B", "C"));

        graph.removeVertex("B");

        assertFalse(graph.containsVertex("B"));
        assertFalse(graph.containsEdge("A", "B"));
        assertFalse(graph.containsEdge("B", "C"));

        // Check dangling edges in A
        Iterator<String> neighborsA = graph.getNeighbors("A").iterator();
        assertFalse(neighborsA.hasNext(), "A should not have neighbors after B is removed");

        // Check dangling reverse edges in C
        Iterator<String> reverseNeighborsC = graph.getReverseNeighbors("C").iterator();
        assertFalse(reverseNeighborsC.hasNext(), "C should not have reverse neighbors after B is removed");
    }
}
