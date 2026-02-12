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

import org.evosuite.graphs.EvoSuiteGraph;
import org.evosuite.graphs.cfg.ControlFlowGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;


/**
 * Given a CFG this class computes the immediateDominators and the
 * dominatingFrontiers for each CFG vertex.
 *
 * <p>The current algorithm to determine the immediateDominators runs in time
 * O(e*log n) where e is the number of control flow edges and n the number of
 * CFG vertices and is taken from:
 *
 * <p>"A Fast Algorithm for Finding Dominators in a Flowgraph" THOMAS LENGAUER and
 * ROBERT ENDRE TARJAN 1979, Stanford University.
 *
 * <p>DOI: 10.1145/357062.357071
 * http://portal.acm.org/citation.cfm?doid=357062.357071.
 *
 * <p>The algorithm for computing the dominatingFrontiers when given the
 * immediateDominators is taken from:
 *
 * <p>"Efficiently Computing Static Single Assignment Form and the Control
 * Dependence Graph" RON CYTRON, JEANNE FERRANTE, BARRY K. ROSEN, and MARK N.
 * WEGMAN IBM Research Division and F. KENNETH ZADECK Brown University 1991.
 *
 * @author Andre Mis
 */
public class DominatorTree<V> extends EvoSuiteGraph<DominatorNode<V>, DefaultEdge> {

    private static final Logger logger = LoggerFactory.getLogger(DominatorTree.class);

    private int nodeCount = 0;
    private final ControlFlowGraph<V> cfg;

    private final Map<V, DominatorNode<V>> dominatorNodesMap = new LinkedHashMap<>();
    private final Map<Integer, DominatorNode<V>> dominatorIdMap = new LinkedHashMap<>();
    private final Map<V, Set<V>> dominatingFrontiers = new LinkedHashMap<>();

    /**
     * Will start the computation of all immediateDominators for the given CFG
     * which can later be retrieved via getImmediateDominator().
     *
     * @param cfg a {@link org.evosuite.graphs.cfg.ControlFlowGraph} object.
     */
    public DominatorTree(ControlFlowGraph<V> cfg) {
        super(DefaultEdge.class);

        if (cfg == null) {
            throw new IllegalArgumentException("CFG cannot be null");
        }

        logger.debug("Computing DominatorTree for " + cfg.getName());

        this.cfg = cfg;

        createDominatorNodes();

        V root = cfg.determineEntryPoint();
        logger.debug("determined root: " + root);

        if (root != null) {
            DominatorNode<V> rootNode = getDominatorNodeFor(root);
            depthFirstAnalyze(rootNode);

            computeSemiDominators();
            computeImmediateDominators(rootNode);

            createDominatorTree();

            computeDominatorFrontiers(rootNode);
        } else if (!cfg.vertexSet().isEmpty()) {
            logger.warn("Could not determine entry point for non-empty CFG: " + cfg.getName());
        }
    }

    private void createDominatorTree() {

        // add dominator nodes
        addVertices(dominatorIdMap.values());

        logger.debug("DTNodes: " + vertexCount());

        // build up tree by adding for each node v an edge from v.iDom to v
        for (DominatorNode<V> v : vertexSet()) {
            if (v.isRootNode()) {
                continue;
            }

            if (v.immediateDominator != null) {
                if (addEdge(v.immediateDominator, v) == null) {
                    throw new IllegalStateException(
                            "internal error while building dominator tree edges");
                }

                logger.debug("added DTEdge from " + v.immediateDominator.dfsNumber + " to " + v.dfsNumber);
            }
        }

        logger.debug("DTEdges: " + edgeCount());

        // sanity check
        if (isEmpty() && !cfg.vertexSet().isEmpty()) {
            throw new IllegalStateException("expect dominator trees to not be empty for non-empty CFG");
        }
    }

    private void computeDominatorFrontiers(DominatorNode<V> currentNode) {

        for (DominatorNode<V> child : getChildren(currentNode)) {
            computeDominatorFrontiers(child);
        }

        logger.debug("computing dominatingFrontier for: " + currentNode.toString());

        Set<V> dominatingFrontier = dominatingFrontiers.get(currentNode.node);
        if (dominatingFrontier == null) {
            dominatingFrontier = new LinkedHashSet<>();
        }

        // "local"
        for (V child : cfg.getChildren(currentNode.node)) {
            DominatorNode<V> y = getDominatorNodeFor(child);
            if (y.immediateDominator == null || y.immediateDominator.dfsNumber != currentNode.dfsNumber) {
                logger.debug("  LOCAL adding to DFs: " + y.node);
                dominatingFrontier.add(y.node);
            }
        }

        // "up"
        for (DominatorNode<V> z : getChildren(currentNode)) {
            Set<V> childDF = dominatingFrontiers.get(z.node);
            if (childDF != null) {
                for (V y : childDF) {
                    DominatorNode<V> ydomNode = getDominatorNodeFor(y);
                    if (ydomNode.immediateDominator == null
                            || ydomNode.immediateDominator.dfsNumber != currentNode.dfsNumber) {
                        logger.debug("  UP adding to DFs: " + y);
                        dominatingFrontier.add(y);
                    }
                }
            }
        }

        dominatingFrontiers.put(currentNode.node, dominatingFrontier);
    }

