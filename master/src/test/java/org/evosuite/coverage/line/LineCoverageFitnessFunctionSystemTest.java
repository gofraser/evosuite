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
package org.evosuite.coverage.line;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.evosuite.EvoSuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.Properties.StoppingCondition;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.examples.with.different.packagename.ClassWithAnonymousClass;
import com.examples.with.different.packagename.instrumentation.testability.FlagExample3;
import com.examples.with.different.packagename.IntExample;
import com.examples.with.different.packagename.SingleMethod;
import com.examples.with.different.packagename.coverage.IntExampleWithNoElse;
import com.examples.with.different.packagename.staticfield.StaticFoo;

/**
 * @author Jose Miguel Rojas
 */
public class LineCoverageFitnessFunctionSystemTest extends SystemTestBase {

    private Properties.Criterion[] oldCriteria = Arrays.copyOf(Properties.CRITERION, Properties.CRITERION.length);
    private Properties.StoppingCondition oldStoppingCondition = Properties.STOPPING_CONDITION;
    private double oldPrimitivePool = Properties.PRIMITIVE_POOL;
    private boolean oldResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final int oldChromosomeLength = Properties.CHROMOSOME_LENGTH;

    
    public String name;

    private static class WatchdogExtension implements org.junit.jupiter.api.extension.TestWatcher {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            String methodName = context.getRequiredTestMethod().getName();
            System.out.println("LINECOVERAGE_TEST: RUN PASS " + methodName
                    + " timeMs=" + System.currentTimeMillis());
        }

