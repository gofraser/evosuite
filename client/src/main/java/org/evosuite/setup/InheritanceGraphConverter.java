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
package org.evosuite.setup;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the inheritance graph of an {@link InheritanceTree} as its vertex list and its
 * edge list, both in graph order, instead of the graph library's internal fields:
 *
 * <pre>{@code
 * <inheritanceGraph>
 *   <vertices><v>java.io.FilterInputStream</v>...</vertices>
 *   <edges><e super="java.io.InputStream" sub="java.io.FilterInputStream"/>...</edges>
 * </inheritanceGraph>
 * }</pre>
 *
 * <p>Re-adding the vertices and then the edges in that order rebuilds the graph with the
 * same vertex order and the same order of incoming and outgoing edges at every vertex, so
 * the traversals over it see the classes in the same order.
 *
 * <p>Trees written by the field-by-field serialisation of JGraphT 0.8 (the earlier format
 * of the JDK inheritance resources and of cached {@code inheritance_file}s) are still
 * read: their {@code edgeMap} holds every edge, in edge order, before any reference to
 * it, and their {@code vertexMapDirected} holds the vertices in vertex order.
 */
public class InheritanceGraphConverter implements Converter {

    private static final String VERTICES = "vertices";
    private static final String VERTEX = "v";
    private static final String EDGES = "edges";
    private static final String EDGE = "e";
    private static final String SUPER = "super";
    private static final String SUB = "sub";

    @Override
    public boolean canConvert(Class type) {
        return DirectedMultigraph.class.equals(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        DirectedMultigraph<String, DefaultEdge> graph = (DirectedMultigraph<String, DefaultEdge>) source;
        writer.startNode(VERTICES);
        for (String vertex : graph.vertexSet()) {
            writer.startNode(VERTEX);
            writer.setValue(vertex);
            writer.endNode();
        }
        writer.endNode();
        writer.startNode(EDGES);
        for (DefaultEdge edge : graph.edgeSet()) {
            writer.startNode(EDGE);
            writer.addAttribute(SUPER, graph.getEdgeSource(edge));
            writer.addAttribute(SUB, graph.getEdgeTarget(edge));
            writer.endNode();
        }
        writer.endNode();
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        List<String> vertices = new ArrayList<>();
        List<String[]> edges = new ArrayList<>();
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            switch (reader.getNodeName()) {
                case VERTICES:
                    readVertices(reader, vertices);
                    break;
                case EDGES:
                    readEdges(reader, edges);
                    break;
                case "edgeMap":
                    readLegacyEdges(reader, edges);
                    break;
                case "specifics":
                    readLegacyVertices(reader, vertices);
                    break;
                default:
                    // the legacy format's graph settings, implied by the graph type
                    break;
            }
            reader.moveUp();
        }

        DirectedMultigraph<String, DefaultEdge> graph = new DirectedMultigraph<>(DefaultEdge.class);
        for (String vertex : vertices) {
            graph.addVertex(vertex);
        }
        for (String[] edge : edges) {
            if (!graph.containsVertex(edge[0]) || !graph.containsVertex(edge[1])) {
                throw new ConversionException("Inheritance edge " + edge[0] + " -> " + edge[1]
                        + " refers to a class that is not in the vertex list");
            }
            graph.addEdge(edge[0], edge[1]);
        }
        return graph;
    }

    private static void readVertices(HierarchicalStreamReader reader, List<String> vertices) {
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            vertices.add(reader.getValue());
            reader.moveUp();
        }
    }

    private static void readEdges(HierarchicalStreamReader reader, List<String[]> edges) {
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            edges.add(new String[]{reader.getAttribute(SUPER), reader.getAttribute(SUB)});
            reader.moveUp();
        }
    }

    /**
     * Reads {@code <entry><DefaultEdge><source/><target/></DefaultEdge><reference/></entry>}.
     */
    private static void readLegacyEdges(HierarchicalStreamReader reader, List<String[]> edges) {
        while (reader.hasMoreChildren()) {
            reader.moveDown(); // entry
            reader.moveDown(); // the edge itself, the map key
            if (reader.getAttribute("reference") != null) {
                throw new ConversionException("Unexpected edge reference in the legacy inheritance graph");
            }
            String source = null;
            String target = null;
            while (reader.hasMoreChildren()) {
                reader.moveDown();
                if ("source".equals(reader.getNodeName())) {
                    source = reader.getValue();
                } else if ("target".equals(reader.getNodeName())) {
                    target = reader.getValue();
                }
                reader.moveUp();
            }
            if (source == null || target == null) {
                throw new ConversionException("Legacy inheritance edge without source or target");
            }
            edges.add(new String[]{source, target});
            reader.moveUp();
            reader.moveUp();
        }
    }

    /**
     * Reads the keys of {@code <specifics><vertexMapDirected><entry><string/>...}.
     */
    private static void readLegacyVertices(HierarchicalStreamReader reader, List<String> vertices) {
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            if ("vertexMapDirected".equals(reader.getNodeName())) {
                while (reader.hasMoreChildren()) {
                    reader.moveDown(); // entry
                    reader.moveDown(); // the vertex, the map key
                    vertices.add(reader.getValue());
                    reader.moveUp();
                    reader.moveUp();
                }
            }
            reader.moveUp();
        }
    }
}
