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
package org.evosuite.testsuite;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.examples.with.different.packagename.FlagExample1;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
public class TestTestSuiteMinimizer {
    private static java.util.Properties currentProperties;
    private Logger minimizerLogger;
    private ListAppender<ILoggingEvent> minimizerLogAppender;
    private boolean minimizerLoggerAdditive;
    private Level minimizerLoggerLevel;

    @BeforeEach
    public void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.getInstance().resetToDefaults();
        Randomness.setSeed(42);
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();
        Randomness.setSeed(42);
        currentProperties = (java.util.Properties) System.getProperties().clone();

        minimizerLogger = (Logger) LoggerFactory.getLogger(TestSuiteMinimizer.class);
        minimizerLogAppender = new ListAppender<>();
        minimizerLogAppender.start();
        minimizerLogger.addAppender(minimizerLogAppender);
        minimizerLoggerAdditive = minimizerLogger.isAdditive();
        minimizerLoggerLevel = minimizerLogger.getLevel();
        minimizerLogger.setAdditive(false);
        minimizerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    public void tearDown() {
        try {
            TestGenerationContext.getInstance().resetContext();
            System.setProperties(currentProperties);
            Properties.getInstance().resetToDefaults();
        } finally {
            minimizerLogger.detachAppender(minimizerLogAppender);
            minimizerLogger.setAdditive(minimizerLoggerAdditive);
            minimizerLogger.setLevel(minimizerLoggerLevel);
            minimizerLogAppender.stop();
        }
    }

    @Test
    public void minimizeEmptySuite() throws ClassNotFoundException {

        DefaultTestCase test = new DefaultTestCase();

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);
        TestSuiteFitnessFunction ff = new BranchCoverageSuiteFitness();
        double previous_fitness = ff.getFitness(tsc);
        tsc.setFitness(ff, previous_fitness);
        assertEquals(0.0, previous_fitness, 0.0);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(new BranchCoverageFactory());
        MinimizationResult result = minimizer.minimize(tsc, false);
        assertEquals(MinimizationStatus.COMPLETED, result.getStatus());
        assertEquals(MinimizationStopCause.NONE, result.getUnderlyingStopCause());
        assertEquals(1, result.getOriginalTests());
        assertEquals(0, result.getOriginalLength());
        assertEquals(0, result.getFinalTests());
        assertEquals(0, result.getFinalLength());
        assertFalse(result.isIncomplete());
        assertEquals(0, tsc.getTests().size());

