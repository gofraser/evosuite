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
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class BranchFitnessGraphTest {

    @Test
    public void testGraphConstruction() {
        // Mocks
        BranchCoverageTestFitness childFitness = mock(BranchCoverageTestFitness.class);
        Branch childBranch = mock(Branch.class);
        BytecodeInstruction childInst = mock(BytecodeInstruction.class);
        BasicBlock childBlock = mock(BasicBlock.class);
        ActualControlFlowGraph acfg = mock(ActualControlFlowGraph.class);

        when(childFitness.getBranch()).thenReturn(childBranch);
        when(childBranch.getInstruction()).thenReturn(childInst);
        when(childInst.getBasicBlock()).thenReturn(childBlock);
        when(childInst.getActualCFG()).thenReturn(acfg);
        when(childInst.isRootBranchDependent()).thenReturn(false);

        BasicBlock rootBlock = mock(BasicBlock.class);
        // acfg.getParents(childBlock) returns rootBlock
        when(acfg.getParents(childBlock)).thenReturn(new HashSet<>(Collections.singletonList(rootBlock)));

        // Ensure recursion stops (rootBlock has no parents)
        when(acfg.getParents(rootBlock)).thenReturn(Collections.emptySet());

        // Mock extractBranch for rootBlock
        BytecodeInstruction rootInst = mock(BytecodeInstruction.class);
        when(rootInst.isBranch()).thenReturn(true);
        Branch rootBranch = mock(Branch.class);
        when(rootInst.toBranch()).thenReturn(rootBranch);
        when(rootBranch.getInstruction()).thenReturn(rootInst);
        when(rootBranch.getClassName()).thenReturn("TestClass");
        when(rootBranch.getMethodName()).thenReturn("testMethod");

        // Mock BasicBlock iteration for rootBlock to return rootInst
        when(rootBlock.iterator()).thenAnswer(i -> {
            Iterator<BytecodeInstruction> it = mock(Iterator.class);
            when(it.hasNext()).thenReturn(true, false);
            when(it.next()).thenReturn(rootInst);
            return it;
        });

        Set<TestFitnessFunction> goals = new HashSet<>();
        goals.add(childFitness);

        BranchFitnessGraph graph = new BranchFitnessGraph(goals);

        // Verify that childFitness has parents
        Set<TestFitnessFunction> parents = graph.getStructuralParents(childFitness);
        // Should have 2 parents (True and False branches of rootBranch)
        assertEquals(2, parents.size());

        // Verify rootBranches contains what?
        // In current implementation, if a fitness depends on something, it is NOT added to rootBranches.
        // So childFitness is not in rootBranches.
        Set<TestFitnessFunction> roots = graph.getRootBranches();
        assertTrue("Child fitness should not be a root", !roots.contains(childFitness));
    }

    @Test
    public void testRootDetection() {
        // A branch with no parents should be a root
        BranchCoverageTestFitness fitness = mock(BranchCoverageTestFitness.class);
        Branch branch = mock(Branch.class);
        BytecodeInstruction inst = mock(BytecodeInstruction.class);
        BasicBlock block = mock(BasicBlock.class);
        ActualControlFlowGraph acfg = mock(ActualControlFlowGraph.class);

        when(fitness.getBranch()).thenReturn(branch);
        when(branch.getInstruction()).thenReturn(inst);
        when(inst.getBasicBlock()).thenReturn(block);
        when(inst.getActualCFG()).thenReturn(acfg);
        when(inst.isRootBranchDependent()).thenReturn(false);

        // No parents
        when(acfg.getParents(block)).thenReturn(Collections.emptySet());
        // extractBranch returns itself or null?
        // If parents is empty, lookForParent returns {block}.
        // Then extractBranch(block) is called.
        // We mock it to return the branch itself (or equivalent).
        // But logic is:
        // Set<BasicBlock> parents = lookForParent(...);
        // If parents is empty (lookForParent returns empty? No, it returns block if no parents in CFG).

        // Let's see lookForParent logic:
        // if (parents.size() == 0) return {block};

        // So parents contains block.
        // for (BasicBlock bb : parents) { extractBranch(bb) ... }
        // If extractBranch(block) returns 'branch', then it adds edges.
        // It does NOT add to rootBranches explicitly in loop.

        // Wait.
        // BranchFitnessGraph:
        // Set<BasicBlock> parents = lookForParent(...)
        // for (BasicBlock bb : parents) {
        //    Branch newB = extractBranch(bb);
        //    if (newB == null) { rootBranches.add(fitness); continue; }
        //    create newFitness for newB
        //    graph.addEdge(newFitness, fitness);
        // }

        // If lookForParent returns the block itself (because it has no CFG parents).
        // And extractBranch(block) returns the branch itself.
        // Then it creates newFitness (clone of itself essentially) and adds edge newFitness -> fitness.
        // It creates a self-loop?
        // And it does NOT add to rootBranches.

        // This seems to indicate that roots are only those where extractBranch returns null?
        // Or where lookForParent returns nothing? But lookForParent always returns at least block if no CFG parents.

        // Logic check:
        // if (parents.size() == 0) { realParent.add(block); return realParent; }

        // So if a block has no parents, it is its own parent.
        // If it contains a branch, extractBranch returns it.
        // Then we add edge from itself to itself?
        // And we don't add to rootBranches?

        // This suggests my understanding or the code is flawed.
        // If it's a root branch (entry of CFG?), it usually doesn't have a branch instruction *before* it.
        // The entry block of a method might not have a branch.
        // If `extractBranch` returns null, then `rootBranches.add(fitness)`.

        // So a root branch is one whose parent block does NOT contain a branch.
        // If the parent block (CFG parent) is the entry block, and entry block is just `ICONST_0`, no branch.
        // Then extractBranch returns null.
        // Then it is added to rootBranches.

        // So for `testRootDetection`:
        // We simulate a branch whose parent is the Entry Block (no branch).

        BasicBlock entryBlock = mock(BasicBlock.class);
        when(acfg.getParents(block)).thenReturn(new HashSet<>(Collections.singletonList(entryBlock)));
        when(acfg.getParents(entryBlock)).thenReturn(Collections.emptySet());

        // extractBranch(entryBlock) returns null
        when(entryBlock.iterator()).thenAnswer(i -> {
            Iterator<BytecodeInstruction> it = mock(Iterator.class);
            when(it.hasNext()).thenReturn(false);
            return it;
        });

        Set<TestFitnessFunction> goals = new HashSet<>();
        goals.add(fitness);
        BranchFitnessGraph graph = new BranchFitnessGraph(goals);

        assertTrue("Should be detected as root", graph.getRootBranches().contains(fitness));
    }
}
