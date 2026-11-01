/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.graphs;

import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.toCollection;

/**
 * Supposed to become the super class of all kinds of graphs used within
 * EvoSuite Examples are the raw and minimal Control Flow Graph and hopefully at
 * one point the Control Dependency Tree.
 *
 * <p>This class is supposed to hide the jGraph library from the rest of EvoSuite
 * and is supposed to serve as an interface for all kinds of primitive graph-
 * functionality such as asking for information about the nodes and edges of the
 * graph and the relations between them.
 *
 * <p>Hopefully at some point only this class and it's sub classes are the only
 * files in EvoSuite that import anything from the jGraph library - at least
 * that's the idea. This is very similar to the way cfg.ASMWrapper is supposed to
 * hide the ASM library and serve as an interface for BytecodeInstrucions.
 *
 * <p>So most of this class' methods are just wrappers that redirect the specific
 * call to the corresponding jGraph-method.
 *
 * <p>For now an EvoSuiteGraph can always be represented by a DefaultDirectedGraph
 * from the jGraph library - that is a directed graph not allowed to contain
 * multiple edges between to nodes but allowed to contain cycles.
 *
 * @author Andre Mis
 */
public abstract class EvoSuiteGraph<V, E extends DefaultEdge> {

    private static final Logger logger = LoggerFactory.getLogger(EvoSuiteGraph.class);

    private static int evoSuiteGraphs = 0;
    protected int graphId;

    protected Graph<V, E> graph;
    protected Class<E> edgeClass;

    // for .dot functionality
    ComponentAttributeProvider<V> vertexAttributeProvider = null;
    ComponentAttributeProvider<E> edgeAttributeProvider = null;

    protected EvoSuiteGraph(Class<E> edgeClass) {

        graph = new DefaultDirectedGraph<>(edgeClass);
        this.edgeClass = edgeClass;

        setId();
    }

    protected EvoSuiteGraph(Graph<V, E> graph, Class<E> edgeClass) {
        if (graph == null || edgeClass == null) {
            throw new IllegalArgumentException("null given");
        }

        this.graph = graph;
        this.edgeClass = edgeClass;

        setId();
    }

    private void setId() {
        evoSuiteGraphs++;
        graphId = evoSuiteGraphs;
    }

    // retrieving nodes and edges

    public V getEdgeSource(E e) {
        if (!containsEdge(e)) {
            throw new IllegalArgumentException("edge not in graph");
        }

        return graph.getEdgeSource(e);
    }

    public V getEdgeTarget(E e) {
        if (!containsEdge(e)) {
            throw new IllegalArgumentException("edge not in graph");
        }

        return graph.getEdgeTarget(e);
    }

    public Set<E> outgoingEdgesOf(V node) {
        if (!containsVertex(node)) { // should this just return null?
            throw new IllegalArgumentException(
                    "node not contained in this graph");
        }
        return new LinkedHashSet<>(graph.outgoingEdgesOf(node));
    }

    public Set<E> incomingEdgesOf(V node) {
        if (!containsVertex(node)) { // should this just return null?
            throw new IllegalArgumentException("node not contained in this graph ");
        }
        return new LinkedHashSet<>(graph.incomingEdgesOf(node));
    }

    /**
     * Returns the direct successors of the given node, or {@code null} (with a warning) if the node is not in
     * the graph.
     */
    public Set<V> getChildren(V node) {
        if (!containsVertex(node)) {
            logger.warn("getChildren call requests a node not contained in the current graph. Node: " + node);
            return null;
        }

        Set<V> r = outgoingEdgesOf(node).stream()
                .map(this::getEdgeTarget)
                .collect(toCollection(LinkedHashSet::new));

        // sanity check
        if (r.size() != outDegreeOf(node)) {
            throw new IllegalStateException(
                    "expect children count and size of set of all children of a graphs node to be equal");
        }

        return r;
    }

