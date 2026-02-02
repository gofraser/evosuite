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
package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.testcase.TestFitnessFunction;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * @author Annibale Panichella
 */
public class BranchFitnessGraph implements Serializable {

    private static final long serialVersionUID = -8020578778906420503L;

    private static final Logger logger = LoggerFactory.getLogger(BranchFitnessGraph.class);

    protected DefaultDirectedGraph<TestFitnessFunction, DependencyEdge> graph =
            new DefaultDirectedGraph<>(DependencyEdge.class);

    protected Set<TestFitnessFunction> rootBranches = new HashSet<>();

    public BranchFitnessGraph(Set<TestFitnessFunction> goals) {
        goals.forEach(g -> graph.addVertex(g));

        // derive dependencies among branches
        for (TestFitnessFunction fitness : goals) {
            if (!(fitness instanceof BranchCoverageTestFitness)) {
                continue;
            }

            BranchCoverageTestFitness branchFitness = (BranchCoverageTestFitness) fitness;
            Branch branch = branchFitness.getBranch();
            if (branch == null) {
                this.rootBranches.add(fitness);
                continue;
            }

            if (branch.getInstruction().isRootBranchDependent())
                this.rootBranches.add(fitness);

            // see dependencies for all true/false branches
            ActualControlFlowGraph rcfg = branch.getInstruction().getActualCFG();
            Set<BasicBlock> visitedBlocks = new HashSet<>();
            Set<BasicBlock> parents = lookForParent(branch.getInstruction().getBasicBlock(), rcfg, visitedBlocks);

            for (BasicBlock bb : parents) {
                Branch newB = extractBranch(bb);
                if (newB == null) {
                    this.rootBranches.add(fitness);
                    continue;
                }

                BranchCoverageGoal goalTrue = new BranchCoverageGoal(newB, true, newB.getClassName(), newB.getMethodName());
                BranchCoverageTestFitness newFitnessTrue = new BranchCoverageTestFitness(goalTrue);
                graph.addVertex(newFitnessTrue);
                graph.addEdge(newFitnessTrue, fitness);

                BranchCoverageGoal goalFalse = new BranchCoverageGoal(newB, false, newB.getClassName(), newB.getMethodName());
                BranchCoverageTestFitness newFitnessFalse = new BranchCoverageTestFitness(goalFalse);
                graph.addVertex(newFitnessFalse);
                graph.addEdge(newFitnessFalse, fitness);
            }
        }
    }


    public Set<BasicBlock> lookForParent(BasicBlock block, ActualControlFlowGraph acfg, Set<BasicBlock> visitedBlocks) {
        Set<BasicBlock> realParents = new HashSet<>();
        Set<BasicBlock> parents = acfg.getParents(block);
        if (parents.isEmpty()) {
            realParents.add(block);
            return realParents;
        }
        for (BasicBlock bb : parents) {
            if (visitedBlocks.contains(bb))
                continue;
            visitedBlocks.add(bb);
            if (containsBranches(bb))
                realParents.add(bb);
            else
                realParents.addAll(lookForParent(bb, acfg, visitedBlocks));
        }
        return realParents;
    }

    /**
     * Utility method that verifies whether a basic block (@link {@link BasicBlock})
     * contains a branch.
     *
     * @param block object of {@link BasicBlock}
     * @return true or false depending on whether a branch is found
     */
    public boolean containsBranches(BasicBlock block) {
        for (BytecodeInstruction inst : block)
            if (inst.toBranch() != null)
                return true;
        return false;
    }

    /**
     * Utility method that extracts a branch ({@link Branch}) from a basic block
     * (@link {@link BasicBlock}).
     *
     * @param block object of {@link BasicBlock}
     * @return an object of {@link Branch} representing the branch in the block
     */
    public Branch extractBranch(BasicBlock block) {
        for (BytecodeInstruction inst : block)
            if (inst.isBranch() || inst.isActualBranch())
                return inst.toBranch();
        return null;
    }

    public Set<TestFitnessFunction> getRootBranches() {
        return this.rootBranches;
    }

    public Set<TestFitnessFunction> getStructuralChildren(TestFitnessFunction parent) {
        return this.graph.outgoingEdgesOf(parent).stream()
                .map(DependencyEdge::getTarget)
                .collect(toSet());
    }

    public Set<TestFitnessFunction> getStructuralParents(TestFitnessFunction parent) {
        return this.graph.incomingEdgesOf(parent).stream()
                .map(DependencyEdge::getSource)
                .collect(toSet());
    }
}
