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