    /**
     * Returns the direct predecessors of the given node.
     *
     * @throws IllegalArgumentException if the node is not in the graph
     */
    public Set<V> getParents(V node) {
        if (!containsVertex(node)) { // should this just return null?
            throw new IllegalArgumentException(
                    "node not contained in this graph");
        }

        Set<V> r = incomingEdgesOf(node).stream()
                .map(this::getEdgeSource)
                .collect(toCollection(LinkedHashSet::new));

        // sanity check
        if (r.size() != inDegreeOf(node)) {
            throw new IllegalStateException(
                    "expect parent count and size of set of all parents of a graphs node to be equal");
        }

        return r;
    }

    public Set<V> vertexSet() {
        return new LinkedHashSet<>(graph.vertexSet());
    }

    public Set<E> edgeSet() {
        return new LinkedHashSet<>(graph.edgeSet());
    }

    /**
     * If the given node is contained within this graph and has exactly one
     * child v this method will return v. Otherwise it will return null
     */
    public V getSingleChild(V node) {
        if (node == null) {
            return null;
        }
        if (!containsVertex(node)) {
            return null;
        }
        if (outDegreeOf(node) != 1) {
            return null;
        }

        for (E e : outgoingEdgesOf(node)) {
            return getEdgeTarget(e);
        }
        // should be unreachable
        return null;
    }

    // building the graph

    protected void addVertices(EvoSuiteGraph<V, E> other) {

        addVertices(other.vertexSet());
    }

    protected void addVertices(Collection<V> vs) {
        if (vs == null) {
            throw new IllegalArgumentException("null given");
        }
        for (V v : vs) {
            if (!addVertex(v)) {
                throw new IllegalArgumentException(
                        "unable to add all nodes in given collection: "
                                + v.toString());
            }
        }
    }

    protected boolean addVertex(V v) {
        return graph.addVertex(v);
    }

    protected E addEdge(V src, V target) {

        return graph.addEdge(src, target);
    }

    protected boolean addEdge(V src, V target, E e) {

        return graph.addEdge(src, target, e);
    }

    /**
     * Redirects all edges going into node from to the node newStart and all
     * edges going out of node from to the node newEnd.
     *
     * <p>All three edges have to be present in the graph prior to a call to this
     * method.
     */
    protected boolean redirectEdges(V from, V newStart, V newEnd) {
        if (!(containsVertex(from) && containsVertex(newStart) && containsVertex(newEnd))) {
            throw new IllegalArgumentException(
                    "expect all given nodes to be present in this graph");
        }

        if (!redirectIncomingEdges(from, newStart)) {
            return false;
        }

        return redirectOutgoingEdges(from, newEnd);

    }

    /**
     * Redirects all incoming edges to oldNode to node newNode by calling
     * redirectEdgeTarget for each incoming edge of oldNode.
     */
    protected boolean redirectIncomingEdges(V oldNode, V newNode) {
        return incomingEdgesOf(oldNode).stream()
                .allMatch(incomingEdge -> redirectEdgeTarget(incomingEdge, newNode));
    }

    /**
     * Redirects all outgoing edges to oldNode to node newNode by calling
     * redirectEdgeSource for each outgoing edge of oldNode.
     */
    protected boolean redirectOutgoingEdges(V oldNode, V newNode) {
        return outgoingEdgesOf(oldNode).stream()
                .allMatch(outgoingEdge -> redirectEdgeSource(outgoingEdge, newNode));
    }

    /**
     * Redirects the edge target of the given edge to the given node by removing
     * the given edge from the graph and reinserting it from the original source
     * node to the given node.
     */
    protected boolean redirectEdgeTarget(E edge, V node) {
        if (!(containsVertex(node) && containsEdge(edge))) {
            throw new IllegalArgumentException(
                    "edge and node must be present in this graph");
        }

        V edgeSource = graph.getEdgeSource(edge);
        if (!graph.removeEdge(edge)) {
            return false;
        }

        return addEdge(edgeSource, node, edge);
    }

