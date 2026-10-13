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
package org.evosuite.testcase.synthesizer;

import com.examples.with.different.packagename.objectconstruction.tuning.ManyFieldsExample;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesis walks the construction graph in the iteration order of its node map. That order
 * must be a function of the graph, not of the identity hash codes of the wrapper objects, or two
 * runs with the same seed can synthesise different tests. Building the same graph repeatedly
 * creates fresh wrappers each time, so their identity hash codes differ while the graph does not.
 */
public class PartialGraphNodeOrderTest {

    private static final int REBUILDS = 20;

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Disabled("D10: graph node order depends on identity hashes (degenerate wrapper hashCode)"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void nodeOrderDoesNotDependOnWrapperIdentity() throws Exception {
        analyze(ManyFieldsExample.class, "target");
        Branch branch = pickStateBearingBranch();

        Set<List<String>> orders = new LinkedHashSet<>();
        for (int i = 0; i < REBUILDS; i++) {
            PartialGraph graph = new ConstructionPathSynthesizer().constructPartialComputationGraph(branch);
            List<String> order = new ArrayList<>();
            int fieldNodes = 0;
            for (DepVariableWrapper node : graph.getNodes()) {
                order.add(node.var.getInstruction().toString());
                if (node.var.getType() == DepVariable.INSTANCE_FIELD) {
                    fieldNodes++;
                }
            }
            assertTrue(fieldNodes >= 12,
                    "precondition: the graph holds the twelve field nodes, found " + fieldNodes);
            orders.add(order);
        }

        assertEquals(1, orders.size(),
                "rebuilding the same graph produced " + orders.size() + " different node orders");
    }

    private static void analyze(Class<?> clazz, String methodName) throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = methodName + MethodUtil.getSignature(clazz.getMethod(methodName));
        Properties.APPLY_OBJECT_RULE = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, Collections.singletonList(cp));
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + clazz, e);
        }
    }

    private static Branch pickStateBearingBranch() {
        Map<Branch, Set<DepVariable>> graph = TestGenerationContext.getInstance()
                .getObjectRuleRuntime().getBranchInterestedVarsMap().get(Properties.TARGET_METHOD);
        assertNotNull(graph, "the target method should have an OCG entry");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> e : graph.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > bestSize) {
                best = e.getKey();
                bestSize = e.getValue().size();
            }
        }
        assertNotNull(best, "expected at least one branch depending on object state");
        return best;
    }
}
