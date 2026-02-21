package org.evosuite.graphs;

import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphPoolTest {

    @Test
    public void testGetInstance() {
        ClassLoader cl1 = new ClassLoader() {};
        ClassLoader cl2 = new ClassLoader() {};

        GraphPool pool1 = GraphPool.getInstance(cl1);
        GraphPool pool2 = GraphPool.getInstance(cl1);
        GraphPool pool3 = GraphPool.getInstance(cl2);

        assertNotNull(pool1);
        assertSame(pool1, pool2);
        assertNotSame(pool1, pool3);
    }

    @Test
    public void testRegisterAndRetrieve() {
        ClassLoader cl = new ClassLoader() {};
        GraphPool pool = GraphPool.getInstance(cl);

        String className = "com.example.TestClass";
        String methodName = "testMethod()V";

        RawControlFlowGraph cfg = new RawControlFlowGraph(cl, className, methodName, 1);
        pool.registerRawCFG(cfg);

        RawControlFlowGraph retrieved = pool.getRawCFG(className, methodName);
        assertSame(cfg, retrieved);

        pool.clear(className);
        assertNull(pool.getRawCFG(className, methodName));
    }
}
