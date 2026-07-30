/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.testcase.synthesizer;

import java.util.ArrayList;

import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PartialGraphTest {

    @Test
    void detectsDependencyCyclesButAcceptsSharedDagNodes() {
        DepVariableWrapper root = node();
        DepVariableWrapper left = node();
        DepVariableWrapper right = node();
        DepVariableWrapper shared = node();
        root.children.add(left);
        root.children.add(right);
        left.children.add(shared);
        right.children.add(shared);

        PartialGraph graph = graph(root, left, right, shared);
        assertFalse(graph.hasDependencyCycle(), "a shared descendant is still a DAG");

        shared.children.add(root);
        assertTrue(graph.hasDependencyCycle(), "a back edge has no valid synthesis order");
    }

    private static DepVariableWrapper node() {
        DepVariableWrapper node = mock(DepVariableWrapper.class);
        node.children = new ArrayList<>();
        node.parents = new ArrayList<>();
        return node;
    }

    private static PartialGraph graph(DepVariableWrapper... nodes) {
        PartialGraph graph = new PartialGraph();
        for (DepVariableWrapper node : nodes) {
            graph.allRelevantNodes.put(node, node);
        }
        return graph;
    }
}