    /**
     * Given a node of this objects CFG this method returns it's previously
     * computed immediateDominator.
     *
     * <p>The immediateDominator iDom of a node v has the following properties:
     *
     * <p>1) iDom dominates v
     *
     * <p>2) every other dominator of v dominates iDom
     *
     * <p>A node w dominates v or is a dominator of v if and only if every path
     * from the CFG's entryPoint to v contains w.
     *
     * @param v A node within this objects CFG for wich the immediateDominator
     *          is to be returned
     * @return a V object.
     */
    public V getImmediateDominator(V v) {
        if (v == null) {
            throw new IllegalArgumentException("null given");
        }
        DominatorNode<V> domNode = dominatorNodesMap.get(v);
        if (domNode == null) {
            throw new IllegalStateException("unknown vertice given");
        }

        if (domNode.immediateDominator == null) {
            // sanity check: this is only allowed to happen if v is root of CFG
            if (domNode.dfsNumber != 1) {
                // Or if node is unreachable from root.
                return null;
            }
            return null;
        }

        return domNode.immediateDominator.node;
    }

    /**
     * Getter for the field <code>dominatingFrontiers</code>.
     *
     * @param v a V object.
     * @return a {@link java.util.Set} object.
     */
    public Set<V> getDominatingFrontiers(V v) {
        if (v == null) {
            throw new IllegalStateException("null given");
        }

        Set<V> df = dominatingFrontiers.get(v);
        return df != null ? df : new LinkedHashSet<>();
    }

    // computation

    private void createDominatorNodes() {

        for (V v : cfg.vertexSet()) {
            dominatorNodesMap.put(v, new DominatorNode<>(v));
        }
    }

    private void depthFirstAnalyze(DominatorNode<V> currentNode) {
        // step 1

        initialize(currentNode);

        for (V w : cfg.getChildren(currentNode.node)) {
            DominatorNode<V> wnode = getDominatorNodeFor(w);
            if (wnode.semiDominator == null) {
                wnode.parent = currentNode;
                depthFirstAnalyze(wnode);
            }
        }
    }

    private void initialize(DominatorNode<V> currentNode) {

        nodeCount++;
        currentNode.dfsNumber = nodeCount;
        currentNode.semiDominator = currentNode;

        logger.debug("created " + currentNode + " for "
                + currentNode.node.toString());

        dominatorIdMap.put(nodeCount, currentNode);
    }

    private void computeSemiDominators() {

        for (int i = nodeCount; i >= 2; i--) {
            DominatorNode<V> w = getDominatorNodeById(i);

            // step 2
            for (V current : cfg.getParents(w.node)) {
                DominatorNode<V> v = getDominatorNodeFor(current);
                if (v.dfsNumber == 0) {
                    continue; // Skip unreachable nodes
                }

                DominatorNode<V> u = v.eval();

                if (u.semiDominator.dfsNumber < w.semiDominator.dfsNumber) {
                    w.semiDominator = u.semiDominator;
                }
            }

            w.semiDominator.bucket.add(w);
            w.link(w.parent);

            // step 3
            while (!w.parent.bucket.isEmpty()) {

                DominatorNode<V> v = w.parent.getFromBucket();
                if (!w.parent.bucket.remove(v)) {
                    throw new IllegalStateException("internal error");
                }

                DominatorNode<V> u = v.eval();
                v.immediateDominator = (u.semiDominator.dfsNumber < v.semiDominator.dfsNumber ? u
                        : w.parent);
            }
        }
    }

    private void computeImmediateDominators(DominatorNode<V> rootNode) {
        // step 4
        for (int i = 2; i <= nodeCount; i++) {
            DominatorNode<V> w = getDominatorNodeById(i);

            if (w.immediateDominator != w.semiDominator) {
                w.immediateDominator = w.immediateDominator.immediateDominator;
            }

            // logger.debug("iDom for node "+i+" was: "+w.immediateDominator.dfsNumber);
        }

        rootNode.immediateDominator = null;
    }

    private DominatorNode<V> getDominatorNodeById(int id) {
        DominatorNode<V> r = dominatorIdMap.get(id);
        if (r == null) {
            throw new IllegalArgumentException("id unknown to this tree");
        }

        return r;
    }

    private DominatorNode<V> getDominatorNodeFor(V v) {
        DominatorNode<V> r = dominatorNodesMap.get(v);
        if (r == null) {
            throw new IllegalStateException(
                    "expect dominatorNodesMap to contain domNodes for all Vs");
        }

        return r;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "DominatorTree" + graphId;
    }
}
