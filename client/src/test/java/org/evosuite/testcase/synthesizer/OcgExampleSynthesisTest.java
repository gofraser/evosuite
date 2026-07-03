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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesis-level coverage for the {@code ocgexample} corner cases ported from the fork's EvoObj
 * suite, targeting code paths not otherwise exercised:
 *
 * <ul>
 *   <li>{@code arraylistexample} — a {@code List} field whose element the branch reads (Collection
 *       element generation);</li>
 *   <li>{@code recursivecase} — a self-typed field (recursion in the object graph);</li>
 *   <li>{@code arrayelementaccess} — an array-element read through nested fields (array-element
 *       synthesis);</li>
 *   <li>{@code directsetleafnode} — nested leaf fields set through direct field assignment;</li>
 *   <li>{@code parameterref} — nested state compared against a method parameter.</li>
 * </ul>
 *
 * Each drives the real chromosome factory and checks a template targeting the state-bearing branch
 * is produced and executes.
 */
public class OcgExampleSynthesisTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        RandomLengthTestFactory.workingBranch4ObjectGraph = null;
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m.getName() + MethodUtil.getSignature(m);
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    private static void analyze(Class<?> clazz, String methodName, int paramNum) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.CHROMOSOME_LENGTH = 40;
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 3;
        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
        Properties.APPLY_OBJECT_RULE = true;
        Properties.P_OBJECT_RULE_APPLICATION = 1.0;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + clazz, e);
        }

        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    private static Branch stateBearingBranch() {
        Map<Branch, Set<DepVariable>> map =
                ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
        assertNotNull(map, "the target method should have an OCG entry");
        Branch best = null;
        int bestSize = 0;
        for (Map.Entry<Branch, Set<DepVariable>> e : map.entrySet()) {
            if (e.getValue() != null && e.getValue().size() > bestSize) {
                best = e.getKey();
                bestSize = e.getValue().size();
            }
        }
        assertNotNull(best, "expected at least one branch depending on object state");
        return best;
    }

    private void assertFactoryBuildsExecutableTemplate(String className, String methodName, int paramNum)
            throws Exception {
        Class<?> clazz = Class.forName(className);
        analyze(clazz, methodName, paramNum);
        Branch stateBranch = stateBearingBranch();

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        boolean produced = false;
        for (int attempt = 0; attempt < 60 && !produced; attempt++) {
            RandomLengthTestFactory.workingBranch4ObjectGraph = stateBranch;
            TestChromosome chromosome = factory.getChromosome();
            TestCase test = chromosome.getTestCase();
            if (test.findTargetMethodCallStatement() != null) {
                produced = true;
                assertNotNull(TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE),
                        "the synthesized template should execute for " + clazz.getSimpleName());
            }
        }
        assertTrue(produced,
                "the factory should build a template calling " + methodName + " for " + clazz.getSimpleName());
    }

    private static final String PKG =
            "com.examples.with.different.packagename.objectconstruction.ocgexample.";

    @Test
    public void collectionFieldElement() throws Exception {
        assertFactoryBuildsExecutableTemplate(PKG + "arraylistexample.Parent", "method", 0);
    }

    @Test
    public void recursiveSelfTypedField() throws Exception {
        assertFactoryBuildsExecutableTemplate(PKG + "recursivecase.LinkedListNode", "method", 0);
    }

    @Test
    public void arrayElementThroughNestedFields() throws Exception {
        assertFactoryBuildsExecutableTemplate(PKG + "arrayelementaccess.Parent", "method", 0);
    }

    @Test
    public void directlySetNestedLeafFields() throws Exception {
        assertFactoryBuildsExecutableTemplate(PKG + "directsetleafnode.Parent", "method", 0);
    }

    @Test
    public void nestedStateComparedToParameter() throws Exception {
        assertFactoryBuildsExecutableTemplate(PKG + "parameterref.Parent", "method", 1);
    }
}
