package org.evosuite.graphs;

import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.junit.Test;
import static org.junit.Assert.*;

public class FloydWarshallTest {

    @Test
    public void testShortestPathAndDiameter() {
        DefaultDirectedGraph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        graph.addVertex("A");
        graph.addVertex("B");
        graph.addVertex("C");
        graph.addVertex("D");

        graph.addEdge("A", "B");
        graph.addEdge("B", "C");
        graph.addEdge("C", "D");
        graph.addEdge("A", "D"); // shortcut

        // In DefaultEdge, weights are 1.0 by default unless weighted graph is used.
        // But DefaultDirectedGraph is not weighted by default?
        // JGraphT's DefaultEdge does not support weights unless we use SimpleWeightedGraph or similar
        // OR we use graph.setEdgeWeight. But DefaultDirectedGraph supports setEdgeWeight?
        // Let's check JGraphT version... usually DefaultDirectedGraph implements WeightedGraph if specified?
        // No, DefaultDirectedGraph implements DirectedGraph.
        // However, JGraphT stores weights in a map inside the graph usually.

        // Let's assume unweighted (weight 1.0).
        // A->B (1), B->C (1), C->D (1). A->D (1).
        // Path A->D should be 1.
        // Path A->C should be 2.

        FloydWarshall<String, DefaultEdge> fw = new FloydWarshall<>(graph);

        assertEquals(1.0, fw.shortestDistance("A", "B"), 0.001);
        assertEquals(2.0, fw.shortestDistance("A", "C"), 0.001);
        assertEquals(1.0, fw.shortestDistance("A", "D"), 0.001);
        assertEquals(0.0, fw.shortestDistance("A", "A"), 0.001);
        assertEquals(Double.POSITIVE_INFINITY, fw.shortestDistance("B", "A"), 0.001); // Directed

        // Diameter:
        // Max shortest path.
        // A->B=1, A->C=2, A->D=1
        // B->C=1, B->D=2
        // C->D=1
        // Max is 2.
        assertEquals(2.0, fw.getDiameter(), 0.001);
    }

    @Test
    public void testEmptyGraph() {
        DefaultDirectedGraph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        FloydWarshall<String, DefaultEdge> fw = new FloydWarshall<>(graph);
        assertEquals(0.0, fw.getDiameter(), 0.001);
    }
}
