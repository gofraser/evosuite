package org.evosuite.graphs.cdg;

import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.ControlFlowEdge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        // Avoid CDG warnings by providing a minimal outgoing edge set for Exit
        ControlFlowEdge exitEdge1 = new ControlFlowEdge();
        ControlFlowEdge exitEdge2 = new ControlFlowEdge();
        when(cfg.outgoingEdgesOf(exit)).thenReturn(new LinkedHashSet<>(Arrays.asList(exitEdge1, exitEdge2)));

        ControlDependenceGraph cdg = new ControlDependenceGraph(cfg);

        assertNotNull(cdg);
        assertEquals("TestClass", cdg.getClassName());
        assertEquals("testMethod", cdg.getMethodName());
    }

    /**
     * Tests an if-then-else CDG:
     *
     * Forward CFG:
     *   Entry -> Branch -> ThenBlock
     *                   -> ElseBlock
     *   ThenBlock -> Join
     *   ElseBlock -> Join
     *   Join -> Exit
     *
     * Reverse CFG (for post-dominator tree):
     *   Exit -> Join -> ThenBlock -> Branch -> Entry
     *                -> ElseBlock -> Branch
     *
     * Expected control dependencies:
     *   ThenBlock is control dependent on Branch
     *   ElseBlock is control dependent on Branch
     *   Entry and Join are root-dependent (adjacent to entry in CDG)
     */
    @Test
    public void testIfThenElseCDG() {
        BasicBlock entry = mock(BasicBlock.class, "Entry");
        BasicBlock branch = mock(BasicBlock.class, "Branch");
        BasicBlock thenBlock = mock(BasicBlock.class, "Then");
        BasicBlock elseBlock = mock(BasicBlock.class, "Else");
        BasicBlock join = mock(BasicBlock.class, "Join");
        BasicBlock exit = mock(BasicBlock.class, "Exit");

        when(entry.getName()).thenReturn("Entry");
        when(branch.getName()).thenReturn("Branch");
        when(thenBlock.getName()).thenReturn("Then");
        when(elseBlock.getName()).thenReturn("Else");
        when(join.getName()).thenReturn("Join");
        when(exit.getName()).thenReturn("Exit");

        when(entry.isExitBlock()).thenReturn(false);
        when(branch.isExitBlock()).thenReturn(false);
        when(thenBlock.isExitBlock()).thenReturn(false);
        when(elseBlock.isExitBlock()).thenReturn(false);
        when(join.isExitBlock()).thenReturn(false);
        when(exit.isExitBlock()).thenReturn(true);

        when(entry.isEntryBlock()).thenReturn(true);
        when(branch.isEntryBlock()).thenReturn(false);
        when(thenBlock.isEntryBlock()).thenReturn(false);
        when(elseBlock.isEntryBlock()).thenReturn(false);
        when(join.isEntryBlock()).thenReturn(false);
        when(exit.isEntryBlock()).thenReturn(false);

        // Forward CFG edges
        ControlFlowEdge edgeBranchThen = new ControlFlowEdge();
        ControlFlowEdge edgeBranchElse = new ControlFlowEdge();

        Set<BasicBlock> allVertices = new LinkedHashSet<>(Arrays.asList(
                entry, branch, thenBlock, elseBlock, join, exit));

        when(cfg.getClassName()).thenReturn("TestClass");
        when(cfg.getMethodName()).thenReturn("testMethod");
        when(cfg.vertexSet()).thenReturn(allVertices);
        when(cfg.computeReverseCFG()).thenReturn(reverseCfg);

        // Forward cfg.getEdge: used for direct edges in computeControlDependence
        when(cfg.getEdge(branch, thenBlock)).thenReturn(edgeBranchThen);
        when(cfg.getEdge(branch, elseBlock)).thenReturn(edgeBranchElse);
        // Avoid CDG warnings by providing a minimal outgoing edge set for Exit
        ControlFlowEdge exitEdge1 = new ControlFlowEdge();
        ControlFlowEdge exitEdge2 = new ControlFlowEdge();
        when(cfg.outgoingEdgesOf(exit)).thenReturn(new LinkedHashSet<>(Arrays.asList(exitEdge1, exitEdge2)));

        // Reverse CFG structure (all forward edges reversed)
        // In reverse: exit is the entry point
        // Exit -> Join, Join -> ThenBlock, Join -> ElseBlock
        // ThenBlock -> Branch, ElseBlock -> Branch
        // Branch -> Entry
        // Entry -> Exit (reverse of forward Entry->Exit auxiliary edge)
        when(reverseCfg.vertexSet()).thenReturn(allVertices);
        when(reverseCfg.determineEntryPoint()).thenReturn(exit);
        when(reverseCfg.getClassName()).thenReturn("TestClass");
        when(reverseCfg.getMethodName()).thenReturn("testMethod");
        when(reverseCfg.getName()).thenReturn("ReverseCFG");

        // Reverse children (successors in reversed graph)
        when(reverseCfg.getChildren(exit)).thenReturn(new LinkedHashSet<>(Arrays.asList(join, entry)));
        when(reverseCfg.getChildren(join)).thenReturn(new LinkedHashSet<>(Arrays.asList(thenBlock, elseBlock)));
        when(reverseCfg.getChildren(thenBlock)).thenReturn(Collections.singleton(branch));
        when(reverseCfg.getChildren(elseBlock)).thenReturn(Collections.singleton(branch));
        when(reverseCfg.getChildren(branch)).thenReturn(Collections.singleton(entry));
        when(reverseCfg.getChildren(entry)).thenReturn(Collections.singleton(exit));

        // Reverse parents
        when(reverseCfg.getParents(exit)).thenReturn(Collections.singleton(entry));
        when(reverseCfg.getParents(join)).thenReturn(Collections.singleton(exit));
        when(reverseCfg.getParents(thenBlock)).thenReturn(Collections.singleton(join));
        when(reverseCfg.getParents(elseBlock)).thenReturn(Collections.singleton(join));
        when(reverseCfg.getParents(branch)).thenReturn(new LinkedHashSet<>(Arrays.asList(thenBlock, elseBlock)));
        when(reverseCfg.getParents(entry)).thenReturn(Collections.singleton(branch));

        ControlDependenceGraph cdg = new ControlDependenceGraph(cfg);

        assertNotNull(cdg);
        // CDG should contain all blocks except exit
        assertTrue(cdg.containsVertex(entry));
        assertTrue(cdg.containsVertex(branch));
        assertTrue(cdg.containsVertex(thenBlock));
        assertTrue(cdg.containsVertex(elseBlock));
        assertTrue(cdg.containsVertex(join));
        assertFalse(cdg.containsVertex(exit));

        // ThenBlock and ElseBlock should have incoming edges from Branch
        assertTrue(cdg.containsEdge(branch, thenBlock));
        assertTrue(cdg.containsEdge(branch, elseBlock));
    }

    /**
     * Tests two sequential branches in a CDG:
     *
     * Forward CFG:
     *   Entry -> B1 -> T1 -> J1 -> B2 -> T2 -> J2 -> Exit
     *                -> F1 ->          -> F2 ->
     *
     * Expected: T1,F1 depend on B1; T2,F2 depend on B2;
     *           B1 and B2 are independent of each other.
     */
    @Test
    public void testSequentialBranchesCDG() {
        BasicBlock entry = mock(BasicBlock.class, "Entry");
        BasicBlock b1 = mock(BasicBlock.class, "B1");
        BasicBlock t1 = mock(BasicBlock.class, "T1");
        BasicBlock f1 = mock(BasicBlock.class, "F1");
        BasicBlock j1 = mock(BasicBlock.class, "J1");
        BasicBlock b2 = mock(BasicBlock.class, "B2");
        BasicBlock t2 = mock(BasicBlock.class, "T2");
        BasicBlock f2 = mock(BasicBlock.class, "F2");
        BasicBlock j2 = mock(BasicBlock.class, "J2");
        BasicBlock exit = mock(BasicBlock.class, "Exit");

        for (Object[] pair : new Object[][]{
                {entry, "Entry", false, true},
                {b1, "B1", false, false},
                {t1, "T1", false, false},
                {f1, "F1", false, false},
                {j1, "J1", false, false},
                {b2, "B2", false, false},
                {t2, "T2", false, false},
                {f2, "F2", false, false},
                {j2, "J2", false, false},
                {exit, "Exit", true, false}
        }) {
            BasicBlock bb = (BasicBlock) pair[0];
            when(bb.getName()).thenReturn((String) pair[1]);
            when(bb.isExitBlock()).thenReturn((Boolean) pair[2]);
            when(bb.isEntryBlock()).thenReturn((Boolean) pair[3]);
        }

        ControlFlowEdge edgeB1T1 = new ControlFlowEdge();
        ControlFlowEdge edgeB1F1 = new ControlFlowEdge();
        ControlFlowEdge edgeB2T2 = new ControlFlowEdge();
        ControlFlowEdge edgeB2F2 = new ControlFlowEdge();

        Set<BasicBlock> allVertices = new LinkedHashSet<>(Arrays.asList(
                entry, b1, t1, f1, j1, b2, t2, f2, j2, exit));

        when(cfg.getClassName()).thenReturn("TestClass");
        when(cfg.getMethodName()).thenReturn("testMethod");
        when(cfg.vertexSet()).thenReturn(allVertices);
        when(cfg.computeReverseCFG()).thenReturn(reverseCfg);

        when(cfg.getEdge(b1, t1)).thenReturn(edgeB1T1);
        when(cfg.getEdge(b1, f1)).thenReturn(edgeB1F1);
        when(cfg.getEdge(b2, t2)).thenReturn(edgeB2T2);
        when(cfg.getEdge(b2, f2)).thenReturn(edgeB2F2);
        // Avoid CDG warnings by providing a minimal outgoing edge set for Exit
        ControlFlowEdge exitEdge1 = new ControlFlowEdge();
        ControlFlowEdge exitEdge2 = new ControlFlowEdge();
        when(cfg.outgoingEdgesOf(exit)).thenReturn(new LinkedHashSet<>(Arrays.asList(exitEdge1, exitEdge2)));

        // Reverse CFG: all edges reversed
        // Forward: Entry->B1, B1->T1, B1->F1, T1->J1, F1->J1, J1->B2,
        //          B2->T2, B2->F2, T2->J2, F2->J2, J2->Exit, Entry->Exit
        // Reverse: Exit->J2, J2->T2, J2->F2, T2->B2, F2->B2, B2->J1,
        //          J1->T1, J1->F1, T1->B1, F1->B1, B1->Entry, Exit->Entry
        when(reverseCfg.vertexSet()).thenReturn(allVertices);
        when(reverseCfg.determineEntryPoint()).thenReturn(exit);
        when(reverseCfg.getClassName()).thenReturn("TestClass");
        when(reverseCfg.getMethodName()).thenReturn("testMethod");
        when(reverseCfg.getName()).thenReturn("ReverseCFG");

        when(reverseCfg.getChildren(exit)).thenReturn(new LinkedHashSet<>(Arrays.asList(j2, entry)));
        when(reverseCfg.getChildren(j2)).thenReturn(new LinkedHashSet<>(Arrays.asList(t2, f2)));
        when(reverseCfg.getChildren(t2)).thenReturn(Collections.singleton(b2));
        when(reverseCfg.getChildren(f2)).thenReturn(Collections.singleton(b2));
        when(reverseCfg.getChildren(b2)).thenReturn(Collections.singleton(j1));
        when(reverseCfg.getChildren(j1)).thenReturn(new LinkedHashSet<>(Arrays.asList(t1, f1)));
        when(reverseCfg.getChildren(t1)).thenReturn(Collections.singleton(b1));
        when(reverseCfg.getChildren(f1)).thenReturn(Collections.singleton(b1));
        when(reverseCfg.getChildren(b1)).thenReturn(Collections.singleton(entry));
        when(reverseCfg.getChildren(entry)).thenReturn(Collections.singleton(exit));

        when(reverseCfg.getParents(exit)).thenReturn(Collections.singleton(entry));
        when(reverseCfg.getParents(j2)).thenReturn(Collections.singleton(exit));
        when(reverseCfg.getParents(t2)).thenReturn(Collections.singleton(j2));
        when(reverseCfg.getParents(f2)).thenReturn(Collections.singleton(j2));
        when(reverseCfg.getParents(b2)).thenReturn(new LinkedHashSet<>(Arrays.asList(t2, f2)));
        when(reverseCfg.getParents(j1)).thenReturn(Collections.singleton(b2));
        when(reverseCfg.getParents(t1)).thenReturn(Collections.singleton(j1));
        when(reverseCfg.getParents(f1)).thenReturn(Collections.singleton(j1));
        when(reverseCfg.getParents(b1)).thenReturn(new LinkedHashSet<>(Arrays.asList(t1, f1)));
        when(reverseCfg.getParents(entry)).thenReturn(Collections.singleton(b1));

        ControlDependenceGraph cdg = new ControlDependenceGraph(cfg);

        assertNotNull(cdg);

        // T1 and F1 control dependent on B1
        assertTrue(cdg.containsEdge(b1, t1), "T1 should be control dependent on B1");
        assertTrue(cdg.containsEdge(b1, f1), "F1 should be control dependent on B1");

        // T2 and F2 control dependent on B2
        assertTrue(cdg.containsEdge(b2, t2), "T2 should be control dependent on B2");
        assertTrue(cdg.containsEdge(b2, f2), "F2 should be control dependent on B2");

        // B1 and B2 should NOT have a control dependence edge between them
        assertFalse(cdg.containsEdge(b2, b1), "B1 should not be control dependent on B2");
        assertFalse(cdg.containsEdge(b1, b2), "B2 should not be control dependent on B1");
    }
}
