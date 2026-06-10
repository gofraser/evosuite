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
package org.evosuite.statistics;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.exception.ExceptionCoverageSuiteFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.rmi.ClientServices;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Class responsible to send "individuals" from Client to Master process.
 * All sending of individuals should go through this class, and not
 * calling ClientServices directly.
 *
 * <p>TODO: still to clarify what type of extra information we want to send with each individual,
 * eg the state in which it was computed (Search vs Minimization).</p>
 *
 * @author arcuri
 */
public class StatisticsSender {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsSender.class);

    /**
     * Send the given individual to the Client, plus any other needed info.
     *
     * @param individual the individual to send
     */
    public static <T extends Chromosome<T>> void sendIndividualToMaster(T individual) throws IllegalArgumentException {
        if (individual == null) {
            throw new IllegalArgumentException("No defined individual to send");
        }
        if (!Properties.NEW_STATISTICS) {
            return;
        }

        try {
            ClientServices.<T>getInstance().getClientNode().updateStatistics(individual);
        } catch (RuntimeException t) {
            // Statistics export must never abort test generation. In some environments
            // the master cannot deserialize certain generic members from the client
            // classpath (e.g., missing SUT constructor metadata), which should degrade
            // to a warning instead of failing the whole run. We deliberately do NOT
            // catch Error/Throwable here — OOM, ThreadDeath, and similar must
            // propagate so shutdown logic runs.
            logger.warn("Could not export individual statistics to master; continuing generation: {}",
                    t.getMessage(), t);
        }
    }


    /**
     * First execute (if needed) the test cases to be sure to have latest correct data,
     * and then send it to Master.
     *
     * @param testSuite the test suite to execute and send
     */
    public static void executedAndThenSendIndividualToMaster(TestSuiteChromosome testSuite)
            throws IllegalArgumentException {
        if (testSuite == null) {
            throw new IllegalArgumentException("No defined test suite to send");
        }
        if (!Properties.NEW_STATISTICS) {
            return;
        }

        /*
         * TODO: shouldn't a test that was never executed always be executed before sending?
         * ie, do we really need a separated public sendIndividualToMaster???
         */

        int needReExecution = 0;
        for (TestChromosome test : testSuite.getTestChromosomes()) {
            if (test.getLastExecutionResult() == null) {
                needReExecution++;
            }
        }
        boolean reExecutionSkipped = false;
        if (needReExecution > 0 && isMemoryTooLowForReExecution()) {
            long thresholdMb = (Properties.MIN_FREE_MEM * 2L) / (1024L * 1024L);
            logger.warn("Skipping re-execution of {} test(s) for statistics: free memory below {} MB. "
                            + "Cached results will be used; un-cached tests are dropped from statistics.",
                    needReExecution, thresholdMb);
            reExecutionSkipped = true;
        } else {
            for (TestChromosome test : testSuite.getTestChromosomes()) {
                if (test.getLastExecutionResult() == null) {
                    ExecutionResult result = TestCaseExecutor.runTest(test.getTestCase());
                    test.setLastExecutionResult(result);
                }
            }
        }

        sendCoveredInfo(testSuite);
        sendExceptionInfo(testSuite);
        sendIndividualToMaster(testSuite);

        if (reExecutionSkipped) {
            logger.info("Statistics for this suite are partial due to skipped re-execution.");
        }
    }

    // -------- private methods ------------------------

    /**
     * Mirrors the policy in {@code TestSuiteGenerator#isMemoryTooLowForPhase}: compares
     * free heap against {@code MIN_FREE_MEM * 2}, with a single GC retry. Returns true
     * if memory is still below the threshold and the caller should skip the work.
     */
    static boolean isMemoryTooLowForReExecution() {
        return isMemoryBelowThreshold(Properties.MIN_FREE_MEM * 2L);
    }

    /**
     * Returns true if free heap is below {@code threshold} bytes, after one GC retry.
     * Extracted so tests can drive the predicate with any threshold rather than going
     * through the int-capped {@link Properties#MIN_FREE_MEM}.
     */
    static boolean isMemoryBelowThreshold(long threshold) {
        Runtime runtime = Runtime.getRuntime();
        long freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        if (freeMem >= threshold) {
            return false;
        }
        System.gc();
        freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        return freeMem < threshold;
    }

    private static void sendExceptionInfo(TestSuiteChromosome testSuite) {

        List<ExecutionResult> results = collectExecutionResults(testSuite);

        /*
         * for each method name, check the class of thrown exceptions in those methods
         */
        Map<String, Set<Class<?>>> implicitTypesOfExceptions = new HashMap<>();
        Map<String, Set<Class<?>>> explicitTypesOfExceptions = new HashMap<>();
        Map<String, Set<Class<?>>> declaredTypesOfExceptions = new HashMap<>();

        ExceptionCoverageSuiteFitness.calculateExceptionInfo(results, implicitTypesOfExceptions,
                explicitTypesOfExceptions, declaredTypesOfExceptions, null);

        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Explicit_MethodExceptions,
                ExceptionCoverageSuiteFitness.getNumExceptions(explicitTypesOfExceptions));
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Explicit_TypeExceptions,
                ExceptionCoverageSuiteFitness.getNumClassExceptions(explicitTypesOfExceptions));
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Implicit_MethodExceptions,
                ExceptionCoverageSuiteFitness.getNumExceptions(implicitTypesOfExceptions));
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Implicit_TypeExceptions,
                ExceptionCoverageSuiteFitness.getNumClassExceptions(implicitTypesOfExceptions));

        /*
         * NOTE: in old report generator, we were using Properties.SAVE_ALL_DATA
         * to check if writing the full explicitTypesOfExceptions and implicitTypesOfExceptions
         */
    }


    /**
     * Builds the list of execution results from the suite, skipping any test whose
     * {@code getLastExecutionResult()} is {@code null}. Such nulls can occur when the
     * re-execution loop in {@link #executedAndThenSendIndividualToMaster} was skipped
     * due to low memory.
     */
    static List<ExecutionResult> collectExecutionResults(TestSuiteChromosome testSuite) {
        List<ExecutionResult> results = new ArrayList<>();
        for (TestChromosome test : testSuite.getTestChromosomes()) {
            ExecutionResult result = test.getLastExecutionResult();
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private static void sendCoveredInfo(TestSuiteChromosome testSuite) {

        Set<String> coveredMethods = new HashSet<>();
        Set<Integer> coveredTrueBranches = new HashSet<>();
        Set<Integer> coveredFalseBranches = new HashSet<>();
        Set<String> coveredBranchlessMethods = new HashSet<>();
        Set<Integer> coveredLines = new HashSet<>();

        for (TestChromosome test : testSuite.getTestChromosomes()) {
            ExecutionResult result = test.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            coveredMethods.addAll(trace.getCoveredMethods());
            coveredTrueBranches.addAll(trace.getCoveredTrueBranches());
            coveredFalseBranches.addAll(trace.getCoveredFalseBranches());
            coveredBranchlessMethods.addAll(trace.getCoveredBranchlessMethods());
            coveredLines.addAll(trace.getCoveredLines());
        }

        int coveredBranchesInstrumented = 0;
        int coveredBranchesReal = 0;
        if (Properties.ERROR_BRANCHES || Properties.EXCEPTION_BRANCHES) {
            BranchPool branchPool = BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT());
            for (Integer branchId : coveredTrueBranches) {
                Branch b = branchPool.getBranch(branchId);
                if (b.isInstrumented()) {
                    coveredBranchesInstrumented++;
                } else {
                    coveredBranchesReal++;
                }
            }
            for (Integer branchId : coveredFalseBranches) {
                Branch b = branchPool.getBranch(branchId);
                if (b.isInstrumented()) {
                    coveredBranchesInstrumented++;
                } else {
                    coveredBranchesReal++;
                }
            }
        } else {
            coveredBranchesReal = coveredTrueBranches.size() + coveredFalseBranches.size();
        }

        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Goals, testSuite.getCoveredGoals().size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Methods, coveredMethods.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Branches, coveredTrueBranches.size() + coveredFalseBranches.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Branchless_Methods, coveredBranchlessMethods.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Branches_Real, coveredBranchesReal);
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Branches_Instrumented, coveredBranchesInstrumented);
        ClientServices.getInstance().getClientNode().trackOutputVariable(
                RuntimeVariable.Covered_Lines, coveredLines.size());
    }
}
