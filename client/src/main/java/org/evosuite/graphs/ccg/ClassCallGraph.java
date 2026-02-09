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
package org.evosuite.graphs.ccg;

import org.evosuite.graphs.EvoSuiteGraph;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Represents the method call structure of a class in a graph.
 *
 * <p>The graph contains a node for each of the classes methods with edges going
 * from a method node to each of its called methods.
 *
 * <p>Edges are labeled with the BytecodeInstruction of the corresponding call.
 *
 * @author Andre Mis
 */
public class ClassCallGraph extends EvoSuiteGraph<ClassCallNode, ClassCallEdge> {

    private final String className;

    private final ClassLoader classLoader;

    private final Map<String, ClassCallNode> methodToNodeMap = new HashMap<>();

    /**
     * Returns the class loader.
     *
     * @return the classLoader
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Constructor for ClassCallGraph.
     *
     * @param className a {@link java.lang.String} object.
     */
    public ClassCallGraph(ClassLoader classLoader, String className) {
        super(ClassCallEdge.class);

        this.className = className;
        this.classLoader = classLoader;

        compute();
    }

    private void compute() {
        Map<String, RawControlFlowGraph> cfgs = GraphPool.getInstance(classLoader).getRawCFGs(className);

        if (cfgs == null) {
            throw new IllegalStateException(
                    "Did not find CFGs for class " + className + " to compute the CCG of");
        }

        // Use TreeMap for deterministic iteration order
        Map<String, RawControlFlowGraph> sortedCfgs = new TreeMap<>(cfgs);

        // add nodes
        for (String method : sortedCfgs.keySet()) {
            ClassCallNode node = new ClassCallNode(method);
            addVertex(node);
            methodToNodeMap.put(method, node);
        }

        // add edges
        for (Map.Entry<String, RawControlFlowGraph> entry : sortedCfgs.entrySet()) {
            String methodName = entry.getKey();
            RawControlFlowGraph rcfg = entry.getValue();
            ClassCallNode methodNode = methodToNodeMap.get(methodName);

            List<BytecodeInstruction> calls = rcfg.determineMethodCallsToOwnClass();
            for (BytecodeInstruction call : calls) {
                ClassCallNode calledMethod = getNodeByMethodName(call.getCalledMethod());
                if (calledMethod != null) {
                    ClassCallEdge e = new ClassCallEdge(call);
                    addEdge(methodNode, calledMethod, e);
                }
            }
        }
    }

    /**
     * Returns the node for the given method name.
     *
     * @param methodName a {@link java.lang.String} object.
     * @return a {@link org.evosuite.graphs.ccg.ClassCallNode} object.
     */
    public ClassCallNode getNodeByMethodName(String methodName) {
        return methodToNodeMap.get(methodName);
    }

    /**
     * Getter for the field <code>className</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getClassName() {
        return className;
    }

    // toDot util

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "CCG_" + className;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String dotSubFolder() {
        return toFileString(className) + "/";
    }
}