    /**
     * Redirects the edge source of the given edge to the given node by removing
     * the given edge from the graph and reinserting it from the given node to
     * the original target node.
     */
    protected boolean redirectEdgeSource(E edge, V node) {
        if (!(containsVertex(node) && containsEdge(edge))) {
            throw new IllegalArgumentException(
                    "edge and node must be present in this graph");
        }

        V edgeTarget = graph.getEdgeTarget(edge);
        if (!graph.removeEdge(edge)) {
            return false;
        }

        return addEdge(node, edgeTarget, edge);
    }

    // different counts

    public int vertexCount() {
        return graph.vertexSet().size();
    }

    public int edgeCount() {
        return graph.edgeSet().size();
    }

    public int outDegreeOf(V node) { // TODO rename to sth. like childCount()
        if (node == null || !containsVertex(node)) {
            return -1;
        }

        return graph.outDegreeOf(node);
    }

    public int inDegreeOf(V node) { // TODO rename sth. like parentCount()
        if (node == null || !containsVertex(node)) {
            return -1;
        }

        return graph.inDegreeOf(node);
    }

    // some queries

    public E getEdge(V v1, V v2) {
        return graph.getEdge(v1, v2);
    }

    public boolean containsVertex(V v) {
        // documentation says containsVertex() returns false on when given null
        return graph.containsVertex(v);
    }

    public boolean containsEdge(V v1, V v2) {
        return graph.containsEdge(v1, v2);
    }

    public boolean containsEdge(E e) {
        return graph.containsEdge(e);
    }

    public boolean isEmpty() {
        return graph.vertexSet().isEmpty();
    }

    /**
     * Checks whether each vertex inside this graph is reachable from some other
     * vertex.
     */
    public boolean isConnected() {
        if (vertexCount() < 2) {
            return true;
        }

        V start = getRandomVertex();
        Set<V> connectedToStart = determineConnectedVertices(start);

        return connectedToStart.size() == vertexSet().size();
    }

    /**
     * determineEntryPoints.
     *
     * @return Set containing all nodes with in degree 0
     */
    public Set<V> determineEntryPoints() {
        Set<V> r = new LinkedHashSet<>();

        for (V instruction : vertexSet()) {
            if (inDegreeOf(instruction) == 0) {
                r.add(instruction);
            }
        }

        return r;
    }

    /**
     * determineExitPoints.
     *
     * @return Set containing all nodes with out degree 0
     */
    public Set<V> determineExitPoints() {
        Set<V> r = new LinkedHashSet<>();

        for (V instruction : vertexSet()) {
            if (outDegreeOf(instruction) == 0) {
                r.add(instruction);
            }
        }

        return r;
    }

    /**
     * Follows all edges adjacent to the given vertex v ignoring edge directions
     * and returns a set containing all vertices visited that way.
     */
    public Set<V> determineConnectedVertices(V v) {

        Set<V> visited = new LinkedHashSet<>();
        Queue<V> queue = new LinkedList<>();

        queue.add(v);
        while (!queue.isEmpty()) {
            V current = queue.poll();
            if (visited.contains(current)) {
                continue;
            }
            visited.add(current);

            queue.addAll(getParents(current));
            queue.addAll(getChildren(current));
        }

        return visited;
    }

    /**
     * Returns true iff whether the given node is not null, in this graph and
     * has exactly n parents and m children.
     */
    public boolean hasNPartentsMChildren(V node, int n, int m) {
        if (node == null || !containsVertex(node)) {
            return false;
        }

        return inDegreeOf(node) == n && outDegreeOf(node) == m;
    }

    // utilities

    /**
     * Returns some vertex of this graph, or {@code null} if the graph is empty. The choice is not random: it is
     * the first vertex in iteration order.
     */
    public V getRandomVertex() {
        // TODO that's not really random
        for (V v : graph.vertexSet()) {
            return v;
        }

        return null;
    }

    public int getDistance(V v1, V v2) {
        // no path: an infinite weight, which the cast turns into -1
        double length = new DijkstraShortestPath<>(graph).getPathWeight(v1, v2);
        return (int) Math.round(length);
    }

    public boolean isDirectSuccessor(V v1, V v2) {

        return (containsEdge(v1, v2) && inDegreeOf(v2) == 1);
    }

