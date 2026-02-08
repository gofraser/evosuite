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
package org.evosuite.graphs.cdg;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.EvoSuiteGraph;
import org.evosuite.graphs.cfg.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

public class ControlDependenceGraph extends EvoSuiteGraph<BasicBlock, ControlFlowEdge> {

    private static final Logger logger = LoggerFactory.getLogger(ControlDependenceGraph.class);

    private final ActualControlFlowGraph cfg;

    private final String className;
    private final String methodName;

    /**
     * <p>Constructor for ControlDependenceGraph.</p>
     *
     * @param cfg a {@link org.evosuite.graphs.cfg.ActualControlFlowGraph} object.
     */
    public ControlDependenceGraph(ActualControlFlowGraph cfg) {
        super(ControlFlowEdge.class);

        this.cfg = cfg;
        this.className = cfg.getClassName();
        this.methodName = cfg.getMethodName();

        computeGraph();
    }

    /**
     * Checks whether this graph knows the given instruction. That is there is a
     * BasicBlock in this graph's vertexSet containing the given instruction.
     *
     * @param ins a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
     * @return a boolean.
     */
    public boolean knowsInstruction(BytecodeInstruction ins) {
        return cfg.knowsInstruction(ins);
    }

    /**
     * <p>getControlDependenceDepth.</p>
     *
     * @param dependence a {@link org.evosuite.graphs.cfg.ControlDependency} object.
     * @return a int.
     */
    public int getControlDependenceDepth(ControlDependency dependence) {
        int min = Integer.MAX_VALUE;
        for (BasicBlock root : determineEntryPoints()) {
            int distance = getDistance(root,
                    dependence.getBranch().getInstruction().getBasicBlock());
            if (distance < min) {
                min = distance;
            }
        }
        return min;
    }

    /**
     * <p>getAlternativeBlocks.</p>
     *
     * @param dependency a {@link org.evosuite.graphs.cfg.ControlDependency} object.
     * @return a {@link java.util.Set} object.
     */
    public Set<BasicBlock> getAlternativeBlocks(ControlDependency dependency) {
        Set<BasicBlock> blocks = new LinkedHashSet<>();
        Branch branch = dependency.getBranch();

        BasicBlock block = branch.getInstruction().getBasicBlock();
        for (ControlFlowEdge e : outgoingEdgesOf(block)) {
            // ControlDependency can be null on edges that are not control dependent (e.g. unconditional)
            if (e.getControlDependency() == null
                    || e.getControlDependency().equals(dependency)) {
                continue;
            }
            BasicBlock next = getEdgeTarget(e);
            blocks.add(next);
            getReachableBasicBlocks(blocks, next);
        }
        return blocks;
    }

    private void getReachableBasicBlocks(Set<BasicBlock> blocks, BasicBlock start) {
        for (ControlFlowEdge e : outgoingEdgesOf(start)) {
            BasicBlock next = getEdgeTarget(e);
            if (!blocks.contains(next)) {
                blocks.add(next);
                getReachableBasicBlocks(blocks, next);
            }
        }
    }

    /**
     * Returns a Set containing all Branches the given BasicBlock is control
     * dependent on.
     *
     * <p>
     * This is for each incoming ControlFlowEdge of the given block within this
     * CDG, the branch instruction of that edge will be added to the returned
     * set.
     * </p>
     *
     * @param insBlock a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     * @return a {@link java.util.Set} object.
     */
    public Set<ControlDependency> getControlDependentBranches(BasicBlock insBlock) {
        if (insBlock == null) {
            throw new IllegalArgumentException("null not accepted");
        }
        if (!containsVertex(insBlock)) {
            throw new IllegalArgumentException("unknown block: " + insBlock.getName());
        }

        if (insBlock.hasControlDependenciesSet()) {
            return insBlock.getControlDependencies();
        }

        Set<ControlDependency> direct = retrieveControlDependencies(insBlock, new LinkedHashSet<>());
        if (direct.isEmpty()) {
            return direct;
        }

        // Expand with transitive control dependencies (e.g., nested branches).
        Set<ControlDependency> expanded = new LinkedHashSet<>(direct);
        Set<BasicBlock> visited = new LinkedHashSet<>();
        for (ControlDependency cd : direct) {
            expandTransitiveDependencies(expanded,
                    cd.getBranch().getInstruction().getBasicBlock(),
                    visited);
        }
        return expanded;
    }

