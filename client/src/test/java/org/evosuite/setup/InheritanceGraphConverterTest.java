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

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InheritanceGraphConverterTest {

    @Test
    public void writtenTreeIsReadBackWithTheSameGraphOrder() throws Exception {
        InheritanceTree tree = new InheritanceTree();
        tree.addSuperclass("p/B", "p/A", 0);
        tree.addSuperclass("p/C", "p/A", 0);
        tree.addInterface("p/C", "p/I");
        tree.addInterface("p/B", "p/I");
        tree.addSuperclass("p/D", "p/B", 0);
        tree.addInterface("p/D", "p/I");
        tree.addInterface("p/D", "p/I"); // parallel edge
        tree.addSuperclass("p/E", "p/D", 0);
        tree.removeClass("p/E");

        File file = File.createTempFile("evosuite-itree-", ".xml.gz");
        try {
            InheritanceTreeGenerator.writeInheritanceTree(tree, file);
            InheritanceTree read = InheritanceTreeGenerator.readInheritanceTree(file.getPath());

            assertEquals(describe(tree), describe(read));
            assertEquals(Arrays.asList("p.D", "p.B", "p.I", "p.A"), read.getOrderedSuperclasses("p.D"));
            assertTrue(read.isInterface("p.I"));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void readsTheJGraphT08Format() throws Exception {
        // written by EvoSuite on JGraphT 0.8.3 for B extends A, C extends A, C implements I
        Path file = Files.createTempFile("evosuite-itree-legacy-", ".xml");
        try (InputStream in = getClass().getResourceAsStream("inheritance-tree-jgrapht-0.8.xml")) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            InheritanceTree tree = InheritanceTreeGenerator.readUncompressedInheritanceTree(file.toString());

            assertEquals(Arrays.asList(
                    "B out=[] in=[A]",
                    "A out=[B, C] in=[]",
                    "C out=[] in=[A, I]",
                    "I out=[C] in=[]"), describe(tree));
            assertEquals(Arrays.asList("C", "A", "I"), tree.getOrderedSuperclasses("C"));
            assertTrue(tree.isInterface("I"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> describe(InheritanceTree tree) throws Exception {
        Field field = InheritanceTree.class.getDeclaredField("inheritanceGraph");
        field.setAccessible(true);
        DirectedMultigraph<String, DefaultEdge> graph = (DirectedMultigraph<String, DefaultEdge>) field.get(tree);
        List<String> result = new ArrayList<>();
        for (String vertex : graph.vertexSet()) {
            List<String> out = new ArrayList<>();
            for (DefaultEdge edge : graph.outgoingEdgesOf(vertex)) {
                out.add(graph.getEdgeTarget(edge));
            }
            List<String> in = new ArrayList<>();
            for (DefaultEdge edge : graph.incomingEdgesOf(vertex)) {
                in.add(graph.getEdgeSource(edge));
            }
            result.add(vertex + " out=" + out + " in=" + in);
        }
        return result;
    }
}
