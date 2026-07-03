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
package org.evosuite.testcase.factories;

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
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.synthesizer.TestCaseLegitimizer;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.instancefield.PublicInstanceFieldExample;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M5 verification for the search-integration hook in {@link RandomLengthTestFactory}
 *. Unlike the M2–M4 tests, which drive the analysis/synthesis/legitimization APIs
 * directly, this test exercises the actual chromosome factory: with {@code apply_object_rule=true},
 * a working branch selected, and the application probability forced to 1, {@link
 * RandomLengthTestFactory#getChromosome()} must turn a freshly generated random test into an
 * object-construction template that constructs the state the target branch reads and reaches the
 * target-method call.
 *
 * <p>The regression test asserts that with {@code apply_object_rule=false} the factory follows the
 * unchanged upstream path and never touches the EvoObj machinery.
 */
public class ObjectRuleFactoryTest {

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

    private static void analyze(Class<?> clazz, String methodName, int paramNum, boolean objectRule) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.CHROMOSOME_LENGTH = 40;
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 5;

        Properties.TARGET_CLASS = clazz.getName();
        Properties.TARGET_METHOD = signatureOf(clazz, methodName, paramNum);
        Properties.APPLY_OBJECT_RULE = objectRule;

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

    /** Pick the branch with the largest OCG leaf set (the most object state to construct). */
    private static Branch pickStateBearingBranch() {
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

    private static boolean hasConstructorOf(TestCase test, String typeName) {
        for (int i = 0; i < test.size(); i++) {
            if (test.getStatement(i) instanceof ConstructorStatement
                    && test.getStatement(i).getReturnValue().getClassName().equals(typeName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The M5 gate: driven through the real factory, the object rule turns a random test into a
     * template that constructs the {@code Student} held in the branch-read field and calls the
     * target method {@code targetM}.
     */
    @Test
    public void factoryAppliesObjectRuleTemplate() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1, true);
        Branch stateBranch = pickStateBearingBranch();
        TestCaseLegitimizer.startTime = System.currentTimeMillis();
        // Force the object rule to always apply when a target call is present.
        Properties.P_OBJECT_RULE_APPLICATION = 1.0;

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        String studentType =
                "com.examples.with.different.packagename.objectconstruction.instancefield.Student";

        boolean templateApplied = false;
        for (int attempt = 0; attempt < 60 && !templateApplied; attempt++) {
            RandomLengthTestFactory.workingBranch4ObjectGraph = stateBranch;
            TestChromosome chromosome = factory.getChromosome();
            TestCase test = chromosome.getTestCase();

            if (test.findTargetMethodCallStatement() != null && hasConstructorOf(test, studentType)) {
                templateApplied = true;
                // The synthesized template must still execute (produce a result).
                assertNotNull(TestCaseExecutor.getInstance().execute(test, Integer.MAX_VALUE),
                        "the object-rule template should execute");
            }
        }

        assertTrue(templateApplied,
                "the factory should produce at least one object-construction template that builds the "
                        + "Student read by the target branch and calls the target method");
    }

    /**
     * With {@code apply_object_rule=true} and a chromosome length of 1, the object-rule generation
     * path must return an empty test rather than spin forever ({@code Randomness.nextInt(1)} is
     * always 0, so the old retry loop never terminated).
     */
    @Test
    public void factoryReturnsForSizeOneWithoutHanging() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1, true);
        Properties.CHROMOSOME_LENGTH = 1;
        RandomLengthTestFactory.workingBranch4ObjectGraph = null;

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        TestChromosome chromosome = assertTimeoutPreemptively(Duration.ofSeconds(10),
                factory::getChromosome,
                "the object-rule path must not hang when the requested size is 1");

        assertNotNull(chromosome.getTestCase(), "a chromosome should still be produced");
        assertTrue(chromosome.getTestCase().size() == 0,
                "size 1 should default to an empty test case");
    }

    /**
     * Regression: with {@code apply_object_rule=false} the factory follows the unchanged upstream
     * path — it never sets a working branch or invokes legitimization, and produces valid random
     * tests within the configured length bound.
     */
    @Test
    public void factoryUnchangedWhenObjectRuleDisabled() {
        analyze(PublicInstanceFieldExample.class, "targetM", 1, false);
        RandomLengthTestFactory.workingBranch4ObjectGraph = null;
        int trialsBefore = RandomLengthTestFactory.legitimizationTrials;

        RandomLengthTestFactory factory = new RandomLengthTestFactory();
        for (int i = 0; i < 20; i++) {
            TestChromosome chromosome = factory.getChromosome();
            assertNotNull(chromosome.getTestCase(), "the default path must produce a test");
            assertTrue(chromosome.getTestCase().size() < Properties.CHROMOSOME_LENGTH,
                    "a random test must respect the length bound");
        }

        assertTrue(RandomLengthTestFactory.workingBranch4ObjectGraph == null,
                "the disabled object rule must not select a working branch");
        assertTrue(RandomLengthTestFactory.legitimizationTrials == trialsBefore,
                "the disabled object rule must not run legitimization");
    }
}