    private void expandTransitiveDependencies(Set<ControlDependency> out,
                                              BasicBlock start,
                                              Set<BasicBlock> visited) {
        if (start == null || !visited.add(start)) {
            return;
        }

        Set<ControlDependency> deps = retrieveControlDependencies(start, new LinkedHashSet<>());
        for (ControlDependency cd : deps) {
            if (out.add(cd)) {
                expandTransitiveDependencies(out,
                        cd.getBranch().getInstruction().getBasicBlock(),
                        visited);
            }
        }
    }

    private Set<ControlDependency> retrieveControlDependencies(BasicBlock insBlock,
                                                               Set<ControlFlowEdge> handled) {

        Set<ControlDependency> r = new LinkedHashSet<>();

        for (ControlFlowEdge e : incomingEdgesOf(insBlock)) {
            if (handled.contains(e)) {
                continue;
            }
            handled.add(e);

            ControlDependency cd = e.getControlDependency();
            if (cd != null) {
                r.add(cd);
            } else {
                BasicBlock in = getEdgeSource(e);
                if (!in.equals(insBlock)) {
                    r.addAll(retrieveControlDependencies(in, handled));
                }
            }

        }

        return r;
    }

    /**
     * <p>getControlDependentBranchIds.</p>
     *
     * @param ins a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     * @return a {@link java.util.Set} object.
     */
    public Set<Integer> getControlDependentBranchIds(BasicBlock ins) {

        Set<ControlDependency> dependentBranches = getControlDependentBranches(ins);

        Set<Integer> r = new LinkedHashSet<>();

        for (ControlDependency cd : dependentBranches) {
            if (cd == null) {
                throw new IllegalStateException(
                        "expect set returned by getControlDependentBranches() not to contain null");
            }

            r.add(cd.getBranch().getActualBranchId());
        }

        // to indicate this is only dependent on root branch,
        // meaning entering the method
        if (isRootDependent(ins)) {
            r.add(-1);
        }

        return r;
    }

    // initialization

    /**
     * Determines whether the given BytecodeInstruction is directly control
     * dependent on the given Branch. It's BasicBlock is control dependent on
     * the given Branch.
     *
     * <p>
     * If b is null, it is assumed to be the root branch.
     * </p>
     *
     * <p>
     * If the given instruction is not known to this CDG an
     * IllegalArgumentException is thrown.
     * </p>
     *
     * @param ins a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
     * @param b   a {@link org.evosuite.coverage.branch.Branch} object.
     * @return a boolean.
     */
    public boolean isDirectlyControlDependentOn(BytecodeInstruction ins, Branch b) {
        if (ins == null) {
            throw new IllegalArgumentException("null given");
        }

        BasicBlock insBlock = ins.getBasicBlock();

        return isDirectlyControlDependentOn(insBlock, b);
    }

