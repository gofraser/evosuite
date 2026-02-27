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

import java.util.List;
import java.util.Set;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathFinderTest {

    @Test
    public void testDiamondGraph() {
        // Diamond graph: A -> B, A -> C, B -> D, C -> D
        // In reverse graph (if used for contexts): D -> B, D -> C, B -> A, C -> A
        // Let's just use Graph directly.
        // D -> B -> A
        // D -> C -> A

        Graph<String> graph = new Graph<String>() {};

        graph.addEdge("D", "B");
        graph.addEdge("D", "C");
        graph.addEdge("B", "A");
        graph.addEdge("C", "A");

        Set<List<String>> paths = PathFinder.getPaths(graph, "D");

        System.out.println("Paths found: " + paths);

        // We expect paths ending in A.
        // [D, B, A]
        // [D, C, A]
        // And prefixes [D, B], [D, C], [D]

        boolean foundDBA = false;
        boolean foundDCA = false;

        for (List<String> path : paths) {
            if (path.toString().equals("[D, B, A]")) foundDBA = true;
            if (path.toString().equals("[D, C, A]")) foundDCA = true;
        }

        assertTrue(foundDBA, "Should find path [D, B, A]");
        assertTrue(foundDCA, "Should find path [D, C, A]");
    }
}
