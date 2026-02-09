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
package org.evosuite.graphs;

import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.ext.ComponentAttributeProvider;
import org.jgrapht.ext.DOTExporter;
import org.jgrapht.ext.IntegerNameProvider;
import org.jgrapht.ext.StringEdgeNameProvider;
import org.jgrapht.ext.StringNameProvider;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

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
 * that's the idea This is very similar to the way cfg.ASMWrapper is supposed to
 * hide the ASM library and serve as an interface for BytecodeInstrucions
 *
 * <p>So most of this class' methods are just wrappers that redirect the specific
 * call to the corresponding jGraph-method
 *
 * <p>For now an EvoSuiteGraph can always be represented by a DefaultDirectedGraph
 * from the jGraph library - that is a directed graph not allowed to contain
 * multiple edges between to nodes but allowed to contain cycles
 *
 * @author Andre Mis
 */
public abstract class EvoSuiteGraph<V, E extends DefaultEdge> {

    private static final Logger logger = LoggerFactory.getLogger(EvoSuiteGraph.class);

    private static int evoSuiteGraphs = 0;
    protected int graphId;

    protected DirectedGraph<V, E> graph;
    protected Class<E> edgeClass;

    // for .dot functionality
    ComponentAttributeProvider<V> vertexAttributeProvider = null;
    ComponentAttributeProvider<E> edgeAttributeProvider = null;

    /**
     * <p>Constructor for EvoSuiteGraph.</p>
     *
     * @param edgeClass a {@link java.lang.Class} object.
     */
    protected EvoSuiteGraph(Class<E> edgeClass) {

        graph = new DefaultDirectedGraph<>(edgeClass);
        this.edgeClass = edgeClass;

        setId();
    }