        double fitness = ff.getFitness(tsc);
        assertEquals(previous_fitness, fitness, 0.0);
    }

    @Test
    public void minimizeSuiteOnlyWithVariables() {
        DefaultTestCase test = new DefaultTestCase();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, i);
            test.addStatement(ips);
        }

        assertEquals(10, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);
        TestSuiteFitnessFunction ff = new BranchCoverageSuiteFitness();
        double previous_fitness = ff.getFitness(tsc);
        tsc.setFitness(ff, previous_fitness);
        assertEquals(0.0, previous_fitness, 0.0);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(new BranchCoverageFactory());
        minimizer.minimize(tsc, false);
        assertEquals(0, tsc.getTests().size());

        double fitness = ff.getFitness(tsc);
        assertEquals(previous_fitness, fitness, 0.0);
    }

    @Test
    public void minimizeSuiteHalfCoverage() throws ClassNotFoundException, NoSuchFieldException, SecurityException, ConstructionFailedException, NoSuchMethodException {
        Properties.TARGET_CLASS = FlagExample1.class.getCanonicalName();
        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(Properties.TARGET_CLASS);
        GenericClass<?> clazz = GenericClassFactory.get(sut);

        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(clazz.getRawClass().getConstructors()[0], clazz);

        TestFactory testFactory = TestFactory.getInstance();
        testFactory.addConstructor(test, gc, 0, 0);

        List<VariableReference> parameters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, 28234 + i);
            VariableReference vr = test.addStatement(ips, i + 1);
        }

        ConstructorStatement ct = new ConstructorStatement(test, gc, parameters);

        Method m = clazz.getRawClass().getMethod("testMe", new Class<?>[]{int.class});
        GenericMethod method = new GenericMethod(m, sut);
        testFactory.addMethod(test, method, 11, 0);

        assertEquals(12, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);
        TestSuiteFitnessFunction ff = new BranchCoverageSuiteFitness();
        double previous_fitness = ff.getFitness(tsc);
        tsc.setFitness(ff, previous_fitness);
        assertEquals(2.0, previous_fitness, 0.0);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(new BranchCoverageFactory());
        minimizer.minimize(tsc, false);
        assertEquals(1, tsc.getTests().size());
        assertEquals(3, tsc.getTests().get(0).size());
        //assertTrue(tsc.getTests().get(0).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28234;\nflagExample1_0.testMe(int0);\n"));

        double fitness = ff.getFitness(tsc);
        assertEquals(previous_fitness, fitness, 0.0);
    }

    @Test
    public void minimizeSuiteHalfCoverageWithTwoFitnessFunctions() throws ClassNotFoundException, ConstructionFailedException, NoSuchMethodException, SecurityException {
        Properties.TARGET_CLASS = FlagExample1.class.getCanonicalName();
        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(Properties.TARGET_CLASS);
        GenericClass<?> clazz = GenericClassFactory.get(sut);

        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(clazz.getRawClass().getConstructors()[0], clazz);

        TestFactory testFactory = TestFactory.getInstance();
        testFactory.addConstructor(test, gc, 0, 0);

        List<VariableReference> parameters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, 28234 + i);
            VariableReference vr = test.addStatement(ips, i + 1);
        }

        ConstructorStatement ct = new ConstructorStatement(test, gc, parameters);

        Method m = clazz.getRawClass().getMethod("testMe", new Class<?>[]{int.class});
        GenericMethod method = new GenericMethod(m, sut);
        testFactory.addMethod(test, method, 11, 0);

        assertEquals(12, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);

        TestSuiteFitnessFunction branch = new BranchCoverageSuiteFitness();
        double previous_branch_fitness = branch.getFitness(tsc);
        tsc.setFitness(branch, previous_branch_fitness);
        assertEquals(2.0, previous_branch_fitness, 0.0);


        List<TestFitnessFactory<? extends TestFitnessFunction>> factories = new ArrayList<>();
        factories.add(new BranchCoverageFactory());

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(factories);
        minimizer.minimize(tsc, false);
        assertEquals(1, tsc.getTests().size());
        assertEquals(3, tsc.getTests().get(0).size());
        //assertTrue(tsc.getTests().get(0).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28234;\nflagExample1_0.testMe(int0);\n"));

        double branch_fitness = branch.getFitness(tsc);
        assertEquals(previous_branch_fitness, branch_fitness, 0.0);

    }

    @Test
    public void minimizeSuiteFullCoverage() throws ClassNotFoundException, NoSuchFieldException, SecurityException, ConstructionFailedException, NoSuchMethodException {
        Properties.TARGET_CLASS = FlagExample1.class.getCanonicalName();
        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(Properties.TARGET_CLASS);
        GenericClass<?> clazz = GenericClassFactory.get(sut);

        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(clazz.getRawClass().getConstructors()[0], clazz);

        TestFactory testFactory = TestFactory.getInstance();
        testFactory.addConstructor(test, gc, 0, 0);

        List<VariableReference> parameters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, 28234 + i);
            VariableReference vr = test.addStatement(ips, i + 1);
        }

        ConstructorStatement ct = new ConstructorStatement(test, gc, parameters);

        Method m = clazz.getRawClass().getMethod("testMe", new Class<?>[]{int.class});
        GenericMethod method = new GenericMethod(m, sut);
        testFactory.addMethod(test, method, 11, 0);

        parameters = new ArrayList<>();
        for (int i = 12; i < 15; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, i);
            VariableReference vr = test.addStatement(ips, i);
        }
        ct = new ConstructorStatement(test, gc, parameters);
        testFactory.addMethod(test, method, 15, 0);

        assertEquals(16, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);
        TestSuiteFitnessFunction ff = new BranchCoverageSuiteFitness();
        double previous_fitness = ff.getFitness(tsc);
        tsc.setFitness(ff, previous_fitness);
        assertEquals(0.0, previous_fitness, 0.0);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(new BranchCoverageFactory());
        minimizer.minimize(tsc, false);
        assertEquals(1, tsc.getTests().size());
        assertEquals(5, tsc.getTests().get(0).size());
        //assertTrue(tsc.getTests().get(0).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28234;\nint int1 = 28241;\nflagExample1_0.testMe(int1);\nflagExample1_0.testMe(int0);\n"));

        double fitness = ff.getFitness(tsc);
        assertEquals(previous_fitness, fitness, 0.0);
    }

    @Test
    public void minimizeSuiteFullCoverageWithTwoFitnessFunctions() throws ClassNotFoundException, NoSuchFieldException, SecurityException, ConstructionFailedException, NoSuchMethodException {
        Properties.TARGET_CLASS = FlagExample1.class.getCanonicalName();
        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(Properties.TARGET_CLASS);
        GenericClass<?> clazz = GenericClassFactory.get(sut);

        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(clazz.getRawClass().getConstructors()[0], clazz);

        TestFactory testFactory = TestFactory.getInstance();
        testFactory.addConstructor(test, gc, 0, 0);

        List<VariableReference> parameters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, 28234 + i);
            VariableReference vr = test.addStatement(ips, i + 1);
        }

        ConstructorStatement ct = new ConstructorStatement(test, gc, parameters);

        Method m = clazz.getRawClass().getMethod("testMe", new Class<?>[]{int.class});
        GenericMethod method = new GenericMethod(m, sut);
        testFactory.addMethod(test, method, 11, 0);

        parameters = new ArrayList<>();
        for (int i = 12; i < 15; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, i);
            VariableReference vr = test.addStatement(ips, i);
        }
        ct = new ConstructorStatement(test, gc, parameters);
        testFactory.addMethod(test, method, 15, 0);

        assertEquals(16, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);

        TestSuiteFitnessFunction branch = new BranchCoverageSuiteFitness();
        double previous_branch_fitness = branch.getFitness(tsc);
        tsc.setFitness(branch, previous_branch_fitness);
        assertEquals(0.0, previous_branch_fitness, 0.0);

        List<TestFitnessFactory<? extends TestFitnessFunction>> factories = new ArrayList<>();
        factories.add(new BranchCoverageFactory());

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(factories);
        minimizer.minimize(tsc, false);
        assertEquals(1, tsc.getTests().size());
        assertEquals(5, tsc.getTests().get(0).size());
        //assertTrue(tsc.getTests().get(0).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28234;\nint int1 = 28241;\nflagExample1_0.testMe(int1);\nflagExample1_0.testMe(int0);\n"));

        double branch_fitness = branch.getFitness(tsc);
        assertEquals(previous_branch_fitness, branch_fitness, 0.0);

    }

    @Test
    public void minimizeSuiteFullCoverageWithTwoFitnessFunctionsMinimizeTestsEnabled() throws ClassNotFoundException, NoSuchFieldException, SecurityException, ConstructionFailedException, NoSuchMethodException {
        Properties.TARGET_CLASS = FlagExample1.class.getCanonicalName();
        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(Properties.TARGET_CLASS);
        GenericClass<?> clazz = GenericClassFactory.get(sut);

        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(clazz.getRawClass().getConstructors()[0], clazz);

        TestFactory testFactory = TestFactory.getInstance();
        testFactory.addConstructor(test, gc, 0, 0);

        List<VariableReference> parameters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, 28234 + i);
            VariableReference vr = test.addStatement(ips, i + 1);
        }

        ConstructorStatement ct = new ConstructorStatement(test, gc, parameters);

        Method m = clazz.getRawClass().getMethod("testMe", new Class<?>[]{int.class});
        GenericMethod method = new GenericMethod(m, sut);
        testFactory.addMethod(test, method, 11, 0);

        parameters = new ArrayList<>();
        for (int i = 12; i < 15; i++) {
            IntPrimitiveStatement ips = new IntPrimitiveStatement(test, i);
            VariableReference vr = test.addStatement(ips, i);
        }
        ct = new ConstructorStatement(test, gc, parameters);
        testFactory.addMethod(test, method, 15, 0);

        assertEquals(16, test.size());

        TestSuiteChromosome tsc = new TestSuiteChromosome();
        tsc.addTest(test);

        TestSuiteFitnessFunction branch = new BranchCoverageSuiteFitness();
        double previous_branch_fitness = branch.getFitness(tsc);
        tsc.setFitness(branch, previous_branch_fitness);
        assertEquals(0.0, previous_branch_fitness, 0.0);

        List<TestFitnessFactory<? extends TestFitnessFunction>> factories = new ArrayList<>();
        factories.add(new BranchCoverageFactory());

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(factories);
        minimizer.minimize(tsc, true);
        assertEquals(2, tsc.getTests().size());
        assertEquals(3, tsc.getTests().get(0).size());
        assertEquals(3, tsc.getTests().get(1).size());
