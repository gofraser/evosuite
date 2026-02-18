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
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationObserver;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.*;

/**
 * This class executes a test case on a unit and all mutants and infers
 * assertions from the resulting traces.
 *
 * <p>TODO: This class is a mess.</p>
 *
 * @author Gordon Fraser
 */
public abstract class MutationAssertionGenerator extends AssertionGenerator {

    private static final Logger logger = LoggerFactory.getLogger(MutationAssertionGenerator.class);

    protected final Map<Integer, Mutation> mutants = new HashMap<>();

    protected static final Map<Mutation, Integer> timedOutMutations = new HashMap<>();

    protected static final Map<Mutation, Integer> exceptionMutations = new HashMap<>();

    /**
     * Default constructor.
     */
    public MutationAssertionGenerator() {
        for (Mutation m : MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getMutants()) {
            mutants.put(m.getId(), m);
        }
        TestCaseExecutor.getInstance().newObservers();
        registerObservers();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Execute a test case on the original unit.</p>
     */
    @Override
    protected ExecutionResult runTest(TestCase test) {
        return runTest(test, null);
    }

    /**
     * Execute a test case on a mutant.
     *
     * @param test   The test case that should be executed
     * @param mutant The mutant on which the test case shall be executed
     */
    protected ExecutionResult runTest(TestCase test, Mutation mutant) {
        ExecutionResult result = new ExecutionResult(test, mutant);
        // resetObservers();
        clearObservers();
        try {
            logger.debug("Executing test");
            if (mutant == null) {
                MutationObserver.deactivateMutation();
            } else {
                MutationObserver.activateMutation(mutant);
            }
            result = TestCaseExecutor.getInstance().execute(test);
            MutationObserver.deactivateMutation(mutant);

            int num = test.size();
            MaxStatementsStoppingCondition.statementsExecuted(num);

            collectTraces(result);

        } catch (Exception e) {
            throw new Error(e);
        }

        return result;
    }

    protected Criterion[] oldCriterion = Properties.CRITERION;

    /**
     * If we are not doing mutation testing anyway, then we need to reinstrument
     * the code to get the mutants now.
     *
     * @param suite a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    @Override
    public void setupClassLoader(TestSuiteChromosome suite) {
        oldCriterion = Arrays.copyOf(Properties.CRITERION, Properties.CRITERION.length);
        if (!ArrayUtil.contains(oldCriterion, Criterion.MUTATION)
                && !ArrayUtil.contains(oldCriterion, Criterion.WEAKMUTATION)
                && !ArrayUtil.contains(oldCriterion, Criterion.ONLYMUTATION)
                && !ArrayUtil.contains(oldCriterion, Criterion.STRONGMUTATION)) {
            Properties.CRITERION = new Criterion[]{Criterion.MUTATION};
        }
        if (Properties.RESET_STATIC_FIELDS) {
            final boolean resetAllClasses = Properties.RESET_ALL_CLASSES_DURING_ASSERTION_GENERATION;
            ClassReInitializer.getInstance().setReInitializeAllClasses(resetAllClasses);
        }
        changeClassLoader(suite);
        for (Mutation m : MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getMutants()) {
            mutants.put(m.getId(), m);
        }
    }

    /**
     * Set the criterion to whatever it was before.
     *
     * @param suite a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    protected void restoreCriterion(TestSuiteChromosome suite) {
        Properties.CRITERION = oldCriterion;
    }

    /**
     * We send status about the mutation score when we're done, because we know
     * it.
     *
     * @param tkilled a {@link java.util.Set} object.
     */
    protected void calculateMutationScore(Set<Integer> tkilled) {
        if (MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getMutantCounter() == 0) {
            Properties.CRITERION = oldCriterion;
            // SearchStatistics.getInstance().mutationScore(1.0);
            LoggingUtils.getEvoLogger()
                    .info("* Resulting test suite's mutation score: " + NumberFormat.getPercentInstance().format(1.0));
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.MutationScore, 1.0);

        } else {
            double score = (double) tkilled.size() / (double) MutationPool.getInstance(
                    TestGenerationContext.getInstance().getClassLoaderForSUT()).getMutantCounter();
            // SearchStatistics.getInstance().mutationScore(score);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.MutationScore, score);
            LoggingUtils.getEvoLogger().info(
                    "* Resulting test suite's mutation score: " + NumberFormat.getPercentInstance().format(score));
        }
    }