    /**
     * <p>Constructor for EvoSuiteGraph.</p>
     *
     * @param graph     a {@link org.jgrapht.DirectedGraph} object.
     * @param edgeClass a {@link java.lang.Class} object.
     */
    protected EvoSuiteGraph(DirectedGraph<V, E> graph, Class<E> edgeClass) {
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

    /**
     * Returns the source of the given edge.
     *
     * @param e a E object.
     * @return a V object.
     */
    public V getEdgeSource(E e) {
        if (!containsEdge(e)) {
            throw new IllegalArgumentException("edge not in graph");
        }

        return graph.getEdgeSource(e);
    }

    /**
     * Returns the target of the given edge.
     *
     * @param e a E object.
     * @return a V object.
     */
    public V getEdgeTarget(E e) {
        if (!containsEdge(e)) {
            throw new IllegalArgumentException("edge not in graph");
        }

        return graph.getEdgeTarget(e);
    }

    /**
     * Returns a set of all edges outgoing from the specified vertex.
     *
     * @param node a V object.
     * @return a {@link java.util.Set} object.
     */
    public Set<E> outgoingEdgesOf(V node) {
        if (!containsVertex(node)) { // should this just return null?
            throw new IllegalArgumentException(
                    "node not contained in this graph");
        }
        return new LinkedHashSet<>(graph.outgoingEdgesOf(node));
    }

    /**
     * Returns a set of all edges incoming into the specified vertex.
     *
     * @param node a V object.
     * @return a {@link java.util.Set} object.
     */
    public Set<E> incomingEdgesOf(V node) {
        if (!containsVertex(node)) { // should this just return null?
            throw new IllegalArgumentException("node not contained in this graph ");
        }
        return new LinkedHashSet<>(graph.incomingEdgesOf(node));
    }

    /**
     * Returns the set of children of the given node.
     *
     * @param node a V object.
     * @return a {@link java.util.Set} object.
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
     * Returns the set of parents of the given node.
     *
     * @param node a V object.
     * @return a {@link java.util.Set} object.
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

    /**
     * Returns the set of vertices in this graph.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<V> vertexSet() {
        return new LinkedHashSet<>(graph.vertexSet());
    }

    /**
     * Returns the set of edges in this graph.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<E> edgeSet() {
        return new LinkedHashSet<>(graph.edgeSet());
    }

    /**
     * If the given node is contained within this graph and has exactly one
     * child v this method will return v. Otherwise it will return null.
     *
     * @param node a V object.
     * @return a V object.
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

    /**
     * Adds all vertices from another graph to this graph.
     *
     * @param other a {@link org.evosuite.graphs.EvoSuiteGraph} object.
     */
    protected void addVertices(EvoSuiteGraph<V, E> other) {

        addVertices(other.vertexSet());
    }

    /**
     * Adds a collection of vertices to this graph.
     *
     * @param vs a {@link java.util.Collection} object.
     */
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

    /**
     * Adds a vertex to this graph.
     *
     * @param v a V object.
     * @return a boolean.
     */
    protected boolean addVertex(V v) {
        return graph.addVertex(v);
    }

    /**
     * Adds an edge between the source and target vertices.
     * Adds the specified edge between the source and target vertices.
     *
     * @param src    a V object.
     * @param target a V object.
     * @return a E object.
     */
    protected E addEdge(V src, V target) {

        return graph.addEdge(src, target);
    }

    /**
     * Adds the specified edge between the source and target vertices.
     *
     * @param src    a V object.
     * @param target a V object.
     * @param e      a E object.
     * @return a boolean.
     */
    protected boolean addEdge(V src, V target, E e) {

        return graph.addEdge(src, target, e);
    }

    /**
     * Redirects all edges going into node from to the node newStart and all
     * edges going out of node from to the node newEnd.
     *
     * <p>All three edges have to be present in the graph prior to a call to this
     * method.
     *
     * @param from     a V object.
     * @param newStart a V object.
     * @param newEnd   a V object.
     * @return a boolean.
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
     *
     * @param oldNode a V object.
     * @param newNode a V object.
     * @return a boolean.
     */
    protected boolean redirectIncomingEdges(V oldNode, V newNode) {
        return incomingEdgesOf(oldNode).stream()
                .allMatch(incomingEdge -> redirectEdgeTarget(incomingEdge, newNode));
    }

    /**
     * Redirects all outgoing edges to oldNode to node newNode by calling
     * redirectEdgeSource for each outgoing edge of oldNode.
     *
     * @param oldNode a V object.
     * @param newNode a V object.
     * @return a boolean.
     */
    protected boolean redirectOutgoingEdges(V oldNode, V newNode) {
        return outgoingEdgesOf(oldNode).stream()
                .allMatch(outgoingEdge -> redirectEdgeSource(outgoingEdge, newNode));
    }

    /**
     * Redirects the edge target of the given edge to the given node by removing
     * the given edge from the graph and reinserting it from the original source
     * node to the given node.
     *
     * @param edge a E object.
     * @param node a V object.
     * @return a boolean.
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
     *
     * @param edge a E object.
     * @param node a V object.
     * @return a boolean.
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

    /**
     * Returns the number of vertices in this graph.
     *
     * @return a int.
     */
    public int vertexCount() {
        return graph.vertexSet().size();
    }

    /**
     * Returns the number of edges in this graph.
     *
     * @return a int.
     */
    public int edgeCount() {
        return graph.edgeSet().size();
    }

    /**
     * Returns the out-degree of the specified vertex.
     *
     * @param node a V object.
     * @return a int.
     */
    public int outDegreeOf(V node) { // TODO rename to sth. like childCount()
        if (node == null || !containsVertex(node)) {
            return -1;
        }

        return graph.outDegreeOf(node);
    }

    /**
     * Returns the in-degree of the specified vertex.
     *
     * @param node a V object.
     * @return a int.
     */
    public int inDegreeOf(V node) { // TODO rename sth. like parentCount()
        if (node == null || !containsVertex(node)) {
            return -1;
        }

        return graph.inDegreeOf(node);
    }

    // some queries

    /**
     * Returns the edge connecting the two specified vertices.
     *
     * @param v1 a V object.
     * @param v2 a V object.
     * @return a E object.
     */
    public E getEdge(V v1, V v2) {
        return graph.getEdge(v1, v2);
    }

    /**
     * Returns true if this graph contains the specified vertex.
     *
     * @param v a V object.
     * @return a boolean.
     */
    public boolean containsVertex(V v) {
        // documentation says containsVertex() returns false on when given null
        return graph.containsVertex(v);
    }

    /**
     * Returns true if this graph contains an edge connecting the two specified vertices.
     *
     * @param v1 a V object.
     * @param v2 a V object.
     * @return a boolean.
     */
    public boolean containsEdge(V v1, V v2) {
        return graph.containsEdge(v1, v2);
    }

    /**
     * Returns true if this graph contains the specified edge.
     *
     * @param e a E object.
     * @return a boolean.
     */
    public boolean containsEdge(E e) {
        return graph.containsEdge(e);
    }

    /**
     * Returns true if this graph contains no vertices.
     *
     * @return a boolean.
     */
    public boolean isEmpty() {
        return graph.vertexSet().isEmpty();
    }

    /**
     * Checks whether each vertex inside this graph is reachable from some other
     * vertex.
     *
     * @return a boolean.
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
     * Returns a set containing all nodes with in-degree 0.
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
     * Returns a set containing all nodes with out-degree 0.
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
     *
     * @param v a V object.
     * @return a {@link java.util.Set} object.
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
     *
     * @param node a V object.
     * @param n    a int.
     * @param m    a int.
     * @return a boolean.
     */
    public boolean hasNPartentsMChildren(V node, int n, int m) {
        if (node == null || !containsVertex(node)) {
            return false;
        }

        return inDegreeOf(node) == n && outDegreeOf(node) == m;
    }

    /**
     * Returns a Set of all nodes within this graph that neither have incoming
     * nor outgoing edges.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<V> getIsolatedNodes() {
        Set<V> r = new LinkedHashSet<>();
        for (V node : graph.vertexSet()) {
            if (inDegreeOf(node) == 0 && outDegreeOf(node) == 0) {
                r.add(node);
            }
        }
        return r;
    }

    /**
     * Returns a Set containing every node in this graph that has no outgoing
     * edges.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<V> getNodesWithoutChildren() {
        Set<V> r = new LinkedHashSet<>();
        for (V node : graph.vertexSet()) {
            if (outDegreeOf(node) == 0) {
                r.add(node);
            }
        }
        return r;
    }

    // utilities

    /**
     * Returns a random vertex from this graph.
     *
     * @return a V object.
     */
    public V getRandomVertex() {
        // TODO that's not really random
        for (V v : graph.vertexSet()) {
            return v;
        }

        return null;
    }

    /**
     * Returns the shortest distance between two vertices.
     *
     * @param v1 a V object.
     * @param v2 a V object.
     * @return a int.
     */
    public int getDistance(V v1, V v2) {
        DijkstraShortestPath<V, E> d = new DijkstraShortestPath<>(graph, v1, v2);
        return (int) Math.round(d.getPathLength());
    }

    /**
     * Checks if v2 is a direct successor of v1.
     *
     * @param v1 a V object.
     * @param v2 a V object.
     * @return a boolean.
     */
    public boolean isDirectSuccessor(V v1, V v2) {

        return (containsEdge(v1, v2) && inDegreeOf(v2) == 1);
    }

    // TODO make like determineEntry/ExitPoints

    /**
     * Returns a set of vertices that are branches (out-degree greater than 1).
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<V> determineBranches() {
        return graph.vertexSet().stream()
                .filter(instruction -> outDegreeOf(instruction) > 1)
                .collect(toCollection(LinkedHashSet::new));
    }

    /**
     * Returns a set of vertices that are joins (in-degree greater than 1).
     *
     * @return a {@link java.util.Set} object.
     */
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
     * edge from v2 to v1 - or in other words the reverted edge
     *
     * <p>This is used to revert CFGs in order to determine control dependencies
     * for example
     *
     * @return a {@link org.jgrapht.graph.DefaultDirectedGraph} object.
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
     * Exports this graph to a DOT file and generates a PNG image.
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
     *
     * @return a {@link java.lang.String} object.
     */
    protected String dotSubFolder() {
        return "";
    }

    /**
     * Converts a string to a file-system safe string.
     *
     * @param name a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
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

    /**
     * Returns the name of this graph.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getName() {
        return "EvoSuiteGraph_" + graphId;
    }

    /**
     * Registers a provider for vertex attributes used in DOT export.
     *
     * @param vertexAttributeProvider a {@link org.jgrapht.ext.ComponentAttributeProvider} object.
     */
    public void registerVertexAttributeProvider(
            ComponentAttributeProvider<V> vertexAttributeProvider) {
        this.vertexAttributeProvider = vertexAttributeProvider;
    }

    /**
     * Registers a provider for edge attributes used in DOT export.
     *
     * @param edgeAttributeProvider a {@link org.jgrapht.ext.ComponentAttributeProvider} object.
     */
    public void registerEdgeAttributeProvider(
            ComponentAttributeProvider<E> edgeAttributeProvider) {
        this.edgeAttributeProvider = edgeAttributeProvider;
    }

    private void toDot(String filename) {

        // TODO check if graphviz/dot is actually available on the current
        // machine

        try (BufferedWriter out = new BufferedWriter(new FileWriter(filename))) {

            if (!graph.vertexSet().isEmpty()) {
                // FrameVertexNameProvider nameprovider = new
                // FrameVertexNameProvider(mn.instructions);
                // DOTExporter<Integer,DefaultEdge> exporter = new
                // DOTExporter<Integer,DefaultEdge>();
                // DOTExporter<Integer,DefaultEdge> exporter = new
                // DOTExporter<Integer,DefaultEdge>(new IntegerNameProvider(),
                // nameprovider, new IntegerEdgeNameProvider());
                // DOTExporter<Integer,DefaultEdge> exporter = new
                // DOTExporter<Integer,DefaultEdge>(new LineNumberProvider(),
                // new LineNumberProvider(), new IntegerEdgeNameProvider());
                DOTExporter<V, E> exporter = new DOTExporter<>(
                        new IntegerNameProvider<>(),
                        new StringNameProvider<>(),
                        new StringEdgeNameProvider<>(),
                        vertexAttributeProvider, edgeAttributeProvider);

                // new IntegerEdgeNameProvider<E>());
                exporter.export(out, graph);

                logger.info("exported " + getName());
            }
        } catch (IOException e) {
            logger.error("Error writing dot file: " + filename, e);
        }
    }
}
