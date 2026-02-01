package org.evosuite.graphs.ccfg;

import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.ccg.ClassCallGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClassControlFlowGraphTest {

    private ClassLoader classLoader;
    private GraphPool graphPool;
    private String className = "TestClass";

    @Before
    public void setUp() {
        classLoader = new ClassLoader() {};
        graphPool = GraphPool.getInstance(classLoader);
        graphPool.clear();
    }

    @Test
    public void testGraphConstruction() {
        // Setup mock CFGs
        String methodName1 = "m1";
        RawControlFlowGraph cfg1 = mock(RawControlFlowGraph.class);
        when(cfg1.getClassName()).thenReturn(className);
        when(cfg1.getMethodName()).thenReturn(methodName1);

        BytecodeInstruction entry1 = mockInstruction(methodName1, 0);
        when(cfg1.determineEntryPoint()).thenReturn(entry1);

        BytecodeInstruction exit1 = mockInstruction(methodName1, 1);
        when(cfg1.determineExitPoints()).thenReturn(Collections.singleton(exit1));

        when(cfg1.vertexSet()).thenReturn(new HashSet<>(Arrays.asList(entry1, exit1)));
        when(cfg1.edgeSet()).thenReturn(Collections.emptySet());
        when(cfg1.determineMethodCallsToOwnClass()).thenReturn(Collections.emptyList());

        // Register CFG
        graphPool.registerRawCFG(cfg1);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        assertNotNull(ccfg);
        // Verify frame nodes
        for (ClassControlFlowGraph.FrameNodeType type : ClassControlFlowGraph.FrameNodeType.values()) {
            assertNotNull(ccfg.getFrameNode(type));
        }
    }

    @Test
    public void testCCFGFrameNodeEquality() {
        CCFGFrameNode node1 = new CCFGFrameNode(ClassControlFlowGraph.FrameNodeType.ENTRY);
        CCFGFrameNode node2 = new CCFGFrameNode(ClassControlFlowGraph.FrameNodeType.ENTRY);
        CCFGFrameNode node3 = new CCFGFrameNode(ClassControlFlowGraph.FrameNodeType.EXIT);

        assertEquals(node1, node2);
        assertEquals(node1.hashCode(), node2.hashCode());
        assertNotEquals(node1, node3);
    }

    @Test
    public void testPurityAnalysisRecursion() {
        // Method A calls Method B
        // Method B calls Method A

        String methodA = "A";
        String methodB = "B";

        // CFG for A
        RawControlFlowGraph cfgA = mock(RawControlFlowGraph.class);
        when(cfgA.getClassName()).thenReturn(className);
        when(cfgA.getMethodName()).thenReturn(methodA);

        BytecodeInstruction entryA = mockInstruction(methodA, 0);
        BytecodeInstruction callB = mockInstruction(methodA, 1);
        when(callB.isMethodCall()).thenReturn(true);
        when(callB.getCalledMethod()).thenReturn(methodB);
        when(callB.getCalledMethodsClass()).thenReturn(className); // Same class
        when(callB.isMethodCallOnSameObject()).thenReturn(true);

        BytecodeInstruction exitA = mockInstruction(methodA, 2);

        when(cfgA.determineEntryPoint()).thenReturn(entryA);
        when(cfgA.determineExitPoints()).thenReturn(Collections.singleton(exitA));
        when(cfgA.vertexSet()).thenReturn(new HashSet<>(Arrays.asList(entryA, callB, exitA)));

        ControlFlowEdge edgeA1 = mock(ControlFlowEdge.class); // entry -> callB
        ControlFlowEdge edgeA2 = mock(ControlFlowEdge.class); // callB -> exitA

        when(cfgA.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(edgeA1, edgeA2)));
        when(cfgA.getEdgeSource(edgeA1)).thenReturn(entryA);
        when(cfgA.getEdgeTarget(edgeA1)).thenReturn(callB);
        when(cfgA.getEdgeSource(edgeA2)).thenReturn(callB);
        when(cfgA.getEdgeTarget(edgeA2)).thenReturn(exitA);

        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callB));


        // CFG for B
        RawControlFlowGraph cfgB = mock(RawControlFlowGraph.class);
        when(cfgB.getClassName()).thenReturn(className);
        when(cfgB.getMethodName()).thenReturn(methodB);

        BytecodeInstruction entryB = mockInstruction(methodB, 0);
        BytecodeInstruction callA = mockInstruction(methodB, 1);
        when(callA.isMethodCall()).thenReturn(true);
        when(callA.getCalledMethod()).thenReturn(methodA);
        when(callA.getCalledMethodsClass()).thenReturn(className);
        when(callA.isMethodCallOnSameObject()).thenReturn(true);

        BytecodeInstruction exitB = mockInstruction(methodB, 2);

        when(cfgB.determineEntryPoint()).thenReturn(entryB);
        when(cfgB.determineExitPoints()).thenReturn(Collections.singleton(exitB));
        when(cfgB.vertexSet()).thenReturn(new HashSet<>(Arrays.asList(entryB, callA, exitB)));

        ControlFlowEdge edgeB1 = mock(ControlFlowEdge.class);
        ControlFlowEdge edgeB2 = mock(ControlFlowEdge.class);

        when(cfgB.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(edgeB1, edgeB2)));
        when(cfgB.getEdgeSource(edgeB1)).thenReturn(entryB);
        when(cfgB.getEdgeTarget(edgeB1)).thenReturn(callA);
        when(cfgB.getEdgeSource(edgeB2)).thenReturn(callA);
        when(cfgB.getEdgeTarget(edgeB2)).thenReturn(exitB);

        when(cfgB.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callA));


        graphPool.registerRawCFG(cfgA);
        graphPool.registerRawCFG(cfgB);

        // Create CCG
        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        // Test isPure
        // Since we didn't add any impure instructions (field definitions), it should be pure.
        // We mocked edge iteration so ccfg construction should work.
        // However, isPure traverses the CCFG graph.
        // The CCFG graph is constructed by importing CFG nodes and edges.
        // In CCFG, call nodes are replaced.
        // So callB becomes a CCFGMethodCallNode and CCFGMethodReturnNode.

        assertTrue("Method A should be pure", ccfg.isPure(methodA));
        assertTrue("Method B should be pure", ccfg.isPure(methodB));
    }

    private BytecodeInstruction mockInstruction(String methodName, int id) {
        BytecodeInstruction ins = mock(BytecodeInstruction.class);
        when(ins.getMethodName()).thenReturn(methodName);
        when(ins.getInstructionId()).thenReturn(id);
        when(ins.toString()).thenReturn(methodName + "_" + id);
        when(ins.isMethodCallOfField()).thenReturn(false);
        when(ins.isMethodCall()).thenReturn(false);
        return ins;
    }
}
