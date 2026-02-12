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
package org.evosuite.setup.callgraph;

import java.util.*;

/**
 * simple implementation of a graph where edges are not classes.
 *
 * @param <E> type of vertices used by the graph
 * @author mattia
 */
public abstract class Graph<E> {

    private final Map<E, Set<E>> edges = Collections.synchronizedMap(new HashMap<>());
    private final Map<E, Set<E>> reverseEdges = Collections.synchronizedMap(new HashMap<>());
    private final Set<E> vertexSet = Collections.synchronizedSet(new HashSet<>());

    /**
     * Get the edges.
     *
     * @return the edges
     */
    public Map<E, Set<E>> getEdges() {
        return edges;
    }

    /**
     * Remove a vertex.
     *
     * @param vertex the vertex to remove
     */
    public synchronized void removeVertex(E vertex) {
        if (edges.containsKey(vertex)) {
            for (E neighbor : edges.get(vertex)) {
                if (reverseEdges.containsKey(neighbor)) {
                    reverseEdges.get(neighbor).remove(vertex);
                }
            }
        }
        if (reverseEdges.containsKey(vertex)) {
            for (E neighbor : reverseEdges.get(vertex)) {
                if (edges.containsKey(neighbor)) {
                    edges.get(neighbor).remove(vertex);
                }
            }
        }
        edges.remove(vertex);
        reverseEdges.remove(vertex);
        vertexSet.remove(vertex);
    }

    /**
     * Check if the graph contains an edge.
     *
     * @param src the source vertex
     * @param dest the destination vertex
     * @return true if the edge exists
     */
    public synchronized boolean containsEdge(E src, E dest) {
        Set<E> tempSet = edges.get(src);
        if (tempSet == null) {
            return false;
        } else {
            return tempSet.contains(dest);
        }
    }

    /**
     * Add an edge.
     *
     * @param src the source vertex
     * @param dest the destination vertex
     */
    public synchronized void addEdge(E src, E dest) {
        vertexSet.add(src);
        vertexSet.add(dest);
        Set<E> srcNeighbors = this.edges.computeIfAbsent(src, k -> new LinkedHashSet<>());
        srcNeighbors.add(dest);

        Set<E> rsrcNeighbors = this.reverseEdges.computeIfAbsent(dest, k -> new LinkedHashSet<>());
        rsrcNeighbors.add(src);
    }

    /**
     * Get the vertex set.
     *
     * @return the vertex set
     */
    public synchronized Set<E> getVertexSet() {
        return vertexSet;
    }

    /**
     * Check if the graph contains a vertex.
     *
     * @param e the vertex to check
     * @return true if the vertex exists
     */
    public synchronized boolean containsVertex(E e) {
        return vertexSet.contains(e);
    }

    /**
     * Get the neighbors of a vertex.
     *
     * @param vertex the vertex
     * @return the neighbors
     */
    public synchronized Iterable<E> getNeighbors(E vertex) {
        Set<E> neighbors = this.edges.get(vertex);
        if (neighbors == null) {
            return Collections.emptyList();
        } else {
            return Collections.unmodifiableSet(neighbors);
        }
    }

    /**
     * Get the reverse neighbors of a vertex.
     *
     * @param vertex the vertex
     * @return the reverse neighbors
     */
    public synchronized Iterable<E> getReverseNeighbors(E vertex) {
        Set<E> neighbors = this.reverseEdges.get(vertex);
        if (neighbors == null) {
            return Collections.emptyList();
        } else {
            return Collections.unmodifiableSet(neighbors);
        }
    }

    /**
     * Get the number of neighbors for a vertex.
     *
     * @param vertex the vertex
     * @return the number of neighbors
     */
    public synchronized int getNeighborsSize(E vertex) {
        if (this.edges.get(vertex) == null) {
            return 0;
        }
        return this.edges.get(vertex).size();
    }
}
