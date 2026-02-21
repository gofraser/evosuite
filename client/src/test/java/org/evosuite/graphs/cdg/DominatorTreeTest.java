package org.evosuite.graphs.cdg;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void testSingleNode() {
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "singleNode");
        cfg.addNode(0);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        assertNull(dt.getImmediateDominator(0));
        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
    }

    @Test
    public void testTwoNodes() {
        // 0 -> 1
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "twoNodes");
        cfg.addNode(0);
        cfg.addNode(1);
        cfg.addTestEdge(0, 1);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));

        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
        assertTrue(dt.getDominatingFrontiers(1).isEmpty());
    }

    @Test
    public void testLinearChain() {
        // 0 -> 1 -> 2 -> 3 -> 4
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "linearChain");
        for (int i = 0; i <= 4; i++) cfg.addNode(i);
        for (int i = 0; i < 4; i++) cfg.addTestEdge(i, i + 1);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        assertNull(dt.getImmediateDominator(0));
        for (int i = 1; i <= 4; i++) {
            assertEquals(Integer.valueOf(i - 1), dt.getImmediateDominator(i));
        }

        // All dominance frontiers empty in a linear chain
        for (int i = 0; i <= 4; i++) {
            assertTrue(dt.getDominatingFrontiers(i).isEmpty(), "DF(" + i + ") should be empty");
        }
    }

    @Test
    public void testIfThenNoElse() {
        // 0 -> 1 (branch)
        // 1 -> 2 (then)
        // 1 -> 3 (skip)
        // 2 -> 3 (join)
        // 3 -> 4
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "ifThenNoElse");
        for (int i = 0; i <= 4; i++) cfg.addNode(i);
        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(1, 2);
        cfg.addTestEdge(1, 3);
        cfg.addTestEdge(2, 3);
        cfg.addTestEdge(3, 4);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(3));
        assertEquals(Integer.valueOf(3), dt.getImmediateDominator(4));

        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
        assertTrue(dt.getDominatingFrontiers(1).isEmpty());
        // DF(2) = {3}
        Set<Integer> df2 = dt.getDominatingFrontiers(2);
        assertEquals(1, df2.size());
        assertTrue(df2.contains(3));
        assertTrue(dt.getDominatingFrontiers(3).isEmpty());
        assertTrue(dt.getDominatingFrontiers(4).isEmpty());
    }

    @Test
    public void testNestedDiamonds() {
        // Outer diamond with inner diamond on the left branch:
        //       0
        //      / \
        //     1   4
        //    / \  |
        //   2   3 |
        //    \ /  |
        //     5   |
        //      \ /
        //       6
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "nestedDiamonds");
        for (int i = 0; i <= 6; i++) cfg.addNode(i);
        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(0, 4);
        cfg.addTestEdge(1, 2);
        cfg.addTestEdge(1, 3);
        cfg.addTestEdge(2, 5);
        cfg.addTestEdge(3, 5);
        cfg.addTestEdge(4, 6);
        cfg.addTestEdge(5, 6);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        // Immediate dominators
        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(3));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(4));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(5));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(6));

        // Dominance frontiers
        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
        // DF(1) = {6} (via "up" from child 5)
        Set<Integer> df1 = dt.getDominatingFrontiers(1);
        assertEquals(1, df1.size());
        assertTrue(df1.contains(6));
        // DF(2) = {5}
        Set<Integer> df2 = dt.getDominatingFrontiers(2);
        assertEquals(1, df2.size());
        assertTrue(df2.contains(5));
        // DF(3) = {5}
        Set<Integer> df3 = dt.getDominatingFrontiers(3);
        assertEquals(1, df3.size());
        assertTrue(df3.contains(5));
        // DF(4) = {6}
        Set<Integer> df4 = dt.getDominatingFrontiers(4);
        assertEquals(1, df4.size());
        assertTrue(df4.contains(6));
        // DF(5) = {6}
        Set<Integer> df5 = dt.getDominatingFrontiers(5);
        assertEquals(1, df5.size());
        assertTrue(df5.contains(6));
        assertTrue(dt.getDominatingFrontiers(6).isEmpty());
    }

    @Test
    public void testSimpleLoop() {
        // 0 -> 1 -> 2 -> 1 (back edge)
        //              -> 3
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "simpleLoop");
        for (int i = 0; i <= 3; i++) cfg.addNode(i);
        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(1, 2);
        cfg.addTestEdge(2, 1); // back edge
        cfg.addTestEdge(2, 3);

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));
        assertEquals(Integer.valueOf(2), dt.getImmediateDominator(3));

        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
        // DF(1) = {1} (loop header is in its own DF)
        Set<Integer> df1 = dt.getDominatingFrontiers(1);
        assertEquals(1, df1.size());
        assertTrue(df1.contains(1));
        // DF(2) = {1}
        Set<Integer> df2 = dt.getDominatingFrontiers(2);
        assertEquals(1, df2.size());
        assertTrue(df2.contains(1));
        assertTrue(dt.getDominatingFrontiers(3).isEmpty());
    }

    @Test
    public void testLoopWithNestedIf() {
        // 0 -> 1 (loop header)
        // 1 -> 2 (branch)
        // 2 -> 3 (then)
        // 2 -> 4 (else)
        // 3 -> 5 (join)
        // 4 -> 5 (join)
        // 5 -> 1 (back edge)
        // 1 -> 6 (exit loop)
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "loopWithNestedIf");
        for (int i = 0; i <= 6; i++) cfg.addNode(i);
        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(1, 2);
        cfg.addTestEdge(1, 6);
        cfg.addTestEdge(2, 3);
        cfg.addTestEdge(2, 4);
        cfg.addTestEdge(3, 5);
        cfg.addTestEdge(4, 5);
        cfg.addTestEdge(5, 1); // back edge

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        // Immediate dominators
        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));
        assertEquals(Integer.valueOf(2), dt.getImmediateDominator(3));
        assertEquals(Integer.valueOf(2), dt.getImmediateDominator(4));
        assertEquals(Integer.valueOf(2), dt.getImmediateDominator(5));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(6));

        // Dominance frontiers
        assertTrue(dt.getDominatingFrontiers(0).isEmpty());
        // DF(1) = {1} (loop)
        Set<Integer> df1 = dt.getDominatingFrontiers(1);
        assertEquals(1, df1.size());
        assertTrue(df1.contains(1));
        // DF(2) = {1} (via "up" from 5's DF)
        Set<Integer> df2 = dt.getDominatingFrontiers(2);
        assertEquals(1, df2.size());
        assertTrue(df2.contains(1));
        // DF(3) = {5}
        Set<Integer> df3 = dt.getDominatingFrontiers(3);
        assertEquals(1, df3.size());
        assertTrue(df3.contains(5));
        // DF(4) = {5}
        Set<Integer> df4 = dt.getDominatingFrontiers(4);
        assertEquals(1, df4.size());
        assertTrue(df4.contains(5));
        // DF(5) = {1}
        Set<Integer> df5 = dt.getDominatingFrontiers(5);
        assertEquals(1, df5.size());
        assertTrue(df5.contains(1));
        assertTrue(dt.getDominatingFrontiers(6).isEmpty());
    }

    @Test
    public void testBackEdgeToRoot() {
        // 0 -> 1
        // 1 -> 0 (back edge to root)
        // 1 -> 2
        // Bug reproduction: without fix, DF(1) = {} instead of {0}
        TestControlFlowGraph cfg = new TestControlFlowGraph("TestClass", "backEdgeToRoot");
        for (int i = 0; i <= 2; i++) cfg.addNode(i);
        cfg.addTestEdge(0, 1);
        cfg.addTestEdge(1, 0); // back edge to root
        cfg.addTestEdge(1, 2);
        cfg.setEntryPoint(0); // needed since node 0 has in-degree > 0

        DominatorTree<Integer> dt = new DominatorTree<>(cfg);

        // Immediate dominators
        assertNull(dt.getImmediateDominator(0));
        assertEquals(Integer.valueOf(0), dt.getImmediateDominator(1));
        assertEquals(Integer.valueOf(1), dt.getImmediateDominator(2));

        // Dominance frontiers
        // DF(0) = {0} (root is in its own DF due to back edge)
        Set<Integer> df0 = dt.getDominatingFrontiers(0);
        assertEquals(1, df0.size());
        assertTrue(df0.contains(0));
        // DF(1) = {0} (1's CFG child is 0, and iDom(0)=null != 1, so 0 is in DF(1))
        Set<Integer> df1 = dt.getDominatingFrontiers(1);
        assertEquals(1, df1.size());
        assertTrue(df1.contains(0));
        assertTrue(dt.getDominatingFrontiers(2).isEmpty());
    }
}
