package org.evosuite.graphs.ccfg;

import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.ccg.ClassCallGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClassControlFlowGraphExtendedTest {

    private ClassLoader classLoader;
    private GraphPool graphPool;
    private String className = "TestClass";

    @BeforeEach
    public void setUp() {
        classLoader = new ClassLoader() {};
        graphPool = GraphPool.getInstance(classLoader);
        graphPool.clear();
    }

    private BytecodeInstruction mockInstruction(String methodName, int id, boolean isMethodCall, String calledMethod, String calledClass, boolean isStatic, boolean isSameObject) {
        BytecodeInstruction ins = mock(BytecodeInstruction.class);
        when(ins.getMethodName()).thenReturn(methodName);
        when(ins.getInstructionId()).thenReturn(id);
        when(ins.toString()).thenReturn(methodName + "_" + id);
        when(ins.isMethodCall()).thenReturn(isMethodCall);
        if (isMethodCall) {
            when(ins.getCalledMethod()).thenReturn(calledMethod);
            when(ins.getCalledMethodsClass()).thenReturn(calledClass);
            when(ins.isCallToStaticMethod()).thenReturn(isStatic);
            when(ins.isMethodCallOnSameObject()).thenReturn(isSameObject);
        } else {
            when(ins.getCalledMethod()).thenReturn(null);
            when(ins.getCalledMethodsClass()).thenReturn(null);
            when(ins.isCallToStaticMethod()).thenReturn(false);
            when(ins.isMethodCallOnSameObject()).thenReturn(false);
        }
        when(ins.isMethodCallOfField()).thenReturn(false);
        return ins;
    }

    private RawControlFlowGraph mockCFG(String methodName, boolean isPublic, boolean isStatic) {
        RawControlFlowGraph cfg = mock(RawControlFlowGraph.class);
        when(cfg.getClassName()).thenReturn(className);
        when(cfg.getMethodName()).thenReturn(methodName);
        when(cfg.isPublicMethod()).thenReturn(isPublic);
        when(cfg.isStaticMethod()).thenReturn(isStatic);

        BytecodeInstruction entry = mockInstruction(methodName, 0, false, null, null, false, false);
        BytecodeInstruction exit = mockInstruction(methodName, 100, false, null, null, false, false);

        when(cfg.determineEntryPoint()).thenReturn(entry);
        when(cfg.determineExitPoints()).thenReturn(Collections.singleton(exit));

        Set<BytecodeInstruction> vertices = new HashSet<>();
        vertices.add(entry);
        vertices.add(exit);
        when(cfg.vertexSet()).thenReturn(vertices);

        // Default: entry -> exit
        ControlFlowEdge e = mock(ControlFlowEdge.class);
        when(cfg.edgeSet()).thenReturn(Collections.singleton(e));
        when(cfg.getEdgeSource(e)).thenReturn(entry);
        when(cfg.getEdgeTarget(e)).thenReturn(exit);

        when(cfg.determineMethodCallsToOwnClass()).thenReturn(Collections.emptyList());

        return cfg;
    }

    @Test
    public void testSimpleMethodCall() {
        String methodA = "A()V";
        String methodB = "B()V";

        // Method A calls B
        RawControlFlowGraph cfgA = mockCFG(methodA, true, false);
        BytecodeInstruction callB = mockInstruction(methodA, 1, true, methodB, className, false, true);

        // Add call instruction to CFG A
        Set<BytecodeInstruction> verticesA = new HashSet<>(cfgA.vertexSet());
        verticesA.add(callB);
        when(cfgA.vertexSet()).thenReturn(verticesA);

        // Mock edges in A: entry -> callB -> exit
        ControlFlowEdge e1 = mock(ControlFlowEdge.class);
        ControlFlowEdge e2 = mock(ControlFlowEdge.class);
        when(cfgA.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(e1, e2)));

        BytecodeInstruction entryA = cfgA.determineEntryPoint();
        BytecodeInstruction exitA = cfgA.determineExitPoints().iterator().next();

        when(cfgA.getEdgeSource(e1)).thenReturn(entryA);
        when(cfgA.getEdgeTarget(e1)).thenReturn(callB);
        when(cfgA.getEdgeSource(e2)).thenReturn(callB);
        when(cfgA.getEdgeTarget(e2)).thenReturn(exitA);

        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callB));

        // Method B
        RawControlFlowGraph cfgB = mockCFG(methodB, true, false);

        graphPool.registerRawCFG(cfgA);
        graphPool.registerRawCFG(cfgB);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        // Assertions
        assertNotNull(ccfg);

        // Verify nodes for method A and B exist
        // But nodes are wrapped in CCFGMethodEntry/Exit/Call/Return

        // Check frame edges
        CCFGFrameNode callFrame = ccfg.getFrameNode(ClassControlFlowGraph.FrameNodeType.CALL);
        Set<CCFGNode> calledFromFrame = ccfg.getChildren(callFrame);
        // A and B are public, so both should be reachable from CALL
        assertTrue(calledFromFrame.stream().anyMatch(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodA)), "A should be reachable from Frame CALL");
        assertTrue(calledFromFrame.stream().anyMatch(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodB)), "B should be reachable from Frame CALL");

        // Check A calls B
        // We expect: Call(B) -> Entry(B)
        // Find Call node for B in A
        // We can traverse from Entry(A) -> Call(B) -> Entry(B)

        // Find Entry(A)
        CCFGMethodEntryNode entryNodeA = (CCFGMethodEntryNode) calledFromFrame.stream().filter(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodA)).findFirst().orElse(null);
        assertNotNull(entryNodeA);

        // Entry(A) has one child: entry instruction of A
        CCFGNode instructionA = ccfg.getSingleChild(entryNodeA);
        assertNotNull(instructionA);

        // InstructionA -> CallNode(B)
        CCFGNode callNodeB = ccfg.getSingleChild(instructionA);
        assertTrue(callNodeB instanceof CCFGMethodCallNode);
        assertEquals(methodB, ((CCFGMethodCallNode)callNodeB).getCalledMethod());

        // CallNode(B) -> Entry(B)
        CCFGNode entryNodeB_target = ccfg.getChildren(callNodeB).stream().filter(n -> n instanceof CCFGMethodEntryNode).findFirst().orElse(null);
        assertNotNull(entryNodeB_target);
        assertEquals(methodB, ((CCFGMethodEntryNode)entryNodeB_target).getMethod());

        // Entry(B) -> instructionB -> Exit(B) -> ReturnNode(B) -> Exit(A) -> ReturnFrame

        // Check ReturnNode(B)
        CCFGMethodReturnNode returnNodeB = ((CCFGMethodCallNode)callNodeB).getReturnNode();
        assertNotNull(returnNodeB);

        // Exit(B) should point to ReturnNode(B)
        // Find Exit(B)
        // We can find Exit(B) via methodExits map if we had access, but we don't.
        // Traverse from Entry(B) -> instructionB (entry) -> instructionExit (exit) -> Exit(B)
        CCFGNode entryNodeB = entryNodeB_target; // Assuming entryNodeB_target IS the entry node
        CCFGNode instructionB = ccfg.getSingleChild(entryNodeB); // CCFG(entry)
        CCFGNode instructionExitB = ccfg.getSingleChild(instructionB); // CCFG(exit)
        CCFGNode exitNodeB = ccfg.getSingleChild(instructionExitB); // CCFGMethodExitNode

        assertTrue(exitNodeB instanceof CCFGMethodExitNode);
        assertEquals(methodB, ((CCFGMethodExitNode)exitNodeB).getMethod());

        // Exit(B) -> ReturnNode(B)
        assertTrue(ccfg.getChildren(exitNodeB).contains(returnNodeB));
    }

    @Test
    public void testPrivateMethodCall() {
        String methodA = "A()V";
        String methodB = "B()V";

        // Method A (public) calls B (private)
        RawControlFlowGraph cfgA = mockCFG(methodA, true, false);
        BytecodeInstruction callB = mockInstruction(methodA, 1, true, methodB, className, false, true);

        // Setup A
        Set<BytecodeInstruction> verticesA = new HashSet<>(cfgA.vertexSet());
        verticesA.add(callB);
        when(cfgA.vertexSet()).thenReturn(verticesA);
        ControlFlowEdge e1 = mock(ControlFlowEdge.class);
        ControlFlowEdge e2 = mock(ControlFlowEdge.class);
        when(cfgA.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(e1, e2)));
        BytecodeInstruction entryA = cfgA.determineEntryPoint();
        BytecodeInstruction exitA = cfgA.determineExitPoints().iterator().next();
        when(cfgA.getEdgeSource(e1)).thenReturn(entryA);
        when(cfgA.getEdgeTarget(e1)).thenReturn(callB);
        when(cfgA.getEdgeSource(e2)).thenReturn(callB);
        when(cfgA.getEdgeTarget(e2)).thenReturn(exitA);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callB));

        // Method B (private)
        RawControlFlowGraph cfgB = mockCFG(methodB, false, false);

        graphPool.registerRawCFG(cfgA);
        graphPool.registerRawCFG(cfgB);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        // Assertions
        CCFGFrameNode callFrame = ccfg.getFrameNode(ClassControlFlowGraph.FrameNodeType.CALL);
        Set<CCFGNode> calledFromFrame = ccfg.getChildren(callFrame);

        // A is public, should be reachable
        assertTrue(calledFromFrame.stream().anyMatch(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodA)), "A should be reachable from Frame CALL");

        // B is private, should NOT be reachable from Frame CALL
        assertFalse(calledFromFrame.stream().anyMatch(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodB)), "B should NOT be reachable from Frame CALL");

        // But B should be reachable from A
        CCFGMethodEntryNode entryNodeA = (CCFGMethodEntryNode) calledFromFrame.stream().filter(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodA)).findFirst().orElse(null);
        CCFGNode instructionA = ccfg.getSingleChild(entryNodeA);
        CCFGNode callNodeB = ccfg.getSingleChild(instructionA);
        assertTrue(callNodeB instanceof CCFGMethodCallNode);

        CCFGNode entryNodeB_target = ccfg.getChildren(callNodeB).stream().filter(n -> n instanceof CCFGMethodEntryNode).findFirst().orElse(null);
        assertNotNull(entryNodeB_target, "Entry(B) should be reachable from Call(B)");
        assertEquals(methodB, ((CCFGMethodEntryNode)entryNodeB_target).getMethod());
    }

    @Test
    public void testSelfRecursion() {
        String methodA = "A()V";

        // Method A calls A
        RawControlFlowGraph cfgA = mockCFG(methodA, true, false);
        BytecodeInstruction callA = mockInstruction(methodA, 1, true, methodA, className, false, true);

        // Setup A
        Set<BytecodeInstruction> verticesA = new HashSet<>(cfgA.vertexSet());
        verticesA.add(callA);
        when(cfgA.vertexSet()).thenReturn(verticesA);
        ControlFlowEdge e1 = mock(ControlFlowEdge.class); // entry -> callA
        ControlFlowEdge e2 = mock(ControlFlowEdge.class); // callA -> exit
        when(cfgA.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(e1, e2)));
        BytecodeInstruction entryA = cfgA.determineEntryPoint();
        BytecodeInstruction exitA = cfgA.determineExitPoints().iterator().next();
        when(cfgA.getEdgeSource(e1)).thenReturn(entryA);
        when(cfgA.getEdgeTarget(e1)).thenReturn(callA);
        when(cfgA.getEdgeSource(e2)).thenReturn(callA);
        when(cfgA.getEdgeTarget(e2)).thenReturn(exitA);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callA));

        graphPool.registerRawCFG(cfgA);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        // Verify recursion
        CCFGFrameNode callFrame = ccfg.getFrameNode(ClassControlFlowGraph.FrameNodeType.CALL);
        CCFGMethodEntryNode entryNodeA = (CCFGMethodEntryNode) ccfg.getChildren(callFrame).stream().filter(n -> n instanceof CCFGMethodEntryNode).findFirst().orElse(null);

        CCFGNode instructionA = ccfg.getSingleChild(entryNodeA);
        CCFGNode callNodeA = ccfg.getSingleChild(instructionA);
        assertTrue(callNodeA instanceof CCFGMethodCallNode);

        // Call(A) should point to Entry(A)
        assertTrue(ccfg.getChildren(callNodeA).contains(entryNodeA), "Call(A) should point to Entry(A)");
    }

    @Test
    public void testStaticMethodCall() {
        String methodA = "A()V";
        String methodB = "staticB()V";

        // Method A calls static B
        RawControlFlowGraph cfgA = mockCFG(methodA, true, false);
        BytecodeInstruction callB = mockInstruction(methodA, 1, true, methodB, className, true, false); // isStatic=true, isSameObject=false

        // Setup A
        Set<BytecodeInstruction> verticesA = new HashSet<>(cfgA.vertexSet());
        verticesA.add(callB);
        when(cfgA.vertexSet()).thenReturn(verticesA);
        ControlFlowEdge e1 = mock(ControlFlowEdge.class);
        ControlFlowEdge e2 = mock(ControlFlowEdge.class);
        when(cfgA.edgeSet()).thenReturn(new HashSet<>(Arrays.asList(e1, e2)));
        BytecodeInstruction entryA = cfgA.determineEntryPoint();
        BytecodeInstruction exitA = cfgA.determineExitPoints().iterator().next();
        when(cfgA.getEdgeSource(e1)).thenReturn(entryA);
        when(cfgA.getEdgeTarget(e1)).thenReturn(callB);
        when(cfgA.getEdgeSource(e2)).thenReturn(callB);
        when(cfgA.getEdgeTarget(e2)).thenReturn(exitA);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callB));

        // Method B (static)
        RawControlFlowGraph cfgB = mockCFG(methodB, true, true);

        graphPool.registerRawCFG(cfgA);
        graphPool.registerRawCFG(cfgB);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);

        // Verify A calls B
        CCFGFrameNode callFrame = ccfg.getFrameNode(ClassControlFlowGraph.FrameNodeType.CALL);
        CCFGMethodEntryNode entryNodeA = (CCFGMethodEntryNode) ccfg.getChildren(callFrame).stream().filter(n -> n instanceof CCFGMethodEntryNode && ((CCFGMethodEntryNode)n).getMethod().equals(methodA)).findFirst().orElse(null);

        CCFGNode instructionA = ccfg.getSingleChild(entryNodeA);
        CCFGNode callNodeB = ccfg.getSingleChild(instructionA);
        assertTrue(callNodeB instanceof CCFGMethodCallNode);

        // Call(B) should point to Entry(B)
        CCFGMethodEntryNode entryNodeB = (CCFGMethodEntryNode) ccfg.getChildren(callNodeB).stream().filter(n -> n instanceof CCFGMethodEntryNode).findFirst().orElse(null);
        assertNotNull(entryNodeB);
        assertEquals(methodB, entryNodeB.getMethod());
    }
}
