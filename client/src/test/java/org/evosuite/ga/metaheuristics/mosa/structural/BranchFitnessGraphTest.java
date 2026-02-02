package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BranchFitnessGraphTest {

    @Test
    public void testGraphConstruction() {
        // Create mocks
        BranchCoverageTestFitness goal = mock(BranchCoverageTestFitness.class);
        Branch branch = mock(Branch.class);
        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        BasicBlock block = mock(BasicBlock.class);
        ActualControlFlowGraph acfg = mock(ActualControlFlowGraph.class);

        // Setup relationships
        when(goal.getBranch()).thenReturn(branch);
        when(branch.getInstruction()).thenReturn(instruction);
        when(instruction.getActualCFG()).thenReturn(acfg);
        when(instruction.getBasicBlock()).thenReturn(block);
        when(block.iterator()).thenReturn(Collections.emptyIterator());

        // Setup ACFG to have no parents for simplicity
        when(acfg.getParents(block)).thenReturn(new HashSet<>());

        // Mock toBranch
        when(instruction.toBranch()).thenReturn(null);

        Set<TestFitnessFunction> goals = new HashSet<>();
        goals.add(goal);

        BranchFitnessGraph graph = new BranchFitnessGraph(goals);

        // Verify root branches were identified (since no parents found with branches)
        assertTrue(graph.getRootBranches().contains(goal));
    }
}
