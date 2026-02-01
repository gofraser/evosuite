package org.evosuite.graphs.cdg;

import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ControlDependenceGraphTest {

    @Mock
    ActualControlFlowGraph cfg;

    @Mock
    ActualControlFlowGraph reverseCfg;

    @Test
    public void testConstruction() {
        when(cfg.getClassName()).thenReturn("TestClass");
        when(cfg.getMethodName()).thenReturn("testMethod");
        when(cfg.computeReverseCFG()).thenReturn(reverseCfg);

        // Setup reverse CFG for DominatorTree computation
        // Create a simple reverse graph: Exit(1) -> Entry(0)
        // Which corresponds to Entry -> Exit in forward graph.

        BasicBlock entry = mock(BasicBlock.class);
        BasicBlock exit = mock(BasicBlock.class);
        when(entry.getName()).thenReturn("Entry");
        when(exit.getName()).thenReturn("Exit");

        Set<BasicBlock> vertices = new HashSet<>();
        vertices.add(entry);
        vertices.add(exit);

        when(reverseCfg.vertexSet()).thenReturn(vertices);
        when(reverseCfg.determineEntryPoint()).thenReturn(exit); // In reverse, exit is entry
        when(reverseCfg.getChildren(exit)).thenReturn(Collections.singleton(entry));
        when(reverseCfg.getChildren(entry)).thenReturn(Collections.emptySet());
        when(reverseCfg.getParents(entry)).thenReturn(Collections.singleton(exit));
        when(reverseCfg.getParents(exit)).thenReturn(Collections.emptySet());

        // Mocking CFG structure as well, for createGraphNodes
        when(cfg.vertexSet()).thenReturn(vertices);
        when(exit.isExitBlock()).thenReturn(true);
        when(entry.isExitBlock()).thenReturn(false);

        ControlDependenceGraph cdg = new ControlDependenceGraph(cfg);

        assertNotNull(cdg);
        assertEquals("TestClass", cdg.getClassName());
        assertEquals("testMethod", cdg.getMethodName());
    }
}
