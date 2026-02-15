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

package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.TestSuiteGeneratorHelper;
import org.evosuite.coverage.ambiguity.AmbiguityCoverageSuiteFitness;
import org.evosuite.coverage.rho.RhoCoverageSuiteFitness;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.*;

/**
 * Analyzer for coverage criteria.
 *
 * @author Gordon Fraser
 */
public class CoverageCriteriaAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(CoverageCriteriaAnalyzer.class);

    private static final Map<String, StringBuilder> coverageBitString = new TreeMap<>();

    private static final Set<Properties.Criterion> MUTATION_CRITERIA = EnumSet.of(
            Properties.Criterion.MUTATION,
            Properties.Criterion.WEAKMUTATION,
            Properties.Criterion.STRONGMUTATION,
            Properties.Criterion.ONLYMUTATION
    );

    private static final Map<Criterion, RuntimeVariable> coverageVariables = new EnumMap<>(Criterion.class);
    private static final Map<Criterion, RuntimeVariable> bitStringVariables = new EnumMap<>(Criterion.class);

    static {
        // Initialize coverage variables
        coverageVariables.put(Criterion.ALLDEFS, RuntimeVariable.AllDefCoverage);
        coverageVariables.put(Criterion.BRANCH, RuntimeVariable.BranchCoverage);
        coverageVariables.put(Criterion.CBRANCH, RuntimeVariable.CBranchCoverage);
        coverageVariables.put(Criterion.EXCEPTION, RuntimeVariable.ExceptionCoverage);
        coverageVariables.put(Criterion.DEFUSE, RuntimeVariable.DefUseCoverage);
        coverageVariables.put(Criterion.STATEMENT, RuntimeVariable.StatementCoverage);
        coverageVariables.put(Criterion.RHO, RuntimeVariable.RhoScore);
        coverageVariables.put(Criterion.AMBIGUITY, RuntimeVariable.AmbiguityScore);
        coverageVariables.put(Criterion.STRONGMUTATION, RuntimeVariable.MutationScore);
        coverageVariables.put(Criterion.MUTATION, RuntimeVariable.MutationScore);
        coverageVariables.put(Criterion.ONLYMUTATION, RuntimeVariable.OnlyMutationScore);
        coverageVariables.put(Criterion.WEAKMUTATION, RuntimeVariable.WeakMutationScore);
        coverageVariables.put(Criterion.ONLYBRANCH, RuntimeVariable.OnlyBranchCoverage);
        coverageVariables.put(Criterion.METHODTRACE, RuntimeVariable.MethodTraceCoverage);
        coverageVariables.put(Criterion.METHOD, RuntimeVariable.MethodCoverage);
        coverageVariables.put(Criterion.METHODNOEXCEPTION, RuntimeVariable.MethodNoExceptionCoverage);
        coverageVariables.put(Criterion.ONLYLINE, RuntimeVariable.LineCoverage);
        coverageVariables.put(Criterion.LINE, RuntimeVariable.LineCoverage);
        coverageVariables.put(Criterion.OUTPUT, RuntimeVariable.OutputCoverage);
        coverageVariables.put(Criterion.INPUT, RuntimeVariable.InputCoverage);
        coverageVariables.put(Criterion.IBRANCH, RuntimeVariable.IBranchCoverage);
        coverageVariables.put(Criterion.TRYCATCH, RuntimeVariable.TryCatchCoverage);

        // Initialize bit string variables
        bitStringVariables.put(Criterion.EXCEPTION, RuntimeVariable.ExceptionCoverageBitString);
        bitStringVariables.put(Criterion.DEFUSE, RuntimeVariable.DefUseCoverageBitString);
        bitStringVariables.put(Criterion.ALLDEFS, RuntimeVariable.AllDefCoverageBitString);
        bitStringVariables.put(Criterion.BRANCH, RuntimeVariable.BranchCoverageBitString);
        bitStringVariables.put(Criterion.CBRANCH, RuntimeVariable.CBranchCoverageBitString);
        bitStringVariables.put(Criterion.IBRANCH, RuntimeVariable.IBranchCoverageBitString);
        bitStringVariables.put(Criterion.ONLYBRANCH, RuntimeVariable.OnlyBranchCoverageBitString);
        bitStringVariables.put(Criterion.MUTATION, RuntimeVariable.MutationCoverageBitString);
        bitStringVariables.put(Criterion.STRONGMUTATION, RuntimeVariable.MutationCoverageBitString);
        bitStringVariables.put(Criterion.WEAKMUTATION, RuntimeVariable.WeakMutationCoverageBitString);
        bitStringVariables.put(Criterion.ONLYMUTATION, RuntimeVariable.OnlyMutationCoverageBitString);
        bitStringVariables.put(Criterion.METHODTRACE, RuntimeVariable.MethodTraceCoverageBitString);
        bitStringVariables.put(Criterion.METHOD, RuntimeVariable.MethodCoverageBitString);
        bitStringVariables.put(Criterion.METHODNOEXCEPTION, RuntimeVariable.MethodNoExceptionCoverageBitString);
        bitStringVariables.put(Criterion.OUTPUT, RuntimeVariable.OutputCoverageBitString);
        bitStringVariables.put(Criterion.INPUT, RuntimeVariable.InputCoverageBitString);
        bitStringVariables.put(Criterion.STATEMENT, RuntimeVariable.StatementCoverageBitString);
        bitStringVariables.put(Criterion.LINE, RuntimeVariable.LineCoverageBitString);
        bitStringVariables.put(Criterion.ONLYLINE, RuntimeVariable.LineCoverageBitString);
        // TRYCATCH maps to null in original code, so we skip it here
    }

    private static boolean isMutationCriterion(Properties.Criterion criterion) {
        return MUTATION_CRITERIA.contains(criterion);
    }

    private static void reinstrument(TestSuiteChromosome testSuite, Properties.Criterion criterion) {

        if (ArrayUtil.contains(Properties.SECONDARY_OBJECTIVE, Properties.SecondaryObjective.IBRANCH)) {
            ExecutionTracer.enableContext();
        }
        if (!ExecutionTracer.isTraceCallsEnabled()) {
            ExecutionTracer.enableTraceCalls();
        }

        testSuite.setChanged(true);
        for (TestChromosome test : testSuite.getTestChromosomes()) {
            test.setChanged(true);
            test.clearCachedResults(); // clears last execution result and last mutation result
        }

        Properties.Criterion[] oldCriterion = Arrays.copyOf(Properties.CRITERION, Properties.CRITERION.length);
        Properties.CRITERION = new Properties.Criterion[]{criterion};

        logger.info("Re-instrumenting for criterion: {}", criterion);
        TestGenerationContext.getInstance().resetContext();

        // Need to load class explicitly in case there are no test cases.
        // If there are tests, then this is redundant
        Properties.getInitializedTargetClass();

        // TODO: Now all existing test cases have reflection objects pointing to the wrong classloader
        logger.info("Changing classloader of test suite for criterion: {}", criterion);

        for (TestChromosome test : testSuite.getTestChromosomes()) {
            DefaultTestCase dtest = (DefaultTestCase) test.getTestCase();
            dtest.changeClassLoader(TestGenerationContext.getInstance().getClassLoaderForSUT());
        }
        Properties.CRITERION = oldCriterion;
    }

    /**
     * Analyzes the given criteria for the specified test suite.
     *
     * @param testSuite the test suite to analyze.
     * @param criteria a comma-separated list of criteria to analyze.
     */
    public static void analyzeCriteria(TestSuiteChromosome testSuite, String criteria) {

        // If coverage of target criteria is not already measured
        if (!Properties.COVERAGE) {
            for (Criterion c : Properties.CRITERION) {
                // Analyse coverage for enabled criteria
                // LoggingUtils.getEvoLogger().info("  - " + c.name());
                logger.debug("Measuring coverage of target criterion {}", c);
                analyzeCoverage(testSuite, c.name());
            }
        }

        boolean reinstrumented = false;
        for (String extraCriterion : criteria.toUpperCase().split(",")) {
            if (extraCriterion.equals("CBRANCH")) {
                Properties.INSTRUMENT_METHOD_CALLS = true;
            }
            // Analyse coverage for extra criteria
            if (!ArrayUtil.contains(Properties.CRITERION, extraCriterion)) {
                logger.debug("Measuring additional coverage of target criterion {}", extraCriterion);
                reinstrumented = true;
                analyzeCoverage(testSuite, extraCriterion);
            }
        }

        // If reinstrumentation happened, we might need to restore the original instrumentation
        // otherwise things like the MutationPool may not be up to date
        if (reinstrumented) {
            TestGenerationContext.getInstance().resetContext();
            Properties.getInitializedTargetClass();
        }
    }

    private static void analyzeCoverage(TestSuiteChromosome testSuite, String criterion) {
        try {
            Properties.Criterion crit = Properties.Criterion.valueOf(criterion.toUpperCase());
            analyzeCoverage(testSuite, crit);
        } catch (IllegalArgumentException e) {
            LoggingUtils.getEvoLogger().info("* Unknown coverage criterion: {}", criterion);
        }
    }

    /**
     * Returns the runtime variable associated with the coverage of a given criterion.
     *
     * @param criterion the criterion.
     * @return the runtime variable.
     */
    public static RuntimeVariable getCoverageVariable(Properties.Criterion criterion) {
        if (coverageVariables.containsKey(criterion)) {
            return coverageVariables.get(criterion);
        }
        throw new RuntimeException("Criterion not supported: " + criterion);
    }

    /**
     * Analyzes coverage for all criteria enabled in Properties.
     *
     * @param testSuite the test suite to analyze.
     */
    public static void analyzeCoverage(TestSuiteChromosome testSuite) {

        LoggingUtils.getEvoLogger().info("* Resulting code coverage:");

        Properties.Criterion[] criteria = Properties.CRITERION;

        /*
            As we analyze exactly the same criteria used during the search, we should do not
            need to re-instrument and re-run the tests
         */
        boolean recalculate = false;

        for (Properties.Criterion pc : criteria) {
            logger.debug("Coverage analysis for criterion {}", pc);

            analyzeCoverage(testSuite, pc, recalculate);
        }
    }

    public static void analyzeCoverage(TestSuiteChromosome testSuite, Properties.Criterion criterion) {
        analyzeCoverage(testSuite, criterion, true);
    }

    private static void analyzeCoverage(TestSuiteChromosome testSuite, Properties.Criterion criterion,
                                        boolean recalculate) {

        TestSuiteChromosome testSuiteCopy = testSuite.clone();

        TestFitnessFactory<? extends TestFitnessFunction> factory = FitnessFunctions.getFitnessFactory(criterion);

        if (recalculate) {
            reinstrument(testSuiteCopy, criterion);

            for (TestChromosome test : testSuiteCopy.getTestChromosomes()) {
                test.getTestCase().clearCoveredGoals();
                test.clearCachedResults();

                // independently of mutation being a main or secondary criteria,
                // test cases have to be 'changed'. with this, isCovered() will
                // re-execute test cases and it will be able to find the covered goals
                if (isMutationCriterion(criterion)) {
                    test.setChanged(true);
                }
            }
        }

        List<? extends TestFitnessFunction> goals = factory.getCoverageGoals();
        Collections.sort(goals);

        StringBuilder buffer = new StringBuilder(goals.size());
        int covered = 0;

        for (TestFitnessFunction goal : goals) {
            if (goal.isCoveredBy(testSuiteCopy)) {
                logger.debug("Goal {} is covered", goal);
                covered++;
                buffer.append("1");
            } else {
                logger.debug("Goal {} is not covered", goal);
                buffer.append("0");
                if (Properties.PRINT_MISSED_GOALS) {
                    LoggingUtils.getEvoLogger().info(" - Missed goal {}", goal);
                }
            }
        }

        coverageBitString.put(criterion.name(), buffer);
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.CoverageBitString,
                coverageBitString.isEmpty() ? "0" : coverageBitString.values().toString()
                        .replace("[", "").replace("]", "").replace(", ", ""));

        RuntimeVariable bitStringVariable = getBitStringVariable(criterion);
        if (bitStringVariable != null) {
            String goalBitString = buffer.toString();
            ClientServices.getInstance().getClientNode().trackOutputVariable(bitStringVariable, goalBitString);
        }

        String criterionName = TestSuiteGeneratorHelper.getCriterionDisplayName(criterion);
        if (goals.isEmpty()) {
            if (criterion == Properties.Criterion.MUTATION
                    || criterion == Properties.Criterion.STRONGMUTATION) {
                ClientServices.getInstance().getClientNode().trackOutputVariable(
                        RuntimeVariable.MutationScore, 1.0);
            }
            LoggingUtils.getEvoLogger().info("  - {}: 100% (0/0 goals)", criterionName);
            ClientServices.getInstance().getClientNode().trackOutputVariable(getCoverageVariable(criterion), 1.0);
        } else {

            double coverage = (double) covered / (double) goals.size();
            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    getCoverageVariable(criterion), coverage);

            if (criterion == Properties.Criterion.MUTATION
                    || criterion == Properties.Criterion.STRONGMUTATION) {
                ClientServices.getInstance().getClientNode().trackOutputVariable(
                        RuntimeVariable.MutationScore, coverage);
            }

            LoggingUtils.getEvoLogger().info("  - {}: {} ({}/{} goals)", criterionName,
                    NumberFormat.getPercentInstance().format(coverage), covered, goals.size());
        }

        // FIXME it works, but needs a better way of handling this
        if (criterion == Properties.Criterion.RHO) {
            RhoCoverageSuiteFitness rho = new RhoCoverageSuiteFitness();
            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    RuntimeVariable.RhoScore, Math.abs(0.5 - rho.getFitness(testSuite)));
        } else if (criterion == Properties.Criterion.AMBIGUITY) {
            AmbiguityCoverageSuiteFitness ag = new AmbiguityCoverageSuiteFitness();
            ClientServices.getInstance().getClientNode().trackOutputVariable(
                    RuntimeVariable.AmbiguityScore, ag.getFitness(testSuite));
        }
    }

    /**
     * Returns the runtime variable associated with the coverage bit string of a given criterion.
     *
     * @param criterion the criterion.
     * @return the runtime variable, or null if not supported.
     */
    public static RuntimeVariable getBitStringVariable(Properties.Criterion criterion) {
        if (bitStringVariables.containsKey(criterion)) {
            return bitStringVariables.get(criterion);
        }
        logger.debug("Criterion not supported: {}", criterion);
        return null;
    }
}
