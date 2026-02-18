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
import org.evosuite.TimeController;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.ClientStateInformation;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

public class SimpleMutationAssertionGenerator extends MutationAssertionGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SimpleMutationAssertionGenerator.class);


    @Override
    public void addAssertions(TestSuiteChromosome suite) {

        setupClassLoader(suite);

        if (!Properties.hasTargetClassBeenLoaded()) {
            // Need to load class explicitly since it was re-instrumented
            Properties.getTargetClassAndDontInitialise();
            if (!Properties.hasTargetClassBeenLoaded()) {
                logger.warn("Could not initialize SUT before Assertion generation");
            }
        }

        Set<Integer> tkilled = new HashSet<>();
        int numTest = 0;
        boolean timeIsShort = false;

        for (TestCase test : suite.getTests()) {
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                logger.warn("Reached maximum time to generate assertions, aborting assertion generation");
                break;
            }

            // If at 50% of the time we have only done X% of the tests, then don't minimise
            if (!timeIsShort && TimeController.getInstance().getPhasePercentage()
                    > Properties.ASSERTION_MINIMIZATION_FALLBACK_TIME) {
                if (numTest < Properties.ASSERTION_MINIMIZATION_FALLBACK * suite.size()) {
                    logger.warn("Assertion minimization is taking too long "
                                    + "({}% of time used, but only {}/{} tests minimized), "
                                    + "falling back to using all assertions",
                            TimeController.getInstance().getPhasePercentage(), numTest, suite.size());
                    timeIsShort = true;
                }
            }

            if (timeIsShort) {
                CompleteAssertionGenerator generator = new CompleteAssertionGenerator();
                generator.addAssertions(test);
                numTest++;
            } else {
                // Set<Integer> killed = new HashSet<Integer>();
                addAssertions(test, tkilled);
                //progressMonitor.updateStatus((100 * numTest++) / tests.size());
                ClientState state = ClientState.ASSERTION_GENERATION;
                ClientStateInformation information = new ClientStateInformation(state);
                information.setProgress((100 * numTest++) / suite.size());
                ClientServices.getInstance().getClientNode().changeState(state, information);
            }
        }

        calculateMutationScore(tkilled);
        restoreCriterion(suite);
    }


    /**
     * Generate assertions to kill all the mutants defined in the pool.
     *
     * @param test   a {@link org.evosuite.testcase.TestCase} object.
     * @param killed a {@link java.util.Set} object.
     */
    private void addAssertions(TestCase test, Set<Integer> killed) {
        addAssertions(test, killed, mutants);
        filterRedundantNonnullAssertions(test);
        filterRedundantChainedInspectorAssertions(test);
        filterRedundantIsEmptySizeAssertions(test);
    }

    /**
     * Add assertions to current test set for given set of mutants.
     *
     * @param test    a {@link org.evosuite.testcase.TestCase} object.
     * @param killed  a {@link java.util.Set} object.
     * @param mutants a {@link java.util.Map} object.
     */
    private void addAssertions(TestCase test, Set<Integer> killed,
                               Map<Integer, Mutation> mutants) {

        if (test.isEmpty()) {
            return;
        }

        logger.debug("Generating assertions");

        int s1 = killed.size();

        logger.debug("Running on original");
        ExecutionResult origResult = runTest(test);

        if (origResult.hasTimeout() || origResult.hasTestException()) {
            logger.debug("Skipping test, as it has timeouts or exceptions");
            return;
        }

        Map<Mutation, List<OutputTrace<?>>> mutationTraces = new HashMap<>();
        List<Mutation> executedMutants = new ArrayList<>();

        for (Integer mutationId : origResult.getTrace().getTouchedMutants()) {
            if (!mutants.containsKey(mutationId)) {
                //logger.warn("Mutation ID unknown: " + mutationId);
                //logger.warn(mutants.keySet().toString());
            } else {
                executedMutants.add(mutants.get(mutationId));
            }
        }

        Randomness.shuffle(executedMutants);
        logger.debug("Executed mutants: " + origResult.getTrace().getTouchedMutants());

        int numExecutedMutants = 0;
        for (Mutation m : executedMutants) {

            numExecutedMutants++;
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                logger.info("Reached maximum time to generate assertions!");
                break;
            }

            assert (m != null);
            if (MutationTimeoutStoppingCondition.isDisabled(m)) {
                killed.add(m.getId());
                continue;
            }
            if (timedOutMutations.containsKey(m)) {
                if (timedOutMutations.get(m) >= Properties.MUTATION_TIMEOUTS) {
                    logger.debug("Skipping timed out mutant");
                    killed.add(m.getId());
                    continue;
                }
            }
            if (exceptionMutations.containsKey(m)) {
                if (exceptionMutations.get(m) >= Properties.MUTATION_TIMEOUTS) {
                    logger.debug("Skipping mutant with exceptions");
                    killed.add(m.getId());
                    continue;
                }
            }
            if (Properties.MAX_MUTANTS_PER_TEST > 0
                    && numExecutedMutants > Properties.MAX_MUTANTS_PER_TEST) {
                break;
            }

            /*
            if (killed.contains(m.getId())) {
                logger.info("Skipping dead mutant");
                continue;
            }
            */

            logger.debug("Running test on mutation {}", m.toString());
            ExecutionResult mutantResult = runTest(test, m);

            int numKilled = 0;
            for (Class<?> observerClass : observerClasses) {
                if (mutantResult.getTrace(observerClass) == null
                        || origResult.getTrace(observerClass) == null) {
                    continue;
                }
                numKilled += origResult.getTrace(observerClass).getAssertions(test,
                        mutantResult.getTrace(observerClass));
            }

            List<OutputTrace<?>> traces = new ArrayList<>(
                    mutantResult.getTraces());
            mutationTraces.put(m, traces);

            if (mutantResult.hasTimeout()) {
                logger.debug("Increasing timeout count!");
                if (!timedOutMutations.containsKey(m)) {
                    timedOutMutations.put(m, 1);
                } else {
                    timedOutMutations.put(m, timedOutMutations.get(m) + 1);
                }
                MutationTimeoutStoppingCondition.timeOut(m);

            } else if (!mutantResult.noThrownExceptions()
                    && origResult.noThrownExceptions()) {
                logger.debug("Increasing exception count.");
                if (!exceptionMutations.containsKey(m)) {
                    exceptionMutations.put(m, 1);
                } else {
                    exceptionMutations.put(m, exceptionMutations.get(m) + 1);
                }
                MutationTimeoutStoppingCondition.raisedException(m);
            }

            if (numKilled > 0
                    || mutantResult.hasTimeout()
                    || (!mutantResult.noThrownExceptions() && origResult.noThrownExceptions())) {
                killed.add(m.getId());
            }
        }

        List<Assertion> assertions = test.getAssertions();
        logger.info("Got " + assertions.size() + " assertions");
        Map<Integer, Set<Integer>> killMap = new HashMap<>();
        int num = 0;
        for (Assertion assertion : assertions) {
            Set<Integer> killedMutations = new HashSet<>();
            for (Mutation m : executedMutants) {

                boolean isKilled = false;
                if (mutationTraces.containsKey(m)) {
                    for (OutputTrace<?> trace : mutationTraces.get(m)) {
                        if (trace.isDetectedBy(assertion)) {
                            isKilled = true;
                            break;
                        }
                    }
                }
                if (isKilled) {
                    killedMutations.add(m.getId());
                    assertion.addKilledMutation(m);
                }
            }
            killMap.put(num, killedMutations);
            //logger.info("Assertion " + num + " kills mutants " + killedMutations);
            num++;
        }

        int killedBefore = getNumKilledMutants(test, mutationTraces, executedMutants);

        logger.debug("Need to kill mutants: " + killedBefore);
        logger.debug(killMap.toString());
        minimize(test, executedMutants, assertions, killMap);

        int killedAfter = getNumKilledMutants(test, mutationTraces, executedMutants);

        int s2 = killed.size() - s1;
        assert (killedBefore == killedAfter) : "Mutants killed before / after / should be: "
                + killedBefore + "/" + killedAfter + "/" + s2 + ": " + test.toCode();
        logger.info("Mutants killed before / after / should be: " + killedBefore + "/"
                + killedAfter + "/" + s2);

        logger.info("Assertions in this test: " + test.getAssertions().size());

        addLastStatementFallbackAssertion(test, assertions, origResult);

        if (!origResult.noThrownExceptions()) {
            if (!test.getStatement(test.size() - 1).getAssertions().isEmpty()) {
                logger.debug("Removing assertions after exception");
                test.getStatement(test.size() - 1).removeAssertions();
            }
        }

    }

    /**
     * If the last statement's return value has no meaningful assertion, try to
     * add one using priorities 1-3 (from the mutation kill pool) and then
     * priority 4 (from execution traces).
     */
    private void addLastStatementFallbackAssertion(TestCase test,
                                                    List<Assertion> assertions,
                                                    ExecutionResult origResult) {
        Statement lastStatement = test.getStatement(test.size() - 1);
        if (!returnValueWithoutAssertion(lastStatement)
                && !lastStatement.getAssertions().isEmpty()
                && !justNullAssertion(lastStatement)) {
            return;
        }

        VariableReference returnValue = lastStatement.getReturnValue();
        logger.info("Last statement return value lacks meaningful assertion: " + test.toCode());
        logger.info("Assertions to choose from: " + assertions.size());

        boolean haveAssertion = findMutationAssertionFromPool(lastStatement, assertions, returnValue);

        if (!haveAssertion) {
            haveAssertion = findTraceBasedAssertion(lastStatement, origResult, test, returnValue);
        }

        logger.info("2. Done with assertions");
        filterInspectorPrimitiveDuplication(lastStatement);
    }

    /**
     * Priorities 1-3: find assertion from the mutation-killed assertion pool.
     *
     * @return true if an assertion was added
     */
    private boolean findMutationAssertionFromPool(Statement lastStatement,
                                                   List<Assertion> assertions,
                                                   VariableReference returnValue) {
        // Priority 1: PrimitiveAssertion referencing the return value
        for (Assertion assertion : assertions) {
            if (assertion instanceof PrimitiveAssertion
                    && assertion.getStatement().equals(lastStatement)
                    && assertion.getReferencedVariables().contains(returnValue)) {
                logger.debug("Adding a primitive assertion " + assertion);
                lastStatement.addAssertion(assertion);
                return true;
            }
        }

        // Priority 2: InspectorAssertion referencing the return value
        for (Assertion assertion : assertions) {
            if (assertion instanceof InspectorAssertion
                    && assertion.getStatement().equals(lastStatement)
                    && assertion.getReferencedVariables().contains(returnValue)) {
                logger.debug("Adding an inspector assertion " + assertion);
                lastStatement.addAssertion(assertion);
                return true;
            }
        }

        // Priority 3: Any non-null assertion referencing the return value
        for (Assertion assertion : assertions) {
            if (assertion instanceof NullAssertion) {
                continue;
            }
            if (assertion.getStatement().equals(lastStatement)
                    && assertion.getReferencedVariables().contains(returnValue)) {
                logger.debug("Adding a non-null assertion " + assertion);
                lastStatement.addAssertion(assertion);
                return true;
            }
        }

        return false;
    }

    /**
     * Priority 4: Fall back to trace-based assertions for the last statement.
     *
     * @return true if an assertion was added
     */
    private boolean findTraceBasedAssertion(Statement lastStatement,
                                             ExecutionResult origResult,
                                             TestCase test,
                                             VariableReference returnValue) {
        logger.info("No assertion from killed mutants, trying all trace assertions");
        Method inspectorMethod = null;
        if (lastStatement instanceof MethodStatement) {
            MethodStatement methodStatement = (MethodStatement) lastStatement;
            Method method = methodStatement.getMethod().getMethod();
            if (method.getParameterTypes().length == 0) {
                if (method.getReturnType().isPrimitive()
                        && !method.getReturnType().equals(void.class)) {
                    inspectorMethod = method;
                }
            }
        }

        // Collect all trace assertions for the last statement without
        // disturbing existing assertions on other statements
        Set<Assertion> existingAssertions = new HashSet<>(lastStatement.getAssertions());
        for (OutputTrace<?> trace : origResult.getTraces()) {
            trace.getAllAssertions(test);
        }
        Set<Assertion> allLastAssertions = new HashSet<>(lastStatement.getAssertions());
        // Remove trace-added assertions, restoring original state
        lastStatement.removeAssertions();
        for (Assertion existing : existingAssertions) {
            lastStatement.addAssertion(existing);
        }

        boolean haveAssertion = false;
        if (!returnValue.isVoid()) {
            logger.debug("Return value is non void: " + returnValue.getClassName());

            // First pass: non-null assertions referencing the return value
            for (Assertion ass : allLastAssertions) {
                if (ass.getReferencedVariables().contains(returnValue)
                        && !(ass instanceof NullAssertion)) {

                    if (ass instanceof InspectorAssertion) {
                        if (((InspectorAssertion) ass).inspector.getMethod().equals(inspectorMethod)) {
                            continue;
                        }
                    }

                    lastStatement.addAssertion(ass);
                    logger.debug("Adding trace assertion " + ass.getCode());
                    haveAssertion = true;
                    break;
                }
            }
            // Second pass: allow NullAssertion as last resort
            if (!haveAssertion) {
                for (Assertion ass : allLastAssertions) {
                    if (ass.getReferencedVariables().contains(returnValue)) {
                        lastStatement.addAssertion(ass);
                        logger.debug("Adding trace assertion " + ass.getCode());
                        haveAssertion = true;
                        break;
                    }
                }
            }
        } else {
            logger.debug("Return value is void");

            Set<VariableReference> targetVars = lastStatement.getVariableReferences();
            for (Assertion ass : allLastAssertions) {
                Set<VariableReference> vars = new HashSet<>(ass.getReferencedVariables());
                vars.retainAll(targetVars);
                if (!vars.isEmpty()) {
                    lastStatement.addAssertion(ass);
                    haveAssertion = true;
                    break;
                }
            }
        }
        logger.info("1. Done with assertions");
        return haveAssertion;
    }

    private static int assertionPriority(Assertion a) {
        if (a instanceof PrimitiveAssertion) {
            return 4; // Best: definite values
        }
        if (a instanceof ArrayLengthAssertion) {
            return 3; // Structural info
        }
        if (a instanceof EqualsAssertion) {
            return 2; // Object equality
        }
        if (a instanceof ContainsAssertion) {
            return 2; // Membership
        }
        if (a instanceof InspectorAssertion) {
            return 1; // Inspector calls
        }
        if (a instanceof NullAssertion) {
            return 0; // Weakest standalone
        }
        return 1; // SameAssertion, CompareAssertion, etc.
    }

    /**
     * Return a minimal subset of the assertions that covers all killable
     * mutants.
     *
     * @param test       The test case that should be executed
     * @param mutants    The list of mutants of the unit
     * @param assertions All assertions that can be generated for the test case
     * @param killMap    Mapping of assertion to mutant ids that are killed by the
     *                   assertion
     */
    private void minimize(TestCase test, List<Mutation> mutants,
                          final List<Assertion> assertions, Map<Integer, Set<Integer>> killMap) {

        class Pair implements Comparable<Object> {
            final Integer assertion;
            final Integer numKilled;

            public Pair(int a, int k) {
                assertion = a;
                numKilled = k;
            }

            @Override
            public int compareTo(Object o) {
                Pair other = (Pair) o;
                if (numKilled.equals(other.numKilled)) {
                    Assertion first = assertions.get(assertion);
                    Assertion second = assertions.get(other.assertion);
                    int p1 = assertionPriority(first);
                    int p2 = assertionPriority(second);
                    if (p1 != p2) {
                        return Integer.compare(p1, p2);
                    } else {
                        return assertion.compareTo(other.assertion);
                    }
                } else {
                    return numKilled.compareTo(other.numKilled);
                }
            }
        }

        Set<Integer> toKill = new HashSet<>();
        for (Entry<Integer, Set<Integer>> entry : killMap.entrySet()) {
            toKill.addAll(entry.getValue());
        }
        logger.debug("Need to kill mutants: " + toKill.size());

        Set<Integer> killed = new HashSet<>();
        Set<Assertion> result = new HashSet<>();

        boolean done = false;
        while (!done) {
            // logger.info("Have to kill "+to_kill.size());
            List<Pair> a = new ArrayList<>();
            for (Entry<Integer, Set<Integer>> entry : killMap.entrySet()) {
                int num = 0;
                for (Integer m : entry.getValue()) {
                    if (!killed.contains(m)) {
                        num++;
                    }
                }
                if (num > 0) {
                    a.add(new Pair(entry.getKey(), num));
                }
            }
            if (a.isEmpty()) {
                done = true;
            } else {
                Pair best = Collections.max(a);
                // logger.info("Chosen "+best.assertion);
                result.add(assertions.get(best.assertion));
                for (Integer m : killMap.get(best.assertion)) {
                    // logger.info("Killed "+m);
                    killed.add(m);
                    toKill.remove(m);
                }
            }
        }
        logger.debug("Killed mutants: " + killed.size());

        // sort by number of assertions killed
        // pick assertion that kills most
        // remove all mutations that are already killed
        logger.debug("Minimized assertions from " + assertions.size() + " to "
                + result.size());

        if (!result.isEmpty()) {
            test.removeAssertions();

            for (Assertion assertion : result) {
                assertion.getStatement().addAssertion(assertion);
            }
        } else {
            logger.debug("Not removing assertions because no new assertions were found");
        }

    }
}
