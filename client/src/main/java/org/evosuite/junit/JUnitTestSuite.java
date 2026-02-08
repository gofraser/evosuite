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

import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.runner.JUnitCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;


/**
 * JUnitTestSuite class.
 *
 * @author Gordon Fraser
 */
public class JUnitTestSuite {

    private static final Logger logger = LoggerFactory.getLogger(JUnitTestSuite.class);

    private Set<String> coveredMethods;

    private Set<Integer> coveredBranchesTrue;

    private Set<Integer> coveredBranchesFalse;

    private final TestCaseExecutor executor = TestCaseExecutor.getInstance();

    /**
     * Run suite.
     *
     * @param name a {@link java.lang.String} object.
     */
    public void runSuite(String name) {
        try {
            Class<?> forName = null;
            forName = Class.forName(name);
            logger.info("Running against JUnit test suite " + name);
            JUnitCore.runClasses(forName);
            ExecutionTrace trace = ExecutionTracer.getExecutionTracer().getTrace();

            coveredMethods = new HashSet<>();
            coveredBranchesTrue = trace.getCoveredTrueBranches();
            coveredBranchesFalse = trace.getCoveredFalseBranches();

            for (String methodName : trace.getCoveredMethods()) {
                if (!methodName.contains("$")) {
                    coveredMethods.add(methodName);
                }
            }

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run suite.
     *
     * @param chromosome a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    public void runSuite(TestSuiteChromosome chromosome) {
        coveredMethods = new HashSet<>();
        coveredBranchesTrue = new HashSet<>();
        coveredBranchesFalse = new HashSet<>();

        for (TestCase test : chromosome.getTests()) {
            ExecutionResult result = runTest(test);
            coveredMethods.addAll(result.getTrace().getCoveredMethods());
            coveredBranchesTrue.addAll(result.getTrace().getCoveredTrueBranches());
            coveredBranchesFalse.addAll(result.getTrace().getCoveredFalseBranches());
        }
    }

    /**
     * Getter for the field <code>coveredMethods</code>.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<String> getCoveredMethods() {
        return coveredMethods;
    }

    /**
     * getTrueCoveredBranches.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<Integer> getTrueCoveredBranches() {
        return coveredBranchesTrue;
    }

    /**
     * getFalseCoveredBranches.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<Integer> getFalseCoveredBranches() {
        return coveredBranchesFalse;
    }

    /**
     * runTest.
     *
     * @param test a {@link org.evosuite.testcase.TestCase} object.
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    public ExecutionResult runTest(TestCase test) {

        ExecutionResult result = new ExecutionResult(test, null);

        try {
            logger.debug("Executing test");
            result = executor.execute(test);

            int num = test.size();
            MaxStatementsStoppingCondition.statementsExecuted(num);
            //result.touched.addAll(HOMObserver.getTouched());

        } catch (Exception e) {
            throw new Error(e);
        }

        return result;
    }

}
