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
package org.evosuite.strategy;

import org.evosuite.ProgressMonitor;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.FitnessFunctionsUtils;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTestsStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingConditionFactory;
import org.evosuite.ga.stoppingconditions.ZeroFitnessStoppingCondition;
import org.evosuite.graphs.cfg.CFGMethodAdapter;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.rmi.ClientServices;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.util.List;

/**
 * This is the abstract superclass of all techniques to generate a set of tests
 * for a target class, which does not necessarily require the use of a GA.
 *
 * <p>
 * Postprocessing is not done as part of the test generation strategy.
 * </p>
 *
 * @author gordon
 */
public abstract class TestGenerationStrategy {

    /**
     * There should only be one progress monitor.
     */
    private final ProgressMonitor<TestSuiteChromosome> progressMonitor = new ProgressMonitor<>();

    /**
     * Stopping condition for zero fitness (coverage goals satisfied).
     */
    private final ZeroFitnessStoppingCondition<TestSuiteChromosome> zeroFitness =
            new ZeroFitnessStoppingCondition<>();

    /**
     * Global timeout stopping condition.
     */
    private final StoppingCondition<TestSuiteChromosome> globalTime =
            new GlobalTimeStoppingCondition<>();

    /**
     * Generate a set of tests; assume that all analyses are already completed.
     *
     * @return the generated test suite
     */
    public abstract TestSuiteChromosome generateTests();

    /**
     * Send execution statistics to the master process.
     */
    protected void sendExecutionStatistics() {
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Statements_Executed,
                MaxStatementsStoppingCondition.getNumExecutedStatements());
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Tests_Executed,
                MaxTestsStoppingCondition.getNumExecutedTests());
    }

    /**
     * Convert criterion names to test suite fitness functions.
     *
     * @return list of test suite fitness functions
     */
    protected List<TestSuiteFitnessFunction> getFitnessFunctions() {
        return FitnessFunctionsUtils.getFitnessFunctions(Properties.CRITERION);
    }

    /**
     * Convert criterion names to factories for test case fitness functions.
     *
     * @return list of fitness factories
     */
    public static List<TestFitnessFactory<? extends TestFitnessFunction>> getFitnessFactories() {
        return FitnessFunctionsUtils.getFitnessFactories(Properties.CRITERION);
    }

    /**
     * Check if the budget has been used up. The GA will do this check
     * on its own, but other strategies (e.g. random) may depend on this function.
     *
     * @param chromosome        the current best individual
     * @param stoppingCondition the primary stopping condition
     * @return true if the search should stop
     */
    protected boolean isFinished(TestSuiteChromosome chromosome,
                                 StoppingCondition<TestSuiteChromosome> stoppingCondition) {
        if (stoppingCondition.isFinished()) {
            return true;
        }

        if (Properties.STOP_ZERO && chromosome.getFitness() == 0.0) {
            return true;
        }

        return !(stoppingCondition instanceof MaxTimeStoppingCondition) && globalTime.isFinished();
    }

    /**
     * Convert property to actual stopping condition.
     *
     * @return the configured stopping condition
     */
    protected StoppingCondition<TestSuiteChromosome> getStoppingCondition() {
        return StoppingConditionFactory.getStoppingCondition(Properties.STOPPING_CONDITION);
    }

    /**
     * Check if it is possible to generate tests for the System Under Test (SUT).
     *
     * @return true if tests can be generated
     */
    protected boolean canGenerateTestsForSUT() {
        if (TestCluster.getInstance().getNumTestCalls() > 0) {
            return true;
        }

        // No test calls found, check if we can use reflection or if there are any methods
        final InstrumentingClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
        final int numMethods = CFGMethodAdapter.getNumMethods(cl);
        return Properties.P_REFLECTION_ON_PRIVATE > 0.0 && numMethods > 0;
    }

    /**
     * Get the progress monitor.
     *
     * @return the progress monitor
     */
    protected ProgressMonitor<TestSuiteChromosome> getProgressMonitor() {
        return progressMonitor;
    }

    /**
     * Get the zero fitness stopping condition.
     *
     * @return the zero fitness stopping condition
     */
    protected ZeroFitnessStoppingCondition<TestSuiteChromosome> getZeroFitness() {
        return zeroFitness;
    }

    /**
     * Get the global time stopping condition.
     *
     * @return the global time stopping condition
     */
    protected StoppingCondition<TestSuiteChromosome> getGlobalTime() {
        return globalTime;
    }
}
