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

import com.examples.with.different.packagename.objectconstruction.acctinqrq.AcctInqRq;
import com.examples.with.different.packagename.objectconstruction.cascadecall.CascadingCallExample;
import com.examples.with.different.packagename.objectconstruction.ocgexample.longgettercase.Parent;
import com.examples.with.different.packagename.objectconstruction.set2.Target2;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesis-level coverage for the fixture groups ported from the fork's EvoObj suite (plan §6.1,
 * §10 {@code tests/}), exercising OCG features not covered by the M2/M3 fixtures:
 *
 * <ul>
 *   <li>{@code cascadecall} — a field settable only through a cascaded call chain (setter-search
 *       depth);</li>
 *   <li>{@code set2} — layered composition {@code OuterMost -> Layer1 -> Layer2 -> InnerMost};</li>
 *   <li>{@code ocgexample/longgettercase} — a deep getter/field chain on {@code this};</li>
 *   <li>{@code acctinqrq} — the paper's deep nested-object tree (12 classes), a graph-size/robustness
 *       guard.</li>
 * </ul>
 *
 * Each test analyzes the fixture with the object rule on and drives the real chromosome factory,
 * checking that a template targeting a state-bearing branch is produced and executes.
 */
public class PortedFixtureSynthesisTest {

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

    /**
     * Drives the factory against the branch with the most object state and asserts that at least one
     * produced test contains the target-method call and executes.
     */
    private void assertFactoryBuildsExecutableTemplate(Class<?> clazz, String methodName, int paramNum) {
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

    @Test
    public void cascadedCallField() {
        assertFactoryBuildsExecutableTemplate(CascadingCallExample.class, "targetM", 0);
    }

    @Test
    public void layeredComposition() {
        assertFactoryBuildsExecutableTemplate(Target2.class, "crossLayer", 1);
    }

    @Test
    public void longGetterChain() {
        assertFactoryBuildsExecutableTemplate(Parent.class, "method", 0);
    }

    /**
     * The deep nested-object tree (paper §5.2 shape). This is primarily a robustness guard: the
     * analysis must produce a non-empty graph and the factory must run without throwing on such a
     * large graph (cf. the {@code OtherVariableWrapper} hardening).
     */
    @Test
    public void deepNestedObjectTreeIsRobust() {
        analyze(AcctInqRq.class, "equals", 1);
        Branch stateBranch = stateBearingBranch();

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        for (int attempt = 0; attempt < 10; attempt++) {
            RandomLengthTestFactory.workingBranch4ObjectGraph = stateBranch;
            TestChromosome chromosome = factory.getChromosome();
            assertNotNull(chromosome.getTestCase(), "synthesis on a deep tree must not fail");
        }
    }
}
