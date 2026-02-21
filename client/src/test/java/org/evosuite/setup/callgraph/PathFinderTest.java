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
