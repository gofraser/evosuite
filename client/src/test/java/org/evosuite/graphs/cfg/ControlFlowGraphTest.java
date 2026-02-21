package org.evosuite.graphs.cfg;

import org.evosuite.graphs.GraphPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import static org.junit.jupiter.api.Assertions.*;

public class ControlFlowGraphTest {

    public static class TestClass {
        public int simpleIf(int x) {
            if (x > 0) {
                return 1;
            }
            return 0;
        }

        public int loop(int x) {
            int sum = 0;
            for (int i = 0; i < x; i++) {
                sum += i;
            }
            return sum;
        }

        public int switchCase(int x) {
            switch (x) {
                case 0: return 1;
                case 1: return 2;
                default: return 3;
            }
        }
    }

    @BeforeEach
    public void setUp() {
        GraphPool.clearAll();
    }

    @Test
    public void testSimpleIf() throws Exception {
        String className = TestClass.class.getName();
        ClassReader cr = new ClassReader(className);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(), new ClassWriter(ClassWriter.COMPUTE_MAXS), className);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);

        ActualControlFlowGraph cfg = GraphPool.getInstance(getClass().getClassLoader()).getActualCFG(className, "simpleIf(I)I");
        assertNotNull(cfg, "CFG should not be null for simpleIf");

        // Nodes:
        // 1. Entry
        // 2. if (x > 0)
        // 3. return 1
        // 4. return 0
        // 5. Exit
        assertEquals(5, cfg.vertexCount(), "Number of vertices");

        // Edges:
        // Entry -> if
        // if -> return 1 (True)
        // if -> return 0 (False)
        // return 1 -> Exit
        // return 0 -> Exit
        // Entry -> Exit (auxiliary edge added by ActualControlFlowGraph)
        assertEquals(6, cfg.edgeCount(), "Number of edges");

        // Cyclomatic complexity: E - N + 2 = 6 - 5 + 2 = 3.
        assertEquals(3, cfg.getCyclomaticComplexity(), "Cyclomatic complexity");
    }

    @Test
    public void testLoop() throws Exception {
        String className = TestClass.class.getName();
        ClassReader cr = new ClassReader(className);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(), new ClassWriter(ClassWriter.COMPUTE_MAXS), className);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);

        ActualControlFlowGraph cfg = GraphPool.getInstance(getClass().getClassLoader()).getActualCFG(className, "loop(I)I");
        assertNotNull(cfg, "CFG should not be null for loop");

        // Loop structure is more complex and depends on compilation (javac vs ecj etc),
        // but typically:
        // Entry -> Init -> Test
        // Test -> Body -> Incr -> Test
        // Test -> Return -> Exit

        // Vertices check: at least Entry, Exit, Test, Body, Return.
        assertTrue(cfg.vertexCount() >= 5, "Should have at least 5 vertices");
        assertTrue(cfg.edgeCount() >= 5, "Should have at least 5 edges");

        // Cyclomatic Complexity for a simple loop should be 2 + 1 (auxiliary edge).
        assertEquals(3, cfg.getCyclomaticComplexity(), "Cyclomatic complexity");
    }

    @Test
    public void testSwitchCase() throws Exception {
        String className = TestClass.class.getName();
        ClassReader cr = new ClassReader(className);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(), new ClassWriter(ClassWriter.COMPUTE_MAXS), className);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);

        ActualControlFlowGraph cfg = GraphPool.getInstance(getClass().getClassLoader()).getActualCFG(className, "switchCase(I)I");
        assertNotNull(cfg, "CFG should not be null for switchCase");

        // Entry -> switch
        // case 0 -> return 1 -> Exit
        // case 1 -> return 2 -> Exit
        // default -> return 3 -> Exit

        // Vertices: Entry, Switch, Ret1, Ret2, Ret3, Exit -> 6
        assertEquals(6, cfg.vertexCount(), "Number of vertices");

        // Edges:
        // Entry -> Switch (1)
        // Switch -> Ret1 (1)
        // Switch -> Ret2 (1)
        // Switch -> Ret3 (1)
        // Ret1 -> Exit (1)
        // Ret2 -> Exit (1)
        // Ret3 -> Exit (1)
        // Entry -> Exit (auxiliary edge)
        // Total: 8 edges.
        assertEquals(8, cfg.edgeCount(), "Number of edges");

        // Cyclomatic complexity: E - N + 2 = 8 - 6 + 2 = 4.
        assertEquals(4, cfg.getCyclomaticComplexity(), "Cyclomatic complexity");
    }

    @Test
    public void testFlagExample1() throws Exception {
        String className = "com.examples.with.different.packagename.FlagExample1";
        ClassReader cr = new ClassReader(className);
        CFGClassAdapter adapter = new CFGClassAdapter(getClass().getClassLoader(), new ClassWriter(ClassWriter.COMPUTE_MAXS), className);
        cr.accept(adapter, ClassReader.SKIP_FRAMES);

        ActualControlFlowGraph cfg = GraphPool.getInstance(getClass().getClassLoader()).getActualCFG(className, "testMe(I)Z");
        assertNotNull(cfg, "CFG should not be null for FlagExample1");

        // Assert basic properties
        assertTrue(cfg.vertexCount() > 0, "Vertex count should be positive");
        assertTrue(cfg.edgeCount() > 0, "Edge count should be positive");

        // For simple boolean flag logic, we expect at least an If and Returns.
        // Entry, Exit, If, Return(s).
        assertTrue(cfg.vertexCount() >= 4, "Should have at least 4 vertices");
    }
}
