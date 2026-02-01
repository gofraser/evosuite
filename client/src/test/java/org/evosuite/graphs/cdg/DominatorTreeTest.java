package org.evosuite.graphs.cdg;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Set;

public class DominatorTreeTest {

    @Test
    public void testDiamondGraph() {
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "testMethod");
        // Entry(0) -> A(1)
        // A(1) -> B(2)
        // A(1) -> C(3)
        // B(2) -> D(4)
        // C(3) -> D(4)
        // D(4) -> Exit(5)

        for(int i=0; i<=5; i++) cfg.addNode(i);

        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(1, 2);
        cfg.addTestEdge(1, 3);
        cfg.addTestEdge(2, 4);
        cfg.addTestEdge(3, 4);
        cfg.addTestEdge(4, 5);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        // Check Immediate Dominators
        // Entry(0) -> null (root)
        assertEquals(null, dt.getImmediateDominator(0));
        // A(1) -> 0
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        // B(2) -> 1
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));
        // C(3) -> 1
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(3));
        // D(4) -> 1
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(4));
        // Exit(5) -> 4
        assertEquals(Integer.valueOf(4), dt.getImmediateDominator(5));

        // Check Dominance Frontiers
        // DF(1) = {}
        assertTrue(dt.getDominatingFrontiers(1).isEmpty());
        // DF(2) = {4}
        Set<Integer> df2 = dt.getDominatingFrontiers(2);
        assertEquals(1, df2.size());
        assertTrue(df2.contains(4));
        // DF(3) = {4}
        Set<Integer> df3 = dt.getDominatingFrontiers(3);
        assertEquals(1, df3.size());
        assertTrue(df3.contains(4));
        // DF(4) = {}
        assertTrue(dt.getDominatingFrontiers(4).isEmpty());
    }
}