//        assertTrue(tsc.getTests().get(0).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28234;\nflagExample1_0.testMe(int0);\n"));
//        assertTrue(tsc.getTests().get(1).toCode().equals("FlagExample1 flagExample1_0 = new FlagExample1();\nint int0 = 28241;\nflagExample1_0.testMe(int0);\n"));

        double branch_fitness = branch.getFitness(tsc);
        assertEquals(previous_branch_fitness, branch_fitness, 0.0);
    }

    @Test
    public void testMinimizeSuiteFitnessUpdate() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        // Add 2 statements
        test.addStatement(new IntPrimitiveStatement(test, 1));
        test.addStatement(new IntPrimitiveStatement(test, 2));
        suite.addTest(test);

        // Mock Fitness Function
        final TestFitnessFunction mockFF = new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) { return 0; }
            @Override
            public int compareTo(TestFitnessFunction other) { return 0; }
            @Override
            public int hashCode() { return 0; }
            @Override
            public boolean equals(Object other) { return false; }
            @Override
            public String getTargetClass() { return ""; }
            @Override
            public String getTargetMethod() { return ""; }
        };

        TestFitnessFactory<TestFitnessFunction> mockFactory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return Collections.singletonList(mockFF);
            }
            @Override
            public double getFitness(TestSuiteChromosome suite) {
                int size = suite.totalLengthOfTestCases();
                if (size == 2) return 10.0;
                if (size == 1) return 8.0;
                if (size == 0) return 9.0;
                return 100.0;
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(mockFactory);
        MinimizationResult result = minimizer.minimize(suite, false);

        // Expected: Size 1. If bug present: Size 0.
        assertEquals(MinimizationStatus.COMPLETED, result.getStatus());
        assertEquals(1, result.getFinalLength());
        assertEquals(1, suite.totalLengthOfTestCases());
    }

    @Test
    public void perTestMinimizationReportsPreflightTimeoutAbort() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(new BranchCoverageFactory()),
                () -> MinimizationStopCause.TIMEOUT);
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.PREFLIGHT_ABORTED, result.getStatus());
        assertEquals(MinimizationStopCause.TIMEOUT, result.getUnderlyingStopCause());
        assertEquals(1, result.getOriginalTests());
        assertEquals(1, result.getOriginalLength());
        assertEquals(1, result.getFinalTests());
        assertEquals(1, result.getFinalLength());
        assertLogMessage(Level.WARN, "Minimization preflight aborted at test 0/1");
    }

    @Test
    public void perTestMinimizationPreservesCachedExecutionResults() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        TestFitnessFunction alwaysCovered = new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                return 0.0;
            }

            @Override
            public ExecutionResult runTest(TestCase test) {
                ExecutionResult result = new ExecutionResult(test);
                result.setTrace(nonEmptyTrace());
                return result;
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public int hashCode() {
                return 1;
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public String getTargetClass() {
                return "";
            }

            @Override
            public String getTargetMethod() {
                return "";
            }
        };

        TestFitnessFactory<TestFitnessFunction> factory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return Collections.singletonList(alwaysCovered);
            }

            @Override
            public double getFitness(TestSuiteChromosome suite) {
                return 0.0;
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(factory),
                () -> MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    result.setTrace(nonEmptyTrace());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.COMPLETED, result.getStatus());
        assertEquals(1, suite.size());
        assertNotNull(suite.getTestChromosomes().get(0).getLastExecutionResult());
    }

    @Test
    public void suiteMinimizationReportsTimeoutAbort() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(new BranchCoverageFactory()),
                () -> MinimizationStopCause.TIMEOUT);
        MinimizationResult result = minimizer.minimize(suite, false);

        assertEquals(MinimizationStatus.TIMED_OUT, result.getStatus());
        assertEquals(MinimizationStopCause.TIMEOUT, result.getUnderlyingStopCause());
        assertEquals(1, result.getFinalTests());
        assertEquals(1, result.getFinalLength());
    }

    @Test
    public void suiteMinimizationReportsLowMemoryAbort() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(new BranchCoverageFactory()),
                () -> MinimizationStopCause.LOW_MEMORY);
        MinimizationResult result = minimizer.minimize(suite, false);

        assertEquals(MinimizationStatus.LOW_MEMORY, result.getStatus());
        assertEquals(MinimizationStopCause.LOW_MEMORY, result.getUnderlyingStopCause());
        assertEquals(1, result.getFinalTests());
        assertEquals(1, result.getFinalLength());
    }

    @Test
    public void perTestMinimizationReportsReexecutionFailedForEmptyPreflightTraces() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(new BranchCoverageFactory()),
                () -> MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    result.setTrace(new ExecutionTraceImpl());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.REEXECUTION_FAILED, result.getStatus());
        assertEquals(MinimizationStopCause.NONE, result.getUnderlyingStopCause());
        assertEquals(1, result.getOriginalTests());
        assertEquals(1, result.getOriginalLength());
        assertEquals(1, result.getFinalTests());
        assertEquals(1, result.getFinalLength());
    }

    @Test
    public void disabledResultIsCompleteByConfiguration() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        suite.addTest(test);

        MinimizationResult result = MinimizationResult.disabled(suite);

        assertEquals(MinimizationStatus.DISABLED, result.getStatus());
        assertEquals(MinimizationStopCause.NONE, result.getUnderlyingStopCause());
        assertEquals(1, result.getOriginalTests());
        assertEquals(1, result.getOriginalLength());
        assertEquals(1, result.getFinalTests());
        assertEquals(1, result.getFinalLength());
        assertFalse(result.isIncomplete());
    }

    @Test
    public void failedMinimizationRestoresDeepSnapshot() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        test.addStatement(new IntPrimitiveStatement(test, 2));
        suite.addTest(test);

        TestFitnessFactory<TestFitnessFunction> throwingFactory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return Collections.emptyList();
            }

            @Override
            public double getFitness(TestSuiteChromosome suite) {
                suite.getTestChromosomes().get(0).getTestCase().remove(0);
                throw new RuntimeException("boom");
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(throwingFactory);
        MinimizationResult result = minimizer.minimize(suite, false);

        assertEquals(MinimizationStatus.FAILED, result.getStatus());
        assertEquals(2, result.getFinalLength());
        assertEquals(2, suite.totalLengthOfTestCases());
        assertEquals(2, suite.getTests().get(0).size());
        TestCase restored = suite.getTestChromosomes().get(0).getTestCase();
        assertNotSame(test, restored);
        assertEquals(Integer.valueOf(1),
                ((IntPrimitiveStatement) restored.getStatement(0)).getValue());
        assertEquals(Integer.valueOf(2),
                ((IntPrimitiveStatement) restored.getStatement(1)).getValue());
        assertLogMessage(Level.ERROR, "Minimization failed (RuntimeException)");
    }

    @Test
    public void perTestMinimizationReportsReexecutionFailedWhenMostTracesCollapse() {
        TestFitnessFunction searchGoal = neverCoveredGoal();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < 4; i++) {
            DefaultTestCase test = new DefaultTestCase();
            test.addStatement(new IntPrimitiveStatement(test, i));
            test.addCoveredGoal(searchGoal);
            suite.addTest(test);
        }

        AtomicInteger probed = new AtomicInteger();
        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(new BranchCoverageFactory()),
                () -> MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    // Only the first test still traces anything, which is the
                    // profile of a suite whose SUT classes were poisoned by the
                    // pre-minimization __STATIC_RESET() sweep.
                    result.setTrace(probed.getAndIncrement() == 0
                            ? nonEmptyTrace()
                            : new ExecutionTraceImpl());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.REEXECUTION_FAILED, result.getStatus());
        assertEquals(4, result.getOriginalTests());
        assertEquals(4, result.getFinalTests());
        assertEquals(4, suite.size());
        assertLogMessage(Level.WARN, "Only 1/4 tests produced non-empty traces on re-execution");
    }

    @Test
    public void perTestMinimizationRevertsWhenCoveredGoalsAreLost() {
        TestFitnessFunction searchGoal = neverCoveredGoal();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < 2; i++) {
            DefaultTestCase test = new DefaultTestCase();
            test.addStatement(new IntPrimitiveStatement(test, i));
            test.addCoveredGoal(searchGoal);
            suite.addTest(test);
        }

        // The probe passes (every test still traces), but no test reproduces
        // the goal, so minimization would ship an empty suite.
        TestFitnessFactory<TestFitnessFunction> factory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return Collections.singletonList(searchGoal);
            }

            @Override
            public double getFitness(TestSuiteChromosome suite) {
                return 1.0;
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(factory),
                () -> MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    result.setTrace(nonEmptyTrace());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.REEXECUTION_FAILED, result.getStatus());
        assertEquals(2, suite.size());
        assertEquals(1, suite.getCoveredGoals().size());
        // Losing the suite's only covered goal is 0% retention, far below the
        // repair threshold, so this reverts wholesale rather than repairing.
        assertLogMessage(Level.ERROR, "Minimization lost 1 of 1 covered goals");
    }

    @Test
    public void perTestMinimizationRepairsSmallCoverageLossInsteadOfReverting() {
        // Three goals that genuinely reproduce on re-execution (one per test,
        // matched by the test's own int value), and one that - like the
        // poisoned __STATIC_RESET() case - was covered during the search
        // phase but no longer reproduces once minimization re-executes it.
        List<TestFitnessFunction> reproducibleGoals = new ArrayList<>();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < 3; i++) {
            TestFitnessFunction goal = indexGoal(i);
            reproducibleGoals.add(goal);
            DefaultTestCase test = new DefaultTestCase();
            test.addStatement(new IntPrimitiveStatement(test, i));
            test.addCoveredGoal(goal);
            suite.addTest(test);
        }
        TestFitnessFunction lostGoal = neverCoveredGoal();
        DefaultTestCase lostTest = new DefaultTestCase();
        lostTest.addStatement(new IntPrimitiveStatement(lostTest, 3));
        lostTest.addCoveredGoal(lostGoal);
        suite.addTest(lostTest);

        List<TestFitnessFunction> allGoals = new ArrayList<>(reproducibleGoals);
        allGoals.add(lostGoal);
        TestFitnessFactory<TestFitnessFunction> factory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return allGoals;
            }

            @Override
            public double getFitness(TestSuiteChromosome suite) {
                return 1.0;
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(factory),
                () -> MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    result.setTrace(nonEmptyTrace());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        // 3 of 4 covered goals (75%) survive minimization, well above the
        // repair threshold, so the lost goal is restored by re-appending the
        // one pre-minimization test that covered it rather than reverting
        // the whole minimized suite.
        assertEquals(MinimizationStatus.COMPLETED, result.getStatus());
        assertEquals(4, suite.getCoveredGoals().size());
        assertTrue(suite.getCoveredGoals().contains(lostGoal));
        assertLogMessage(Level.WARN, "Minimization lost 1 of 4 covered goals");
    }

    @Test
    public void perTestMinimizationStoppedEarlyKeepsOnlyOriginalsStillNeeded() {
        // Three tests, each covering its own goal. Minimization runs out of
        // time right after it has minimized a test for the first goal: the
        // fallback must keep the originals for the two goals it never reached
        // and drop the original the minimized test has made redundant - not
        // keep all three and then have the coverage reconciliation restore
        // the two "lost" ones a second time.
        minimizerLogger.setLevel(Level.INFO);
        boolean[] timeUp = {false};
        List<TestFitnessFunction> goals = new ArrayList<>();
        goals.add(indexGoal(0));
        // Evaluated only after the first goal's minimized test has been kept
        // (the scan of the remaining goals), so flipping the flag here stops
        // minimization before it reaches the second goal.
        goals.add(indexGoal(1, () -> timeUp[0] = true));
        goals.add(indexGoal(2));

        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < 3; i++) {
            DefaultTestCase test = new DefaultTestCase();
            test.addStatement(new IntPrimitiveStatement(test, i));
            test.addStatement(new IntPrimitiveStatement(test, 100 + i));
            test.addCoveredGoal(goals.get(i));
            suite.addTest(test);
        }
        TestFitnessFactory<TestFitnessFunction> factory = new TestFitnessFactory<TestFitnessFunction>() {
            @Override
            public List<TestFitnessFunction> getCoverageGoals() {
                return goals;
            }

            @Override
            public double getFitness(TestSuiteChromosome suite) {
                return 1.0;
            }
        };

        TestSuiteMinimizer minimizer = new TestSuiteMinimizer(
                Collections.singletonList(factory),
                () -> timeUp[0] ? MinimizationStopCause.TIMEOUT : MinimizationStopCause.NONE,
                executedTest -> {
                    ExecutionResult result = new ExecutionResult(executedTest);
                    result.setTrace(nonEmptyTrace());
                    return result;
                });
        MinimizationResult result = minimizer.minimize(suite, true);

        assertEquals(MinimizationStatus.TIMED_OUT, result.getStatus());
        assertEquals(3, suite.size());
        assertEquals(3, suite.getCoveredGoals().size());
        // The first goal's test was minimized to its one relevant statement;
        // the two originals that were never reached stay as they were.
        assertEquals(5, suite.totalLengthOfTestCases());
        assertLogMessage(Level.INFO, "kept 2 of 3 original tests, dropped 1");
        assertLogMessage(Level.INFO, "Minimization preserved coverage");
    }

    private void assertLogMessage(Level level, String message) {
        ILoggingEvent matchingEvent = null;
        for (ILoggingEvent event : minimizerLogAppender.list) {
            if (event.getLevel() == level
                    && event.getFormattedMessage().contains(message)) {
                assertTrue(matchingEvent == null,
                        "Expected at most one matching minimizer log event");
                matchingEvent = event;
            }
        }
        assertNotNull(matchingEvent, "Missing expected minimizer log event: " + message);
    }

    private static TestFitnessFunction neverCoveredGoal() {
        return new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                return 1.0;
            }

            @Override
            public ExecutionResult runTest(TestCase test) {
                ExecutionResult result = new ExecutionResult(test);
                result.setTrace(nonEmptyTrace());
                return result;
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public int hashCode() {
                return 2;
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public String getTargetClass() {
                return "";
            }

            @Override
            public String getTargetMethod() {
                return "";
            }
        };
    }

    /**
     * A goal that genuinely reproduces on re-execution: covered by exactly the
     * test whose first statement is the int primitive {@code index}, mirroring
     * how minimization re-derives coverage rather than relying on a
     * pre-recorded mark.
     */
    private static TestFitnessFunction indexGoal(int index) {
        return indexGoal(index, () -> {
        });
    }

    private static TestFitnessFunction indexGoal(int index, Runnable onEvaluate) {
        return new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                onEvaluate.run();
                // TestCaseMinimizer probes fitness on a statement-deleted clone
                // to decide whether the deletion was safe, so an empty test
                // must be handled (as simply not covering) rather than throw.
                TestCase testCase = individual.getTestCase();
                if (testCase.size() == 0) {
                    return 1.0;
                }
                IntPrimitiveStatement statement = (IntPrimitiveStatement) testCase.getStatement(0);
                return statement.getValue() == index ? 0.0 : 1.0;
            }

            @Override
            public ExecutionResult runTest(TestCase test) {
                ExecutionResult result = new ExecutionResult(test);
                result.setTrace(nonEmptyTrace());
                return result;
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public int hashCode() {
                return 100 + index;
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public String getTargetClass() {
                return "";
            }

            @Override
            public String getTargetMethod() {
                return "";
            }
        };
    }

    private static ExecutionTrace nonEmptyTrace() {
        ExecutionTrace trace = org.mockito.Mockito.mock(ExecutionTrace.class);
        org.mockito.Mockito.when(trace.getCoveredTrueBranches()).thenReturn(Collections.emptySet());
        org.mockito.Mockito.when(trace.getCoveredFalseBranches()).thenReturn(Collections.emptySet());
        org.mockito.Mockito.when(trace.getCoveredBranchlessMethods()).thenReturn(Collections.emptySet());
        org.mockito.Mockito.when(trace.getCoveredMethods()).thenReturn(Collections.emptySet());
        org.mockito.Mockito.when(trace.getCoveredLines()).thenReturn(Collections.singleton(1));
        org.mockito.Mockito.when(trace.lazyClone()).thenReturn(trace);
        return trace;
    }
}
