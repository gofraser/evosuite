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
package org.evosuite.testcase.synthesizer.var;

import com.examples.with.different.packagename.objectconstruction.tuning.CastParameterExample;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A parameter read only through a downcast is synthesised as the cast type. The cast type is
 * checked against the declared parameter type taken from the method descriptor, which must be
 * parsed correctly when the method also has primitive (or array) parameters.
 */
public class ParameterCastDescriptorTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void castTypeIsFoundForTheOnlyParameter() throws Exception {
        assertEquals(StringBuilder.class.getName(), castTypeFor("objectOnly", 1, 1));
    }

    @Test
    public void castTypeIsFoundForAnObjectParameterAfterAPrimitive() throws Exception {
        assertEquals(StringBuilder.class.getName(), castTypeFor("afterPrimitive", 2, 2),
                "the descriptor (ILjava/lang/Object;)Z must yield java.lang.Object as parameter 2");
    }

    private static String castTypeFor(String methodName, int paramCount, int paramOrder)
            throws Exception {
        analyze(CastParameterExample.class, methodName, paramCount);
        Branch branch = pickStateBearingBranch();
        PartialGraph graph = new ConstructionPathSynthesizer().constructPartialComputationGraph(branch);
        ParameterVariableWrapper parameter = null;
        for (DepVariableWrapper node : graph.getNodes()) {
            if (node instanceof ParameterVariableWrapper && node.var.getParamOrder() == paramOrder) {
                parameter = (ParameterVariableWrapper) node;
            }
        }
        assertNotNull(parameter, "the graph should contain parameter " + paramOrder);
        return parameter.checkCastClassForParameter();
    }

    private static void analyze(Class<?> clazz, String methodName, int paramNum) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH};
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = clazz.getName();
        Method method = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                method = m;
            }
        }
        assertNotNull(method);
        Properties.TARGET_METHOD = methodName + MethodUtil.getSignature(method);
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
