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

package org.evosuite.junit;

import junit.framework.TestCase;
import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.TestSuiteGenerator;
import org.evosuite.TestSuiteGeneratorHelper;
import org.evosuite.annotations.EvoSuiteTest;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationObserver;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.EvoRunner;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.StatisticsSender;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ExternalProcessUtilities;
import org.evosuite.utils.LoggingUtils;
import org.junit.Test;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.TestClass;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Modifier;
import java.text.NumberFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * CoverageAnalysis class.
 *
 * @author Gordon Fraser
 * @author José Campos
 */
public class CoverageAnalysis {

    /**
     * FIXME.
     *
     * <p>OUTPUT
     * METHOD
     * METHODNOEXCEPTION
     *
     * <p>relies on Observers. to have coverage of these criteria, JUnit test cases
     * must have to be converted to some format that EvoSuite can understand
     */

    private static final Logger logger = LoggerFactory.getLogger(CoverageAnalysis.class);

    private static int totalGoals = 0;
    private static int totalCoveredGoals = 0;
    private static Set<String> targetClasses = new LinkedHashSet<>();

    /**
     * Identify all JUnit tests starting with the given name prefix, instrument
     * and run tests.
     */
    public static void analyzeCoverage() {
        Sandbox.goingToExecuteSUTCode();
        TestGenerationContext.getInstance().goingToExecuteSUTCode();
        Sandbox.goingToExecuteUnsafeCodeOnSameThread();
        ExecutionTracer.setCheckCallerThread(false);
        try {
            String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();

            if (Properties.TARGET_CLASS.endsWith(".jar")
                    || Properties.TARGET_CLASS.contains(File.separator)) {
                targetClasses = DependencyAnalysis.analyzeTarget(Properties.TARGET_CLASS,
                        Arrays.asList(cp.split(File.pathSeparator)));
            } else {
                targetClasses.add(Properties.TARGET_CLASS);
                DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS,
                        Arrays.asList(cp.split(File.pathSeparator)));
            }

            DependencyAnalysis.logClasspathAnalysisSummary(Arrays.asList(cp.split(File.pathSeparator)));
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Finished analyzing classpath");
        } catch (Throwable e) {
            LoggingUtils.getEvoLogger().error("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Error while initializing target class: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
            logger.error("Problem for " + Properties.TARGET_CLASS + ". Full stack:", e);
            return;
        } finally {
            Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
            Sandbox.doneWithExecutingSUTCode();
            TestGenerationContext.getInstance().doneWithExecutingSUTCode();
        }
        // TestCluster.getInstance();

        List<Class<?>> testClasses = getTestClasses();
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Found " + testClasses.size()
                + " test class(es)");
        if (testClasses.isEmpty()) {
            return;
        }

        /*
         * sort them in a deterministic way, in case there are
         * static state dependencies
         */
        sortTestClasses(testClasses);

