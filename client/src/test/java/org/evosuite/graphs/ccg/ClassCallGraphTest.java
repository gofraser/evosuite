/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.graphs.ccg;

import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClassCallGraphTest {

    private final String className = "com.example.TestClass";
    private final ClassLoader classLoader = getClass().getClassLoader();

    @Before
    public void setUp() {
        GraphPool.getInstance(classLoader).clear();
    }

    @After
    public void tearDown() {
        GraphPool.getInstance(classLoader).clear();
    }

    @Test
    public void testComputeClassCallGraph() {
        // Setup methods
        String methodA = "methodA()V";
        String methodB = "methodB()V";
        String methodC = "methodC()V";

        // Mock RawCFGs
        RawControlFlowGraph cfgA = mock(RawControlFlowGraph.class);
        RawControlFlowGraph cfgB = mock(RawControlFlowGraph.class);
        RawControlFlowGraph cfgC = mock(RawControlFlowGraph.class);

        when(cfgA.getClassName()).thenReturn(className);
        when(cfgA.getMethodName()).thenReturn(methodA);
        when(cfgB.getClassName()).thenReturn(className);
        when(cfgB.getMethodName()).thenReturn(methodB);
        when(cfgC.getClassName()).thenReturn(className);
        when(cfgC.getMethodName()).thenReturn(methodC);

        // Register mocks to GraphPool
        GraphPool pool = GraphPool.getInstance(classLoader);
        pool.registerRawCFG(cfgA);
        pool.registerRawCFG(cfgB);
        pool.registerRawCFG(cfgC);

        // Setup calls
        // A -> B
        BytecodeInstruction callToB = mock(BytecodeInstruction.class);
        when(callToB.getCalledMethod()).thenReturn(methodB);
        when(callToB.getCalledMethodsClass()).thenReturn(className);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callToB));

        // B -> C
        BytecodeInstruction callToC = mock(BytecodeInstruction.class);
        when(callToC.getCalledMethod()).thenReturn(methodC);
        when(callToC.getCalledMethodsClass()).thenReturn(className);
        when(cfgB.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callToC));

        // C -> A (Cycle)
        BytecodeInstruction callToA = mock(BytecodeInstruction.class);
        when(callToA.getCalledMethod()).thenReturn(methodA);
        when(callToA.getCalledMethodsClass()).thenReturn(className);
        when(cfgC.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callToA));

        // Create ClassCallGraph
        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);

        // Verify Nodes
        assertEquals(3, ccg.vertexCount());
        assertTrue(ccg.containsVertex(new ClassCallNode(methodA)));
        assertTrue(ccg.containsVertex(new ClassCallNode(methodB)));
        assertTrue(ccg.containsVertex(new ClassCallNode(methodC)));

        // Verify Edges
        assertEquals(3, ccg.edgeCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName(methodA);
        ClassCallNode nodeB = ccg.getNodeByMethodName(methodB);
        ClassCallNode nodeC = ccg.getNodeByMethodName(methodC);

        assertNotNull(nodeA);
        assertNotNull(nodeB);
        assertNotNull(nodeC);

        assertTrue(ccg.containsEdge(nodeA, nodeB));
        assertTrue(ccg.containsEdge(nodeB, nodeC));
        assertTrue(ccg.containsEdge(nodeC, nodeA));
    }

    @Test
    public void testComputeClassCallGraphWithSelfLoop() {
        // Setup methods
        String methodA = "methodA()V";

        // Mock RawCFGs
        RawControlFlowGraph cfgA = mock(RawControlFlowGraph.class);

        when(cfgA.getClassName()).thenReturn(className);
        when(cfgA.getMethodName()).thenReturn(methodA);

        // Register mocks to GraphPool
        GraphPool pool = GraphPool.getInstance(classLoader);
        pool.registerRawCFG(cfgA);

        // Setup calls
        // A -> A
        BytecodeInstruction callToA = mock(BytecodeInstruction.class);
        when(callToA.getCalledMethod()).thenReturn(methodA);
        when(callToA.getCalledMethodsClass()).thenReturn(className);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.singletonList(callToA));

        // Create ClassCallGraph
        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);

        // Verify Nodes
        assertEquals(1, ccg.vertexCount());

        // Verify Edges
        assertEquals(1, ccg.edgeCount());

        ClassCallNode nodeA = ccg.getNodeByMethodName(methodA);
        assertTrue(ccg.containsEdge(nodeA, nodeA));
    }

    @Test
    public void testDisconnectedGraph() {
        String methodA = "methodA()V";
        String methodB = "methodB()V";

        RawControlFlowGraph cfgA = mock(RawControlFlowGraph.class);
        RawControlFlowGraph cfgB = mock(RawControlFlowGraph.class);

        when(cfgA.getClassName()).thenReturn(className);
        when(cfgA.getMethodName()).thenReturn(methodA);
        when(cfgB.getClassName()).thenReturn(className);
        when(cfgB.getMethodName()).thenReturn(methodB);
        when(cfgA.determineMethodCallsToOwnClass()).thenReturn(Collections.emptyList());
        when(cfgB.determineMethodCallsToOwnClass()).thenReturn(Collections.emptyList());

        GraphPool pool = GraphPool.getInstance(classLoader);
        pool.registerRawCFG(cfgA);
        pool.registerRawCFG(cfgB);

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);

        assertEquals(2, ccg.vertexCount());
        assertEquals(0, ccg.edgeCount());
    }
}
