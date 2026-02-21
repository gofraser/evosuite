package org.evosuite.graphs.ccg;

import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.CFGClassAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static org.junit.jupiter.api.Assertions.*;

public class ClassCallGraphSystemTest {

    @BeforeEach
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
        assertEquals(4, ccg.vertexCount(), "Should have 4 vertices (3 methods + init)");

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        ClassCallNode nodeB = ccg.getNodeByMethodName("methodB()V");
        ClassCallNode nodeC = ccg.getNodeByMethodName("methodC()V");

        assertNotNull(nodeA, "methodA should be in the graph");
        assertNotNull(nodeB, "methodB should be in the graph");
        assertNotNull(nodeC, "methodC should be in the graph");

        assertTrue(ccg.containsEdge(nodeA, nodeB), "Edge methodA -> methodB should exist");
        assertTrue(ccg.containsEdge(nodeB, nodeC), "Edge methodB -> methodC should exist");
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
        assertEquals(3, ccg.vertexCount(), "Should have 3 vertices (2 methods + init)");

        ClassCallNode nodeA = ccg.getNodeByMethodName("methodA()V");
        ClassCallNode nodeB = ccg.getNodeByMethodName("methodB()V");

        assertNotNull(nodeA);
        assertNotNull(nodeB);

        assertTrue(ccg.containsEdge(nodeA, nodeB), "Edge methodA -> methodB should exist");
        assertTrue(ccg.containsEdge(nodeB, nodeA), "Edge methodB -> methodA should exist");
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

        assertTrue(ccg.containsEdge(nodeA, nodeA), "Edge methodA -> methodA should exist");
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

        assertTrue(ccg.containsEdge(nodeA, nodeB), "Edge methodA -> methodB should exist");
        assertTrue(ccg.containsEdge(nodeC, nodeD), "Edge methodC -> methodD should exist");

        // Check that there are no edges between the two components
        assertEquals(1, ccg.outDegreeOf(nodeA), "methodA should only call methodB");
        assertEquals(1, ccg.outDegreeOf(nodeC), "methodC should only call methodD");

        // Also ensure methods don't have unexpected incoming edges
        assertEquals(1, ccg.inDegreeOf(nodeB), "methodB should be called once");
        assertEquals(1, ccg.inDegreeOf(nodeD), "methodD should be called once");
    }
}