        Class<?>[] tests = testClasses.toArray(new Class<?>[testClasses.size()]);
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Executing test(s)");
        if (Properties.SELECTED_JUNIT == null) {
            boolean origUseAgent = EvoRunner.useAgent;
            boolean origUseClassLoader = EvoRunner.useClassLoader;
            try {
                EvoRunner.useAgent = false; //avoid double instrumentation
                EvoRunner.useClassLoader = false; //avoid double instrumentation

                List<JUnitResult> results = executeTests(tests);
                printReport(results);
            } finally {
                EvoRunner.useAgent = origUseAgent;
                EvoRunner.useClassLoader = origUseClassLoader;
            }
        } else {
            // instead of just running junit tests, carve them
            JUnitTestCarvedChromosomeFactory carvedFactory = new JUnitTestCarvedChromosomeFactory(null);
            TestSuiteChromosome testSuite = carvedFactory.getCarvedTestSuite();

            int goals = 0;
            for (Properties.Criterion pc : Properties.CRITERION) {
                logger.debug("Coverage analysis for criterion {}", pc);

                TestFitnessFactory<? extends TestFitnessFunction> ffactory = FitnessFunctions.getFitnessFactory(pc);
                goals += ffactory.getCoverageGoals().size();

                TestSuiteFitnessFunction ffunction = FitnessFunctions.getFitnessFunction(pc);
                ffunction.getFitness(testSuite);

                CoverageCriteriaAnalyzer.analyzeCoverage(testSuite, pc);
            }

            // Generate test suite
            TestSuiteGenerator.writeJUnitTestsAndCreateResult(testSuite);

            StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals);
            if (Properties.COVERAGE_MATRIX) {
                throw new IllegalArgumentException(
                        "Coverage matrix not yet available when measuring coverage of a carved test suite");
            }
        }
    }

    /**
     * Return the number of covered goals.
     *
     * @param testClass class to test
     * @param allGoals list of all goals
     * @return set of covered goals
     */
    public static Set<TestFitnessFunction> getCoveredGoals(Class<?> testClass, List<TestFitnessFunction> allGoals) {

        // A dummy Chromosome
        TestChromosome dummy = new TestChromosome();
        dummy.setChanged(false);

        // Execution result of a dummy Test Case
        ExecutionResult executionResult = new ExecutionResult(dummy.getTestCase());

        Set<TestFitnessFunction> coveredGoals = new HashSet<>();

        List<JUnitResult> results = executeTests(testClass);
        for (JUnitResult testResult : results) {
            executionResult.setTrace(testResult.getExecutionTrace());
            dummy.setLastExecutionResult(executionResult);

            for (TestFitnessFunction goal : allGoals) {
                if (goal.isCovered(dummy)) {
                    coveredGoals.add(goal);
                }
            }
        }

        return coveredGoals;
    }

    private static List<Class<?>> getTestClassesFromClasspath() {
        List<Class<?>> classes = new ArrayList<>();
        for (String prefix : Properties.JUNIT.split(":")) {

            Set<String> suts = ResourceList
                    .getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getAllClasses(ClassPathHandler.getInstance().getTargetProjectClasspath(), prefix, false);

            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Found " + suts.size()
                    + " classes with prefix '" + prefix + "'");
            if (!suts.isEmpty()) {
                for (String sut : suts) {
                    if (targetClasses.contains(sut)) {
                        continue;
                    }

                    try {
                        Class<?> clazz = Class.forName(
                                sut, true, TestGenerationContext.getInstance().getClassLoaderForSUT());

                        if (isTest(clazz)) {
                            classes.add(clazz);
                        }
                    } catch (ClassNotFoundException e2) {
                        logger.info("Could not find class " + sut);
                    } catch (Throwable t) {
                        logger.info("Error while initialising class " + sut);
                    }
                }

            }
        }
        return classes;
    }

    private static List<Class<?>> getTestClasses() {
        List<Class<?>> testClasses = new ArrayList<>();

        logger.debug("JUNIT: " + Properties.JUNIT);

        for (String prefix : Properties.JUNIT.split(":")) {

            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Analyzing entry: " + prefix);

            // If the target name is a path analyze it
            File path = new File(prefix);
            if (path.exists()) {
                if (Properties.JUNIT.endsWith(".jar")) {
                    testClasses.addAll(getTestClassesJar(path));
                } else {
                    testClasses.addAll(getTestClasses(path));
                }
            } else {

                try {
                    Class<?> clazz = Class.forName(prefix,
                            true,
                            TestGenerationContext.getInstance().getClassLoaderForSUT());
                    testClasses.add(clazz);
                } catch (ClassNotFoundException e) {
                    // Second, try if the target name is a package name
                    testClasses.addAll(getTestClassesFromClasspath());
                }
            }
        }
        return testClasses;
    }

    /**
     * Analyze all classes that can be found in a given directory.
     *
     * @param directory a {@link java.io.File} object.
     * @return a {@link java.util.List} object.
     * @throws ClassNotFoundException if any.
     */
    private static List<Class<?>> getTestClasses(File directory) {

        List<Class<?>> testClasses = new ArrayList<>();

        if (directory.getName().endsWith(".class")) {
            LoggingUtils.muteCurrentOutAndErrStream();

            try {
                File file = new File(directory.getPath());
                byte[] array = new byte[(int) file.length()];
                ByteArrayOutputStream out = new ByteArrayOutputStream(array.length);
                try (InputStream in = new FileInputStream(file)) {
                    int length = in.read(array);
                    while (length > 0) {
                        out.write(array, 0, length);
                        length = in.read(array);
                    }
                }
                ClassReader reader = new ClassReader(array);
                String className = reader.getClassName();

                // Use default classLoader
                Class<?> clazz = Class.forName(className.replace('/', '.'), true,
                        TestGenerationContext.getInstance().getClassLoaderForSUT());
                LoggingUtils.restorePreviousOutAndErrStream();

                //clazz = Class.forName(clazz.getName());
                if (isTest(clazz)) {
                    testClasses.add(clazz);
                }

            } catch (IllegalAccessError e) {
                LoggingUtils.restorePreviousOutAndErrStream();

                System.out.println("  Cannot access class "
                        + directory.getName().substring(0,
                        directory.getName().length() - 6)
                        + ": " + e);
            } catch (NoClassDefFoundError e) {
                LoggingUtils.restorePreviousOutAndErrStream();

                System.out.println("  Error while loading "
                        + directory.getName().substring(0,
                        directory.getName().length() - 6)
                        + ": Cannot find " + e.getMessage());
                //e.printStackTrace();
            } catch (ExceptionInInitializerError e) {
                LoggingUtils.restorePreviousOutAndErrStream();

                System.out.println("  Exception in initializer of "
                        + directory.getName().substring(0,
                        directory.getName().length() - 6));
            } catch (ClassNotFoundException e) {
                LoggingUtils.restorePreviousOutAndErrStream();

                System.out.println("  Class not found in classpath: "
                        + directory.getName().substring(0,
                        directory.getName().length() - 6)
                        + ": " + e);
            } catch (Throwable e) {
                LoggingUtils.restorePreviousOutAndErrStream();

                System.out.println("  Unexpected error: "
                        + directory.getName().substring(0,
                        directory.getName().length() - 6)
                        + ": " + e);
            }
        } else if (directory.isDirectory()) {
            for (File file : directory.listFiles()) {
                testClasses.addAll(getTestClasses(file));
            }
        }

        return testClasses;
    }

    /**
     * getClassesJar.
     *
     * @param file a {@link java.io.File} object.
     * @return a {@link java.util.List} object.
     */
    private static List<Class<?>> getTestClassesJar(File file) {

        List<Class<?>> testClasses = new ArrayList<>();

        ZipFile zf;
        try {
            zf = new ZipFile(file);
        } catch (final ZipException e) {
            throw new Error(e);
        } catch (final IOException e) {
            throw new Error(e);
        }

        final Enumeration<?> e = zf.entries();
        while (e.hasMoreElements()) {
            final ZipEntry ze = (ZipEntry) e.nextElement();
            final String fileName = ze.getName();
            if (!fileName.endsWith(".class")) {
                continue;
            }
            /*if (fileName.contains("$"))
                continue;*/

            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            //System.setOut(outStream);
            //System.setErr(outStream);

            try {
                Class<?> clazz = Class.forName(fileName.replace(".class", "").replace("/",
                                "."),
                        true,
                        TestGenerationContext.getInstance().getClassLoaderForSUT());

                if (isTest(clazz)) {
                    testClasses.add(clazz);
                }
            } catch (IllegalAccessError ex) {
                System.setOut(oldOut);
                System.setErr(oldErr);
                System.out.println("Cannot access class "
                        + file.getName().substring(0, file.getName().length() - 6));
            } catch (NoClassDefFoundError ex) {
                System.setOut(oldOut);
                System.setErr(oldErr);
                System.out.println("Cannot find dependent class " + ex);
            } catch (ExceptionInInitializerError ex) {
                System.setOut(oldOut);
                System.setErr(oldErr);
                System.out.println("Exception in initializer of "
                        + file.getName().substring(0, file.getName().length() - 6));
            } catch (ClassNotFoundException ex) {
                System.setOut(oldOut);
                System.setErr(oldErr);
                System.out.println("Cannot find class "
                        + file.getName().substring(0, file.getName().length() - 6) + ": "
                        + ex);
            } catch (Throwable t) {
                System.setOut(oldOut);
                System.setErr(oldErr);

                System.out.println("  Unexpected error: "
                        + file.getName().substring(0, file.getName().length() - 6) + ": "
                        + t);
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
        }
        try {
            zf.close();
        } catch (final IOException e1) {
            throw new Error(e1);
        }

        return testClasses;
    }

    private static void analyzeCoverageCriterion(List<JUnitResult> results, Properties.Criterion criterion) {

        logger.info("analysing coverage of " + criterion);

        // Factory
        TestFitnessFactory<? extends TestFitnessFunction> factory = FitnessFunctions.getFitnessFactory(criterion);

        // Goals
        List<?> goals = null;

        if (criterion == Criterion.MUTATION
                || criterion == Criterion.STRONGMUTATION) {
            goals = MutationPool.getInstance(
                    TestGenerationContext.getInstance().getClassLoaderForSUT()).getMutants();
        } else {
            goals = factory.getCoverageGoals();
        }
        totalGoals += goals.size();

        // A dummy Chromosome
        TestChromosome dummy = new TestChromosome();
        dummy.setChanged(false);

        // Execution result of a dummy Test Case
        ExecutionResult executionResult = new ExecutionResult(dummy.getTestCase());

        // coverage matrix (each row represents the coverage of each test case
        // and each column represents the coverage of each component (e.g., line)
        // this coverage matrix is useful for Rho fitness
        // +1 because we also want to include the test result
        boolean[][] coverageMatrix = new boolean[results.size()][goals.size() + 1];
        BitSet covered = new BitSet(goals.size());

        for (int indexTest = 0; indexTest < results.size(); indexTest++) {
            JUnitResult junitResult = results.get(indexTest);

            ExecutionTrace trace = junitResult.getExecutionTrace();
            executionResult.setTrace(trace);
            dummy.getTestCase().clearCoveredGoals();
            dummy.setLastExecutionResult(executionResult);

            if (criterion == Criterion.MUTATION
                    || criterion == Criterion.STRONGMUTATION) {
                for (Integer mutationId : trace.getTouchedMutants()) {
                    Mutation mutation = MutationPool.getInstance(
                            TestGenerationContext.getInstance().getClassLoaderForSUT()).getMutant(mutationId);

                    if (goals.contains(mutation)) {
                        MutationObserver.activateMutation(mutationId);
                        List<JUnitResult> mutationResults = executeTests(junitResult.getJUnitClass());
                        MutationObserver.deactivateMutation();

                        for (JUnitResult mutationResult : mutationResults) {
                            if (mutationResult.getFailureCount() != junitResult.getFailureCount()) {
                                logger.info("Mutation killed: " + mutationId);
                                covered.set(mutation.getId());
                                coverageMatrix[indexTest][mutationId] = true;
                                break;
                            }
                        }
                    }
                }
            } else {

                if (criterion == Criterion.EXCEPTION) {
                    // TODO collect exception goals from execution results
                }

                for (int indexComponent = 0; indexComponent < goals.size(); indexComponent++) {
                    TestFitnessFunction goal = (TestFitnessFunction) goals.get(indexComponent);

                    if (goal.isCovered(dummy)) {
                        covered.set(indexComponent);
                        coverageMatrix[indexTest][indexComponent] = true;
                    } else {
                        coverageMatrix[indexTest][indexComponent] = false;
                    }
                }
            }

            coverageMatrix[indexTest][goals.size()] = junitResult.wasSuccessful();
        }
        totalCoveredGoals += covered.cardinality();

        if (Properties.COVERAGE_MATRIX) {
            CoverageReportGenerator.writeCoverage(coverageMatrix, criterion);
        }

        StringBuilder str = new StringBuilder();
        for (int indexComponent = 0; indexComponent < goals.size(); indexComponent++) {
            str.append(covered.get(indexComponent) ? "1" : "0");
        }
        logger.info("* CoverageBitString " + str);

        RuntimeVariable bitStringVariable = CoverageCriteriaAnalyzer.getBitStringVariable(criterion);
        String criterionName = TestSuiteGeneratorHelper.getCriterionDisplayName(criterion);
        if (goals.isEmpty()) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "  - " + criterionName + ": 100% (0/0 goals)");
            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    CoverageCriteriaAnalyzer.getCoverageVariable(criterion), 1.0);
            if (bitStringVariable != null) {
                ClientServices.getInstance().getClientNode().trackOutputVariable(bitStringVariable, "1");
            }
        } else {
            double coverage = ((double) covered.cardinality()) / ((double) goals.size());
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "  - "
                    + criterionName + ": " + NumberFormat.getPercentInstance().format(coverage)
                    + " (" + covered.cardinality() + "/" + goals.size() + " goals)");

            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    CoverageCriteriaAnalyzer.getCoverageVariable(criterion), coverage);
            if (bitStringVariable != null) {
                ClientServices.getInstance().getClientNode().trackOutputVariable(bitStringVariable, str.toString());
            }
        }
    }

    private static void printReport(List<JUnitResult> results) {

        Iterator<String> it = targetClasses.iterator();
        Criterion[] criterion = Properties.CRITERION;

        while (it.hasNext()) {
            String targetClass = it.next();

            // restart variables
            totalGoals = 0;
            totalCoveredGoals = 0;

            Properties.TARGET_CLASS = targetClass;
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Target class "
                    + Properties.TARGET_CLASS);
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Resulting code coverage:");
            ClientServices.getInstance().getClientNode().updateProperty("TARGET_CLASS", Properties.TARGET_CLASS);

            for (Criterion c : criterion) {
                Properties.CRITERION = new Criterion[]{c};

                analyzeCoverageCriterion(results, c);
            }

            // restore
            Properties.CRITERION = criterion;

            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Total number of covered goals: " + totalCoveredGoals + " / " + "" + totalGoals);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, totalGoals);
            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    RuntimeVariable.Covered_Goals, totalCoveredGoals);

            double coverage = totalGoals == 0 ? 1.0 : ((double) totalCoveredGoals) / ((double) totalGoals);
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Total coverage: "
                    + NumberFormat.getPercentInstance().format(coverage));
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Coverage, coverage);

            // need to give some time for transmission before client is killed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // last element will be flush by master process
            if (it.hasNext()) {
                ClientServices.getInstance().getClientNode().flushStatisticsForClassChange();
            }
        }
    }

    private static List<JUnitResult> executeTests(Class<?>... testClasses) {

        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        List<JUnitResult> results = new ArrayList<>();
        for (Class<?> testClass : testClasses) {
            LoggingUtils.getEvoLogger().info("  Executing " + testClass.getSimpleName());
            // Set the context classloader in case the SUT requests it
            Thread.currentThread().setContextClassLoader(testClass.getClassLoader());
            JUnitRunner junitRunner = new JUnitRunner(testClass);
            junitRunner.run();
            results.addAll(junitRunner.getTestResults());
        }

        ExecutionTracer.disable();

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Executed " + results.size() + " unit " + "test(s)");
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Tests_Executed,
                results.size());

        return results;
    }

    /**
     * Determine if a class contains JUnit tests.
     *
     * @param cls class to check
     * @return true if class contains tests
     */
    public static boolean isTest(Class<?> cls) {
        if (Modifier.isAbstract(cls.getModifiers())) {
            return false;
        }

        TestClass tc;

        try {
            tc = new TestClass(cls);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (RuntimeException e) {
            //this can happen if class has Annotations that are not available on classpath
            throw new RuntimeException("Failed to analyze class " + cls.getName() + " due to: " + e);
        }

        // JUnit 4
        try {
            List<FrameworkMethod> methods = new ArrayList<>();
            methods.addAll(tc.getAnnotatedMethods(Test.class));
            methods.addAll(tc.getAnnotatedMethods(EvoSuiteTest.class));
            for (FrameworkMethod method : methods) {
                List<Throwable> errors = new ArrayList<>();
                method.validatePublicVoidNoArg(false, errors);
                if (errors.isEmpty()) {
                    return true;
                }
            }
        } catch (IllegalArgumentException e) {
            return false;
        }

        // JUnit 3
        Class<?> superClass = cls;
        while ((superClass = superClass.getSuperclass()) != null) {
            if (superClass.getCanonicalName().equals(Object.class.getCanonicalName())) {
                break;
            } else if (superClass.getCanonicalName().equals(TestCase.class.getCanonicalName())) {
                return true;
            }
        }

        // TODO add support for other frameworks, e.g., TestNG ?

        return false;
    }

    /**
     * re-order test classes.
     *
     * @param tests list of test classes
     */
    private static void sortTestClasses(List<Class<?>> tests) {
        tests.sort((t0, t1) -> Integer.compare(t1.getName().length(), t0.getName().length()));
    }

    /**
     * run.
     */
    public void run() {

        LoggingUtils.getEvoLogger().info("* Connecting to master process on port "
                + Properties.PROCESS_COMMUNICATION_PORT);

        ExternalProcessUtilities util = new ExternalProcessUtilities();
        if (!util.connectToMainProcess()) {
            throw new RuntimeException("Could not connect to master process on port "
                    + Properties.PROCESS_COMMUNICATION_PORT);
        }

        analyzeCoverage();
        /*
         * for now, we ignore the instruction (originally was meant to support several client in parallel and
         * restarts, but that will be done in RMI)
         */

        util.informSearchIsFinished(null);
    }

    // just for testing
    protected static void reset() {
        totalGoals = 0;
        totalCoveredGoals = 0;
        targetClasses.clear();
    }
}
