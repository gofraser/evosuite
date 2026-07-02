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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.legitimization.LegitimizationExample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M4 verification for the EvoObj legitimizer ({@link TestCaseLegitimizer} and the
 * {@code legitimacyDistance} bookkeeping on {@link TestChromosome}).
 *
 * <p>The legitimizer's job (plan §3.6): a synthesized template frequently throws before reaching
 * the target-method call, and the legitimizer runs a small local search that mutates the
 * primitive/null "slots" preceding the throwing statement until execution reaches the target call
 * (legitimacy distance 0). These tests use a controlled fixture whose pre-target failure is a
 * divide-by-zero fixable by a single primitive mutation, so the M4 gate — "templates reach the
 * target call (distance 0) within budget" — is checked deterministically.
 */
public class ObjectLegitimizationTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        ObjectConstructionContext.reset();
    }

    private static String signatureOf(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m.getName() + MethodUtil.getSignature(m);
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    private static Method reflectMethod(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m;
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum + " in " + clazz);
    }

    /** Set up the analysis/execution context targeting {@code targetM}. */
    private static void setup() {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        // keep the per-template budget short so the test never stalls
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 5;

        Properties.TARGET_CLASS = LegitimizationExample.class.getName();
        Properties.TARGET_METHOD = signatureOf(LegitimizationExample.class, "targetM", 1);
        Properties.APPLY_OBJECT_RULE = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed", e);
        }

        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);
        TestCaseExecutor.initExecutor();
    }

    /**
     * Build {@code new LegitimizationExample(); risky(divisor); targetM(value);}. When
     * {@code divisor == 0} the {@code risky} call throws before the target call is reached, giving a
     * positive legitimacy distance that the legitimizer must drive to 0.
     */
    private static TestCase buildTest(int divisor, int value) throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();
        Class<?> clazz = LegitimizationExample.class;

        Constructor<?> ctor = clazz.getDeclaredConstructor();
        VariableReference callee =
                factory.addConstructor(test, new GenericConstructor(ctor, clazz), test.size(), 0);

        IntPrimitiveStatement divisorStmt = new IntPrimitiveStatement(test);
        divisorStmt.setValue(divisor);
        VariableReference divisorRef = test.addStatement(divisorStmt, test.size());

        Method risky = reflectMethod(clazz, "risky", 1);
        MethodStatement riskyCall = new MethodStatement(test, new GenericMethod(risky, clazz),
                callee, Arrays.asList(divisorRef));
        test.addStatement(riskyCall, test.size());

        IntPrimitiveStatement valueStmt = new IntPrimitiveStatement(test);
        valueStmt.setValue(value);
        VariableReference valueRef = test.addStatement(valueStmt, test.size());

        Method targetM = reflectMethod(clazz, "targetM", 1);
        MethodStatement targetCall = new MethodStatement(test, new GenericMethod(targetM, clazz),
                callee, Arrays.asList(valueRef));
        test.addStatement(targetCall, test.size());

        assertNotNull(test.findTargetMethodCallStatement(),
                "test must contain the target-method call");
        return test;
    }

    private static TestChromosome chromosomeFor(TestCase test) {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        return chromosome;
    }

    @Test
    public void distanceIsZeroWhenTargetCallReached() throws Exception {
        setup();
        // divisor != 0: risky succeeds, execution reaches the target call.
        TestChromosome chromosome = chromosomeFor(buildTest(3, 1));
        assertEquals(0.0, chromosome.getLegitimacyDistance(), 0.0,
                "a test that executes through the target call has legitimacy distance 0");
    }

    @Test
    public void distanceIsPositiveWhenBlockedBeforeTarget() throws Exception {
        setup();
        // divisor == 0: risky throws before the target call is reached.
        TestChromosome chromosome = chromosomeFor(buildTest(0, 1));
        assertTrue(chromosome.getLegitimacyDistance() > 0,
                "a test that throws before the target call has a positive legitimacy distance");
    }

    @Test
    public void setAndGetLegitimacyDistance() {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setLegitimacyDistance(4.0);
        // no target-method call in this (empty) test: getLegitimacyDistance recomputes to 0
        Properties.TARGET_METHOD = "does#not()Exist";
        assertEquals(0.0, chromosome.getLegitimacyDistance(), 0.0);
        Properties.TARGET_METHOD = "";
    }

    @Test
    public void legitimizeReachesTargetCallWithinBudget() throws Exception {
        setup();
        TestChromosome blocked = chromosomeFor(buildTest(0, 1));
        double initialDistance = blocked.getLegitimacyDistance();
        assertTrue(initialDistance > 0, "the blocked template should start with a positive distance");

        TestChromosome legitimized =
                TestCaseLegitimizer.getInstance().legitimize(blocked, null, null);

        assertNotNull(legitimized, "legitimize should return a chromosome");
        assertEquals(0.0, legitimized.getLegitimacyDistance(), 0.0,
                "legitimization should reach the target call (distance 0) within budget");
    }
}