    /**
     * Returns the number of killed mutants.
     *
     * @param test            the test case
     * @param mutationTraces  the mutation traces
     * @param executedMutants the executed mutants
     * @return the number of killed mutants
     */
    protected int getNumKilledMutants(TestCase test, Map<Mutation, List<OutputTrace<?>>> mutationTraces,
                                      List<Mutation> executedMutants) {
        List<Assertion> assertions;
        Set<Integer> killed = new HashSet<>();
        assertions = test.getAssertions();
        for (Assertion assertion : assertions) {
            for (Mutation m : executedMutants) {

                boolean isKilled = false;
                if (mutationTraces.containsKey(m)) {
                    int i = 0;
                    for (OutputTrace<?> trace : mutationTraces.get(m)) {
                        isKilled = trace.isDetectedBy(assertion);
                        if (isKilled) {
                            logger.debug("Mutation killed: " + m.getId() + " by trace " + i++);
                            killed.add(m.getId());
                            break;
                        }
                        i++;
                    }
                } else {
                    // Mutant not in traces means it was not covered, not killed
                }
            }
        }
        logger.debug("Killed mutants: " + killed);
        return killed.size();
    }

    /**
     * Returns true if the statement has nothing but null assertions.
     *
     * @param statement the statement
     * @return true if the statement has nothing but null assertions
     */
    protected boolean justNullAssertion(Statement statement) {
        Set<Assertion> assertions = statement.getAssertions();
        if (assertions.isEmpty()) {
            return false;
        } else {
            Iterator<Assertion> iterator = assertions.iterator();
            VariableReference ret = statement.getReturnValue();
            VariableReference callee = null;
            if (statement instanceof MethodStatement) {
                callee = ((MethodStatement) statement).getCallee();
            }
            boolean just = true;
            while (iterator.hasNext()) {
                Assertion ass = iterator.next();
                if (!(ass instanceof NullAssertion)) {
                    if (ass.getReferencedVariables().contains(ret) || ass.getReferencedVariables().contains(callee)) {
                        just = false;
                        break;
                    }
                }
            }

            return just;
        }
    }

    protected boolean primitiveWithoutAssertion(Statement statement) {
        if (!statement.getReturnValue().isPrimitive()) {
            return false;
        }

        Set<Assertion> assertions = statement.getAssertions();
        if (assertions.isEmpty()) {
            return true;
        } else {
            Iterator<Assertion> iterator = assertions.iterator();
            VariableReference ret = statement.getReturnValue();
            while (iterator.hasNext()) {
                Assertion ass = iterator.next();
                if (ass instanceof PrimitiveAssertion) {
                    if (ass.getReferencedVariables().contains(ret)) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    /**
     * Returns true if the statement has a non-void return value but no meaningful
     * assertion (i.e., only NullAssertions or no assertions) on that return value.
     * Covers primitives, Strings, wrappers, and complex objects.
     *
     * @param statement the statement to check
     * @return true if the return value lacks a meaningful assertion
     */
    protected boolean returnValueWithoutAssertion(Statement statement) {
        VariableReference ret = statement.getReturnValue();
        if (ret.isVoid()) {
            return false;
        }

        Set<Assertion> assertions = statement.getAssertions();
        if (assertions.isEmpty()) {
            return true;
        }

        for (Assertion ass : assertions) {
            if (!(ass instanceof NullAssertion) && ass.getReferencedVariables().contains(ret)) {
                return false;
            }
        }

        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.evosuite.assertion.AssertionGenerator#addAssertions(org.evosuite.
     * testcase.TestCase)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAssertions(TestCase test) {
        // TODO Auto-generated method stub

    }

}
