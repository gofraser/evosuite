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

import java.io.PrintWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes a directed graph in the Graphviz .dot format.
 *
 * <p>Vertices are numbered from 1 in the graph's vertex order and labelled with their
 * {@code toString()}; edges are labelled with theirs. This is the output of the
 * {@code DOTExporter} of JGraphT 0.8, which later JGraphT releases moved into a separate
 * artifact with further dependencies.
 *
 * @param <V> the vertex type
 * @param <E> the edge type
 */
public class DotWriter<V, E> {

    private final ComponentAttributeProvider<V> vertexAttributeProvider;
    private final ComponentAttributeProvider<E> edgeAttributeProvider;

    /**
     * Creates a writer.
     *
     * @param vertexAttributeProvider the vertex attributes, or {@code null} for none
     * @param edgeAttributeProvider   the edge attributes, or {@code null} for none
     */
    public DotWriter(ComponentAttributeProvider<V> vertexAttributeProvider,
                     ComponentAttributeProvider<E> edgeAttributeProvider) {
        this.vertexAttributeProvider = vertexAttributeProvider;
        this.edgeAttributeProvider = edgeAttributeProvider;
    }

    /**
     * Writes the graph.
     *
     * @param writer the destination, flushed but not closed
     * @param graph  the directed graph to write
     */
    public void write(Writer writer, Graph<V, E> graph) {
        PrintWriter out = new PrintWriter(writer);
        Map<V, Integer> ids = new HashMap<>();
        out.println("digraph G {");
        for (V v : graph.vertexSet()) {
            out.print("  " + idOf(ids, v));
            renderAttributes(out, v.toString(),
                    vertexAttributeProvider == null ? null : vertexAttributeProvider.getComponentAttributes(v));
            out.println(";");
        }
        for (E e : graph.edgeSet()) {
            out.print("  " + idOf(ids, graph.getEdgeSource(e)) + " -> " + idOf(ids, graph.getEdgeTarget(e)));
            renderAttributes(out, e.toString(),
                    edgeAttributeProvider == null ? null : edgeAttributeProvider.getComponentAttributes(e));
            out.println(";");
        }
        out.println("}");
        out.flush();
    }

    private int idOf(Map<V, Integer> ids, V v) {
        return ids.computeIfAbsent(v, k -> ids.size() + 1);
    }

    private static void renderAttributes(PrintWriter out, String label, Map<String, String> attributes) {
        out.print(" [ ");
        out.print("label=\"" + label + "\" ");
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey().equals("label")) {
                    continue;
                }
                out.print(entry.getKey() + "=\"" + entry.getValue() + "\" ");
            }
        }
        out.print("]");
    }
}