    /**
     * Determines whether the given BasicBlock is directly control dependent on
     * the given Branch. Meaning within this CDG there is an incoming
     * ControlFlowEdge to this instructions BasicBlock holding the given Branch
     * as it's branchInstruction.
     *
     * <p>
     * If b is null, it is assumed to be the root branch.
     * </p>
     *
     * <p>
     * If the given instruction is not known to this CDG an
     * IllegalArgumentException is thrown.
     * </p>
     *
     * @param insBlock a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     * @param b        a {@link org.evosuite.coverage.branch.Branch} object.
     * @return a boolean.
     */
    public boolean isDirectlyControlDependentOn(BasicBlock insBlock, Branch b) {
        Set<ControlFlowEdge> incomming = incomingEdgesOf(insBlock);

        if (incomming.size() == 1) {
            // In methods with a try-catch-block it is possible that there
            // are nodes in the CDG that have exactly one parent with an
            // edge without a branchInstruction that is a non exceptional edge.
            // Should the given instruction be such a node, follow the parents until
            // you reach one where the above conditions are not met
            for (ControlFlowEdge e : incomming) {
                if (!e.hasControlDependency() && !e.isExceptionEdge()) {
                    return isDirectlyControlDependentOn(getEdgeSource(e), b);
                }
            }
        }

        boolean isRootDependent = isRootDependent(insBlock);
        if (b == null) {
            return isRootDependent;
        }
        if (isRootDependent && b != null) {
            return false;
        }

        for (ControlFlowEdge e : incomming) {
            Branch current = e.getBranchInstruction();

            if (e.isExceptionEdge()) {
                if (current != null) {
                    throw new IllegalStateException(
                            "expect exception edges to have no BranchInstruction set");
                } else {
                    continue;
                }
            }

            if (current == null) {
                continue;
            }

            if (current.equals(b)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the given instruction is dependent on the root branch of
     * it's method.
     *
     * <p>
     * This is the case if the BasicBlock of the given instruction is directly
     * adjacent to the EntryBlock
     * </p>
     *
     * @param ins a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
     * @return a boolean.
     */
    public boolean isRootDependent(BytecodeInstruction ins) {

        return isRootDependent(ins.getBasicBlock());
    }

    /**
     * Checks whether the given basicBlock is dependent on the root branch of
     * it's method.
     *
     * <p>
     * This is the case if the BasicBlock of the given instruction is directly
     * adjacent to the EntryBlock
     * </p>
     *
     * @param insBlock a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     * @return a boolean.
     */
    public boolean isRootDependent(BasicBlock insBlock) {
        if (isAdjacentToEntryBlock(insBlock)) {
            return true;
        }

        for (ControlFlowEdge in : incomingEdgesOf(insBlock)) {
            if (in.hasControlDependency()) {
                continue;
            }
            BasicBlock inBlock = getEdgeSource(in);
            if (inBlock.equals(insBlock)) {
                continue;
            }

            if (isRootDependent(inBlock)) {
                return true;
            }
        }

        return false;

    }

    /**
     * Returns true if the given BasicBlock has an incoming edge from this CDG's
     * EntryBlock or is itself the EntryBlock.
     *
     * @param insBlock a {@link org.evosuite.graphs.cfg.BasicBlock} object.
     * @return a boolean.
     */
    public boolean isAdjacentToEntryBlock(BasicBlock insBlock) {

        if (insBlock.isEntryBlock()) {
            return true;
        }

        Set<BasicBlock> parents = getParents(insBlock);
        for (BasicBlock parent : parents) {
            if (parent.isEntryBlock()) {
                return true;
            }
        }

        return false;
    }

    // init

    private void computeGraph() {
        createGraphNodes();
        computeControlDependence();
    }

    private void createGraphNodes() {
        // copy CFG nodes
        addVertices(cfg);

        // Remove exit blocks from CDG as they don't have control dependencies
        Set<BasicBlock> toRemove = new LinkedHashSet<>();
        for (BasicBlock b : vertexSet()) {
            if (b.isExitBlock()) {
                toRemove.add(b);
            }
        }

        for (BasicBlock b : toRemove) {
            if (!graph.removeVertex(b)) {
                throw new IllegalStateException("internal error building up CDG: failed to remove exit block");
            }
        }
    }

    private void computeControlDependence() {

        ActualControlFlowGraph rcfg = cfg.computeReverseCFG();
        DominatorTree<BasicBlock> dt = new DominatorTree<>(rcfg);

        for (BasicBlock b : rcfg.vertexSet()) {
            if (b.isExitBlock()) {
                continue;
            }

            logger.debug("DFs for: " + b.getName());
            for (BasicBlock cd : dt.getDominatingFrontiers(b)) {
                ControlFlowEdge orig = cfg.getEdge(cd, b);

                if (!cd.isEntryBlock() && orig == null) {
                    // Handling the case where cd and b are not directly connected in CFG

                    logger.debug("cd: " + cd);
                    logger.debug("b: " + b);

                    Set<ControlFlowEdge> candidates = cfg.outgoingEdgesOf(cd);
                    if (candidates.size() < 2) {
                        logger.warn("Expected branch node " + cd + " to have multiple outgoing edges, but found "
                                + candidates.size());
                    }

                    boolean leadToB = false;
                    boolean skip = false;

                    for (ControlFlowEdge e : candidates) {
                        if (!e.hasControlDependency()) {
                            // Ignore exception edges when determining the controlling branch.
                            if (e.isExceptionEdge()) {
                                continue;
                            }
                            skip = true;
                            break;
                        }

                        if (cfg.leadsToNode(e, b)) {
                            if (leadToB) {
                                orig = null;
                            }
                            leadToB = true;
                            orig = e;
                        }
                    }
                    if (skip) {
                        continue;
                    }

                    if (!leadToB) {
                        logger.warn("Unexpected: node " + cd + " determined as control dependency for " + b
                                + " but no path found.");
                    }
                }

                if (orig != null) {
                    if (!addEdge(cd, b, new ControlFlowEdge(orig))) {
                        throw new IllegalStateException("internal error while adding CD edge");
                    }

                    logger.debug("  " + cd.getName());
                } else {
                    logger.debug("orig is null, cannot add CD edge for " + cd + " -> " + b);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return methodName + "_" + "CDG";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String dotSubFolder() {
        return toFileString(className) + "/CDG/";
    }

    /**
     * <p>Getter for the field <code>className</code>.</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getClassName() {
        return className;
    }

    /**
     * <p>Getter for the field <code>methodName</code>.</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getMethodName() {
        return methodName;
    }
}