        @Override
        public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context, Throwable cause) {
            String methodName = context.getRequiredTestMethod().getName();
            System.err.println("LINECOVERAGE_TEST: RUN FAIL " + methodName
                    + " timeMs=" + System.currentTimeMillis());
            System.err.println("LINECOVERAGE_TEST: RUN FAIL cause=" + cause);
        }
    }

    @org.junit.jupiter.api.extension.RegisterExtension
    WatchdogExtension watcher = new WatchdogExtension();

    @BeforeEach
    public void beforeTest(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        if (testMethod.isPresent()) {
            this.name = testMethod.get().getName();
        }
        oldCriteria = Arrays.copyOf(Properties.CRITERION, Properties.CRITERION.length);
        oldStoppingCondition = Properties.STOPPING_CONDITION;
        oldPrimitivePool = Properties.PRIMITIVE_POOL;
        Properties.CRITERION = new Properties.Criterion[]{Criterion.LINE};
        oldResetStaticFields = Properties.RESET_STATIC_FIELDS;
        //Properties.MINIMIZE = false;
        GlobalTimeStoppingCondition.forceReset();
    }

    @AfterEach
    public void restoreProperties() {
        Properties.CRITERION = oldCriteria;
        Properties.STOPPING_CONDITION = oldStoppingCondition;
        Properties.PRIMITIVE_POOL = oldPrimitivePool;
        Properties.RESET_STATIC_FIELDS = oldResetStaticFields;
        Properties.CHROMOSOME_LENGTH = oldChromosomeLength;
    }

    @Test
    public void testOnlyLineCoverageFitnessSimpleExampleWithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        String targetClass = SingleMethod.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.CRITERION = new Properties.Criterion[]{Criterion.ONLYLINE};

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(2, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessSimpleExampleWithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        String targetClass = SingleMethod.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(2, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessSimpleExampleWithoutArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;

        String targetClass = SingleMethod.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(2, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessFlagExample3WithoutArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        String targetClass = FlagExample3.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.STOPPING_CONDITION = StoppingCondition.MAXTIME;
        Properties.SEARCH_BUDGET = 60;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(5, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessFlagExample3WithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;

        String targetClass = FlagExample3.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.STOPPING_CONDITION = StoppingCondition.MAXTIME;
        Properties.SEARCH_BUDGET = 60;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(5, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessBranchGuidanceWithoutArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        String targetClass = IntExample.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        // To see whether there is any guidance we turn off
        // seeding, but need to increase the budget
        Properties.PRIMITIVE_POOL = 0.0;
        Properties.SEARCH_BUDGET = 150_000;
        Properties.GLOBAL_TIMEOUT = 300;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(6, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessBranchGuidanceWithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;

        String targetClass = IntExample.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        // To see whether there is any guidance we turn off
        // seeding, but need to increase the budget
        Properties.PRIMITIVE_POOL = 0.0;
        Properties.SEARCH_BUDGET = 150_000;
        Properties.GLOBAL_TIMEOUT = 300;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(6, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testOnlyLineCoverageFitnessBranchGuidanceWithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;
        Properties.CRITERION = new Properties.Criterion[]{Criterion.ONLYLINE};

        String targetClass = IntExample.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        // To see whether there is any guidance we turn off
        // seeding, but need to increase the budget
        Properties.PRIMITIVE_POOL = 0.0;
        Properties.SEARCH_BUDGET = 150_000;
        Properties.GLOBAL_TIMEOUT = 300;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(6, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
        // Assert.assertTrue("Did not expect optimal coverage: ", best.getCoverage() < 1);
    }

    @Test
    public void testLineCoverageFitnessBranchGuidance2WithoutArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        String targetClass = IntExampleWithNoElse.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        // To see whether there is any guidance we turn off
        // seeding, but need to increase the budget
        Properties.PRIMITIVE_POOL = 0.0;
        Properties.SEARCH_BUDGET = 150_000;
        Properties.GLOBAL_TIMEOUT = 300;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(6, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testLineCoverageFitnessBranchGuidance2WithArchive() {
        EvoSuite evosuite = new EvoSuite();
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;

        String targetClass = IntExampleWithNoElse.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        // To see whether there is any guidance we turn off
        // seeding, but need to increase the budget
        Properties.PRIMITIVE_POOL = 0.0;
        Properties.SEARCH_BUDGET = 150_000;
        Properties.GLOBAL_TIMEOUT = 300;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        Properties.TEST_ARCHIVE = archive;

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        logCase(targetClass, best, goals, Properties.TEST_ARCHIVE);
        Assertions.assertEquals(6, goals);
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testListOfGoalsWith_RESET_STATIC_FIELDS_enable() {
        String targetClass = StaticFoo.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        Properties.RESET_STATIC_FIELDS = true;

        EvoSuite evosuite = new EvoSuite();

        String[] command = new String[]{
                "-printStats",
                "-class", targetClass
        };

        runWithWatchdog(evosuite, command);

        LineCoverageFactory rc = new LineCoverageFactory();

        List<LineCoverageTestFitness> goals = rc.getCoverageGoals();
        logGoalsList(targetClass, goals);

        assertEquals(9, goals.size());
    }

    @Test
    public void testListOfGoalsWith_RESET_STATIC_FIELDS_disable() {
        String targetClass = StaticFoo.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        Properties.RESET_STATIC_FIELDS = false;

        EvoSuite evosuite = new EvoSuite();

        String[] command = new String[]{
                "-printStats",
                "-class", targetClass
        };

        runWithWatchdog(evosuite, command);

        LineCoverageFactory rc = new LineCoverageFactory();

        List<LineCoverageTestFitness> goals = rc.getCoverageGoals();
        logGoalsList(targetClass, goals);

        assertEquals(9, goals.size());
    }

    @Test
    public void testListOfGoals_AnonymousClass() {
        String targetClass = ClassWithAnonymousClass.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.CRITERION = new Properties.Criterion[]{Criterion.ONLYLINE};

        String[] command = new String[]{
                "-class", targetClass,
                "-printStats"
        };

        EvoSuite evosuite = new EvoSuite();
        runWithWatchdog(evosuite, command);

        LineCoverageFactory line_factory = new LineCoverageFactory();
        List<LineCoverageTestFitness> lines = line_factory.getCoverageGoals();
        logGoalsList(targetClass, lines);

        // lines: 22, 24, 27, 30, 31, 32, 33, 35, 38
        Assertions.assertEquals(11, lines.size());
    }

    @Test
    public void testCoveredGoals_AnonymousClass() {
        String targetClass = ClassWithAnonymousClass.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.CRITERION = new Properties.Criterion[]{Criterion.ONLYLINE};

        String[] command = new String[]{
                "-class", targetClass,
                "-generateSuite"
        };

        EvoSuite evosuite = new EvoSuite();

        Object result = runWithWatchdog(evosuite, command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        // lines: 22, 24, 27, 30, 31, 32, 33, 35, 38
        logCase(targetClass, best, TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(),
                Properties.TEST_ARCHIVE);
        Assertions.assertEquals(11, TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size());
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    private void logCase(String targetClass, TestSuiteChromosome best, int goals, boolean archive) {
        String testName = name;
        System.out.println("LINECOVERAGE_TEST: START " + testName);
        System.out.println("LINECOVERAGE_TEST: target=" + targetClass);
        System.out.println("LINECOVERAGE_TEST: archive=" + archive);
        System.out.println("LINECOVERAGE_TEST: criterion=" + Arrays.toString(Properties.CRITERION));
        System.out.println("LINECOVERAGE_TEST: stop=" + Properties.STOPPING_CONDITION
                + " budget=" + Properties.SEARCH_BUDGET
                + " timeout=" + Properties.GLOBAL_TIMEOUT);
        System.out.println("LINECOVERAGE_TEST: primitivePool=" + Properties.PRIMITIVE_POOL
                + " resetStaticFields=" + Properties.RESET_STATIC_FIELDS
                + " chromosomeLength=" + Properties.CHROMOSOME_LENGTH);
        System.out.println("LINECOVERAGE_TEST: goals=" + goals + " coverage=" + best.getCoverage());
        System.out.println("LINECOVERAGE_TEST: CoveredGoals START");
        System.out.println(best.getCoveredGoals());
        System.out.println("LINECOVERAGE_TEST: CoveredGoals END");
        System.out.println("LINECOVERAGE_TEST: EvolvedTestSuite START");
        System.out.println(best);
        System.out.println("LINECOVERAGE_TEST: EvolvedTestSuite END");
        System.out.println("LINECOVERAGE_TEST: END " + testName);
    }

    private void logGoalsList(String targetClass, List<?> goals) {
        String testName = name;
        System.out.println("LINECOVERAGE_TEST: GOALS START " + testName);
        System.out.println("LINECOVERAGE_TEST: target=" + targetClass);
        System.out.println("LINECOVERAGE_TEST: goalsCount=" + goals.size());
        for (Object goal : goals) {
            System.out.println(goal);
        }
        System.out.println("LINECOVERAGE_TEST: GOALS END " + testName);
    }

    private Object runWithWatchdog(EvoSuite evosuite, String[] command) {
        final Thread testThread = Thread.currentThread();
        final long timeoutMs = Math.max(30_000L, Properties.GLOBAL_TIMEOUT * 1000L);
        final String testName = name;
        final String commandLine = String.join(" ", command);
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("LINECOVERAGE_TEST: WATCHDOG TIMEOUT " + testName
                    + " afterMs=" + timeoutMs);
            System.err.println("LINECOVERAGE_TEST: WATCHDOG command=" + commandLine);
            for (StackTraceElement ste : testThread.getStackTrace()) {
                System.err.println("LINECOVERAGE_TEST: WATCHDOG " + ste.toString());
            }
        }, "LineCoverageWatchdog-" + testName);
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            return evosuite.parseCommandLine(command);
        } finally {
            watchdog.interrupt();
        }
    }
}
