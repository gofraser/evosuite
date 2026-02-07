package org.evosuite.graphs.ccg;

import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.CFGClassAdapter;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClassCallGraphSystemTest {

    @Before
    public void setUp() {
        GraphPool.getInstance(getClass().getClassLoader()).clear();
    }

    private void analyzeClass(Class<?> clazz) throws Exception {
        String className = clazz.getName();
        ClassReader cr = new ClassReader(className);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(), new ClassWriter(ClassWriter.COMPUTE_MAXS), className);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);
    }

    static class LinearCalls {
        public void methodA() {
            methodB();
        }
        public void methodB() {
            methodC();
        }
        public void methodC() {
        }
    }

    @Test
    public void testLinearCalls() throws Exception {
        analyzeClass(LinearCalls.class);
        String className = LinearCalls.class.getName();
        ClassCallGraph ccg = new ClassCallGraph(getClass().getClassLoader(), className);

        // methodA, methodB, methodC, <init>
        assertEquals("Should have 4 vertices (3 methods + init)", 4, ccg.vertexCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        ClassCallNode nodeB = ccg.getNodeByMethodName("methodB()V");
        ClassCallNode nodeC = ccg.getNodeByMethodName("methodC()V");

        assertNotNull("methodA should be in the graph", nodeA);
        assertNotNull("methodB should be in the graph", nodeB);
        assertNotNull("methodC should be in the graph", nodeC);

        assertTrue("Edge methodA -> methodB should exist", ccg.containsEdge(nodeA, nodeB));
        assertTrue("Edge methodB -> methodC should exist", ccg.containsEdge(nodeB, nodeC));
    }

    static class CyclicCalls {
        public void methodA() {
            methodB();
        }
        public void methodB() {
            methodA();
        }
    }

    @Test
    public void testCyclicCalls() throws Exception {
        analyzeClass(CyclicCalls.class);
        String className = CyclicCalls.class.getName();
        ClassCallGraph ccg = new ClassCallGraph(getClass().getClassLoader(), className);

        // methodA, methodB, <init>
        assertEquals("Should have 3 vertices (2 methods + init)", 3, ccg.vertexCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        ClassCallNode nodeB = ccg.getNodeByMethodName("methodB()V");

        assertNotNull(nodeA);
        assertNotNull(nodeB);

        assertTrue("Edge methodA -> methodB should exist", ccg.containsEdge(nodeA, nodeB));
        assertTrue("Edge methodB -> methodA should exist", ccg.containsEdge(nodeB, nodeA));
    }

    static class SelfLoop {
        public void methodA() {
            methodA();
        }
    }

    @Test
    public void testSelfLoop() throws Exception {
        analyzeClass(SelfLoop.class);
        String className = SelfLoop.class.getName();
        ClassCallGraph ccg = new ClassCallGraph(getClass().getClassLoader(), className);

        // methodA, <init>
        assertEquals(2, ccg.vertexCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        assertNotNull(nodeA);

        assertTrue("Edge methodA -> methodA should exist", ccg.containsEdge(nodeA, nodeA));
    }

    static class DisconnectedCalls {
        public void methodA() {
            methodB();
        }
        public void methodB() {}

        public void methodC() {
            methodD();
        }
        public void methodD() {}
    }

    @Test
    public void testDisconnectedCalls() throws Exception {
        analyzeClass(DisconnectedCalls.class);
        String className = DisconnectedCalls.class.getName();
        ClassCallGraph ccg = new ClassCallGraph(getClass().getClassLoader(), className);

        // A, B, C, D, <init>
        assertEquals(5, ccg.vertexCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        ClassCallNode nodeB = ccg.getNodeByMethodName("methodB()V");
        ClassCallNode nodeC = ccg.getNodeByMethodName("methodC()V");
        ClassCallNode nodeD = ccg.getNodeByMethodName("methodD()V");

        assertNotNull(nodeA);
        assertNotNull(nodeB);
        assertNotNull(nodeC);
        assertNotNull(nodeD);

        assertTrue("Edge methodA -> methodB should exist", ccg.containsEdge(nodeA, nodeB));
        assertTrue("Edge methodC -> methodD should exist", ccg.containsEdge(nodeC, nodeD));

        // Check that there are no edges between the two components
        assertEquals("methodA should only call methodB", 1, ccg.outDegreeOf(nodeA));
        assertEquals("methodC should only call methodD", 1, ccg.outDegreeOf(nodeC));

        // Also ensure methods don't have unexpected incoming edges
        assertEquals("methodB should be called once", 1, ccg.inDegreeOf(nodeB));
        assertEquals("methodD should be called once", 1, ccg.inDegreeOf(nodeD));
    }
}