    // TODO make like determineEntry/ExitPoints

    public Set<V> determineBranches() {
        return graph.vertexSet().stream()
                .filter(instruction -> outDegreeOf(instruction) > 1)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<V> determineJoins() {
        return vertexSet().stream()
                .filter(instruction -> inDegreeOf(instruction) > 1)
                .collect(toCollection(LinkedHashSet::new));
    }

    // building up the reverse graph

    /**
     * Returns a reverted version of this graph in a jGraph.
     *
     * <p>That is a graph containing exactly the same nodes as this one but for
     * each edge from v1 to v2 in this graph the resulting graph will contain an
     * edge from v2 to v1 - or in other words the reverted edge.
     *
     * <p>This is used to revert CFGs in order to determine control dependencies
     * for example.
     */
    protected DefaultDirectedGraph<V, E> computeReverseJGraph() {

        DefaultDirectedGraph<V, E> r = new DefaultDirectedGraph<>(edgeClass);

        for (V v : vertexSet()) {
            if (!r.addVertex(v)) {
                throw new IllegalStateException(
                        "internal error while adding vertices");
            }
        }

        for (E e : edgeSet()) {
            V src = getEdgeSource(e);
            V target = getEdgeTarget(e);
            if (r.addEdge(target, src) == null) {
                throw new IllegalStateException(
                        "internal error while adding reverse edges");
            }
        }

        return r;
    }

    // visualizing the graph TODO clean up!

    /**
     * Writes this graph as a DOT file into the graph directory and creates a script to convert it to PNG.
     */
    public void toDot() {

        createGraphDirectory();

        String dotFileName = getGraphDirectory() + toFileString(getName())
                + ".dot";
        toDot(dotFileName);
        createToPNGScript(dotFileName);
    }

    private String getGraphDirectory() {
        return "evosuite-graphs/" + dotSubFolder();
    }

    /**
     * Subclasses can overwrite this method in order to separate their .dot and
     * .png export to a special folder.
     */
    protected String dotSubFolder() {
        return "";
    }

    protected String toFileString(String name) {

        return name.replaceAll("\\(", "_").replaceAll("\\)", "_")
                .replaceAll(";", "_").replaceAll("/", "_").replaceAll("<", "_")
                .replaceAll(">", "_");
    }

    private void createGraphDirectory() {

        File graphDir = new File(getGraphDirectory());

        if (!graphDir.exists() && !graphDir.mkdirs()) {
            throw new IllegalStateException("unable to create directory "
                    + getGraphDirectory());
        }
    }

    private void createToPNGScript(String filename) {
        File dotFile = new File(filename);

        assert (dotFile.exists() && !dotFile.isDirectory());

        try {
            ProcessBuilder pb = new ProcessBuilder("dot", "-Tpng",
                    "-o" + dotFile.getAbsolutePath() + ".png",
                    dotFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // We wait for the process to ensure the PNG is generated
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("dot process exited with code " + exitCode + " for file " + filename);
            }

        } catch (IOException | InterruptedException e) {
            logger.error("Problem while generating a graph for a dotFile: " + filename, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public String getName() {
        return "EvoSuiteGraph_" + graphId;
    }

    public void registerVertexAttributeProvider(
            ComponentAttributeProvider<V> vertexAttributeProvider) {
        this.vertexAttributeProvider = vertexAttributeProvider;
    }

    public void registerEdgeAttributeProvider(
            ComponentAttributeProvider<E> edgeAttributeProvider) {
        this.edgeAttributeProvider = edgeAttributeProvider;
    }

    private void toDot(String filename) {

        // TODO check if graphviz/dot is actually available on the current
        // machine

        try (BufferedWriter out = new BufferedWriter(new FileWriter(filename))) {

            if (!graph.vertexSet().isEmpty()) {
                new DotWriter<>(vertexAttributeProvider, edgeAttributeProvider).write(out, graph);

                logger.info("exported " + getName());
            }
        } catch (IOException e) {
            logger.error("Error writing dot file: " + filename, e);
        }
    }
}
