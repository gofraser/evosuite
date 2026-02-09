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

import org.jgrapht.Graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * The <a href="http://en.wikipedia.org/wiki/Floyd-Warshall_algorithm">
 * Floyd-Warshall algorithm</a> finds all shortest paths (all n^2 of them) in
 * O(n^3) time. This also works out the graph diameter during the process.
 *
 * @author Tom Larkworthy
 */
public class FloydWarshall<V, E> {

    private final Map<V, Integer> vertexIndices;
    private final double[][] dist;
    private double diameter = 0.0;

    /**
     * Constructs the shortest path array for the given graph.
     *
     * @param g   input graph
     */
    public FloydWarshall(Graph<V, E> g) {
        List<V> vertices = new ArrayList<>(g.vertexSet());
        int n = vertices.size();
        vertexIndices = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            vertexIndices.put(vertices.get(i), i);
        }

        dist = new double[n][n];

        // Initialize distances
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    dist[i][j] = 0.0;
                } else {
                    V v1 = vertices.get(i);
                    V v2 = vertices.get(j);
                    E e = g.getEdge(v1, v2);
                    if (e != null) {
                        dist[i][j] = g.getEdgeWeight(e);
                    } else {
                        dist[i][j] = Double.POSITIVE_INFINITY;
                    }
                }
            }
        }

        // Floyd-Warshall algorithm
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                if (dist[i][k] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    if (dist[k][j] != Double.POSITIVE_INFINITY) {
                        dist[i][j] = Math.min(dist[i][j], dist[i][k] + dist[k][j]);
                    }
                }
            }
        }

        computeDiameter();
    }

    private void computeDiameter() {
        diameter = 0.0;
        for (double[] row : dist) {
            for (double d : row) {
                if (d != Double.POSITIVE_INFINITY) {
                    diameter = Math.max(diameter, d);
                }
            }
        }
    }

    /**
     * Retrieves the shortest distance between two vertices.
     *
     * @param v1 first vertex
     * @param v2 second vertex
     * @return distance, or positive infinity if no path
     */
    public double shortestDistance(V v1, V v2) {
        Integer i = vertexIndices.get(v1);
        Integer j = vertexIndices.get(v2);

        if (i == null || j == null) {
            throw new IllegalArgumentException("Vertices must be in the graph");
        }
        return dist[i][j];
    }

    /**
     * <p>Getter for the field <code>diameter</code>.</p>
     *
     * @return diameter computed for the graph
     */
    public double getDiameter() {
        return diameter;
    }
}
