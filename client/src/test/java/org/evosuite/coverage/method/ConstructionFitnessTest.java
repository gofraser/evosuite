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
package org.evosuite.coverage.method;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification of the E9 construction-fitness layering ({@link ConstructionFitness}): the graded
 * distance falls in the correct band per scenario, is monotone within and across bands, and is 0
 * exactly when the plain binary goal is covered. Also checks the flag-off regression (binary values
 * unchanged).
 *
 * <p>Test cases are built from SUT-classloader classes so that the executed code is the instrumented
 * version — the guard distance (layers b/c) reads branch distances from the execution trace, which
 * only the instrumented SUT records (in a real search the SUT is always instrumented).
 */
public class ConstructionFitnessTest {

    private static final String SETTER_STATE =
            "com.examples.with.different.packagename.objectconstruction.construction.SetterState";
    private static final String CONSTRUCTOR_VALIDATED =
            "com.examples.with.different.packagename.objectconstruction.construction.ConstructorValidated";

    @AfterEach
    public void tearDown() {
        Properties.TARGET_METHOD = "";
        Properties.TARGET_CLASS = "";
        Properties.CONSTRUCTION_FITNESS = false;
        TestGenerationContext.getInstance().resetContext();
    }

    /** Analyze the class and return its <em>instrumented</em> {@code Class} (from the SUT loader). */
    private static Class<?> analyze(String className) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH, Criterion.METHODNOEXCEPTION };
        Properties.ASSERTIONS = false;
        Properties.RANDOM_SEED = 42L;
        Randomness.setSeed(42L);
        Properties.TARGET_CLASS = className;
        Properties.CONSTRUCTION_FITNESS = true;

        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        try {
            DependencyAnalysis.analyzeClass(className, classpath);
            LoopCounter.getInstance().setActive(false);
            ExceptionMapGenerator.initializeExceptionMap(className);
            TestCaseExecutor.initExecutor();
            return TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new AssertionError("analyzeClass failed for " + className, e);
        }
    }

    private static String sig(Class<?> clazz, String methodName, int paramNum) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == paramNum) {
                return m.getName() + MethodUtil.getSignature(m);
            }
        }
        throw new IllegalStateException("no method " + methodName + "/" + paramNum);
    }

    private static VariableReference addInt(TestCase test, int value) {
        return test.addStatement(new IntPrimitiveStatement(test, value), test.size());
    }

    private static VariableReference construct(TestCase test, Class<?> clazz, VariableReference... args)
            throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = int.class;
        }
        Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
        List<VariableReference> params = new ArrayList<>();
        Collections.addAll(params, args);
        return test.addStatement(new ConstructorStatement(test, new GenericConstructor(ctor, clazz), params),
                test.size());
    }

    private static void call(TestCase test, Class<?> clazz, VariableReference callee, String method,
            VariableReference... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = int.class;
        }
        Method m = clazz.getDeclaredMethod(method, paramTypes);
        List<VariableReference> params = new ArrayList<>();
        Collections.addAll(params, args);
        test.addStatement(new MethodStatement(test, new GenericMethod(m, clazz), callee, params), test.size());
    }

    private static double distance(Class<?> clazz, String method, int paramNum, TestCase test) {
        ExecutionResult result = TestCaseExecutor.runTest(test);
        return ConstructionFitness.gradedDistance(clazz.getName(), sig(clazz, method, paramNum), test, result);
    }

    @Test
    public void setterStateLayering() throws Exception {
        Class<?> sut = analyze(SETTER_STATE);

        // (a) no call, no state set -> band [2,3], top of band.
        TestCase noCall = new DefaultTestCase();
        construct(noCall, sut);
        double dNoCall = distance(sut, "run", 0, noCall);

        // (a) no call, but the setter is present -> still band [2,3], but sigma drops.
        TestCase noCallButSetter = new DefaultTestCase();
        VariableReference obj = construct(noCallButSetter, sut);
        call(noCallButSetter, sut, obj, "setValue", addInt(noCallButSetter, 5));
        double dNoCallSetter = distance(sut, "run", 0, noCallButSetter);

        // (c) call present but throws inside run() (value defaults to 0) -> band (0,1).
        TestCase throwsInside = new DefaultTestCase();
        VariableReference obj2 = construct(throwsInside, sut);
        call(throwsInside, sut, obj2, "run");
        double dThrows = distance(sut, "run", 0, throwsInside);

        // (d) covered: value set positive then run() executes cleanly -> 0.
        TestCase covered = new DefaultTestCase();
        VariableReference obj3 = construct(covered, sut);
        call(covered, sut, obj3, "setValue", addInt(covered, 5));
        call(covered, sut, obj3, "run");
        double dCovered = distance(sut, "run", 0, covered);

        assertTrue(dNoCall > 2.0 && dNoCall <= 3.0, "no call -> band (2,3], was " + dNoCall);
        assertTrue(dNoCallSetter >= 2.0 && dNoCallSetter < dNoCall,
                "adding the correct setter lowers sigma within band a (" + dNoCallSetter + " vs " + dNoCall + ")");
        assertTrue(dThrows > 0.0 && dThrows < 1.0, "call throws inside -> band (0,1), was " + dThrows);
        assertEquals(0.0, dCovered, "a clean call is covered -> 0");
        assertTrue(dCovered < dThrows && dThrows < dNoCallSetter,
                "bands must be ordered: 0 < (0,1) < [2,3)");
    }

    @Test
    public void constructorValidatedLayerBDecreasesWithGuard() throws Exception {
        Class<?> sut = analyze(CONSTRUCTOR_VALIDATED);

        double dFar = distance(sut, "run", 0, constructorThenRun(sut, 5));
        double dNear = distance(sut, "run", 0, constructorThenRun(sut, 9));
        double dCovered = distance(sut, "run", 0, constructorThenRun(sut, 10));

        assertTrue(dFar > 1.0 && dFar < 2.0, "constructor throws -> band (1,2), was " + dFar);
        assertTrue(dNear > 1.0 && dNear < 2.0, "constructor throws -> band (1,2), was " + dNear);
        assertTrue(dNear < dFar, "closer-to-valid constructor argument -> smaller distance ("
                + dNear + " < " + dFar + ")");
        assertEquals(0.0, dCovered, "a valid constructor + clean call is covered -> 0");
    }

    private static TestCase constructorThenRun(Class<?> sut, int ctorArg) throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference obj = construct(test, sut, addInt(test, ctorArg));
        call(test, sut, obj, "run");
        return test;
    }

    @Test
    public void flagOffKeepsBinaryFitness() throws Exception {
        Class<?> sut = analyze(SETTER_STATE);
        Properties.CONSTRUCTION_FITNESS = false; // regression: plain binary signal
        Properties.TEST_ARCHIVE = false; // direct getFitness on an unregistered goal, no archive

        MethodNoExceptionCoverageTestFitness goal =
                new MethodNoExceptionCoverageTestFitness(SETTER_STATE, sig(sut, "run", 0));

        TestCase throwsInside = new DefaultTestCase();
        VariableReference obj = construct(throwsInside, sut);
        call(throwsInside, sut, obj, "run");
        TestChromosome c1 = new TestChromosome();
        c1.setTestCase(throwsInside);
        assertEquals(1.0, goal.getFitness(c1, TestCaseExecutor.runTest(throwsInside)),
                "flag off: uncovered method-no-exception fitness must be the binary 1.0");

        TestCase covered = new DefaultTestCase();
        VariableReference obj2 = construct(covered, sut);
        call(covered, sut, obj2, "setValue", addInt(covered, 5));
        call(covered, sut, obj2, "run");
        TestChromosome c2 = new TestChromosome();
        c2.setTestCase(covered);
        assertEquals(0.0, goal.getFitness(c2, TestCaseExecutor.runTest(covered)),
                "flag off: covered method-no-exception fitness must be the binary 0.0");
    }
}
