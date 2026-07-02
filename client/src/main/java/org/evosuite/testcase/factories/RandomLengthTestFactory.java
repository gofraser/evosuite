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
package org.evosuite.testcase.factories;

import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.testcase.synthesizer.TestCaseLegitimizer;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory that creates {@link TestChromosome}s of random length.
 *
 * <p>When {@link Properties#APPLY_OBJECT_RULE} is enabled and a working branch has been selected
 * (via {@link #workingBranch4ObjectGraph}), a freshly generated random test may be replaced with a
 * synthesized object-construction template for that branch (EvoObj, see the re-implementation plan
 * §3.7). With {@code apply_object_rule=false} this class behaves exactly as upstream.
 *
 * @author Gordon Fraser
 */
public class RandomLengthTestFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -5202578461625984100L;

    /**
     * The logger for this class.
     */
    protected static final Logger logger = LoggerFactory.getLogger(RandomLengthTestFactory.class);

    /** Number of templates whose initial legitimacy distance was non-zero (statistics). */
    public static int legitimizationTrials = 0;

    /** Number of those templates that legitimization drove to distance 0 (statistics). */
    public static int legitimizationSuccess = 0;

    /** Number of random tests replaced by a synthesized template (statistics, plan E8). */
    public static int templateInjections = 0;

    /** Cumulative time (ms) spent synthesizing + legitimizing templates (statistics, plan E8). */
    public static long templateSynthesisTimeMs = 0;

    /**
     * The uncovered branch the object-construction template should target for the next generated
     * test. Set by the GAs each generation (MonotonicGA / AbstractMOSA); {@code null} disables
     * template synthesis for this test.
     */
    public static Branch workingBranch4ObjectGraph = null;

    /**
     * Creates a random test case (i.e., a test case consisting of random statements) with the given.
     * {@code size} as an exclusive upper bound for the number of contained statements. In
     * particular, {@code size} is chosen at random from the interval [1, size). This means that
     * returned test cases contain at most {@code size - 1} statements. Usually, one can also expect
     * the test case to contain at least one statement, but it is still possible that an empty
     * test case is returned, although very unlikely.
     *
     * @param size the upper bound for the test case length
     * @return a random test case
     */
    private TestCase getRandomTestCase(int size) {
        boolean tracerEnabled = ExecutionTracer.isEnabled();
        if (tracerEnabled) {
            ExecutionTracer.disable();
        }

        final TestCase test = Properties.APPLY_OBJECT_RULE
                ? getObjectRuleTestCase(size)
                : getDefaultRandomTestCase(size);

        if (logger.isDebugEnabled()) {
            logger.debug("Randomized test case:" + test.toCode());
        }

        if (tracerEnabled) {
            ExecutionTracer.enable();
        }

        return test;
    }

    /**
     * Upstream default random-test generation. Unchanged behavior.
     */
    private TestCase getDefaultRandomTestCase(int size) {
        final TestCase test = getNewTestCase();
        final TestFactory testFactory = TestFactory.getInstance();

        // Choose a random length between 1 (inclusive) and size (exclusive).
        int length = 0;
        if (size > 1) {
            length = Randomness.nextInt(1, size);
        } else {
            logger.warn("Requested random test case size {} is too small, defaulting to empty test case.", size);
        }

        // Then add random statements until the test case reaches the chosen length or we run out of
        // generation attempts.
        for (int num = 0; test.size() < length && num < Properties.MAX_ATTEMPTS; num++) {
            // NOTE: Even though extremely unlikely, insertRandomStatement could fail every time
            // with return code -1, thus eventually exceeding MAX_ATTEMPTS. In this case, the
            // returned test case would indeed be empty!
            testFactory.insertRandomStatement(test, test.size() - 1);
        }

        return test;
    }

    /**
     * EvoObj generation path (plan §3.7). Only reached when {@code apply_object_rule=true}. After
     * the first inserted statement the test contains the target-method call (guaranteed by the M1
     * {@code TestCluster.getRandomTestCall} changes); at that point, with probability
     * {@link Properties#P_OBJECT_RULE_APPLICATION}, the test is turned into an object-construction
     * template for {@link #workingBranch4ObjectGraph} and legitimized.
     */
    private TestCase getObjectRuleTestCase(int size) {
        TestCase test = getNewTestCase();
        final TestFactory testFactory = TestFactory.getInstance();

        // Object-rule construction disables primitive reuse so template "slots" get fresh values
        // (the fork's implicit slot mechanism, plan E5). Saved and restored around this method.
        final double oldPrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;
        Properties.PRIMITIVE_REUSE_PROBABILITY = 0;
        try {
            int length = Randomness.nextInt(size);
            while (length == 0) {
                length = Randomness.nextInt(size);
            }

            boolean applyObjectRule = workingBranch4ObjectGraph != null
                    && Randomness.nextDouble() <= Properties.P_OBJECT_RULE_APPLICATION;
            boolean allowNullValue = Properties.NULL_PROBABILITY > Randomness.nextDouble();

            int num = 0;
            while (test.size() < length && num < Properties.MAX_ATTEMPTS) {
                int position = test.size() - 1;

                int targetMethodCallPosition = -1;
                if (!Properties.TARGET_METHOD.isEmpty()) {
                    targetMethodCallPosition = TestGenerationUtil.getTargetMethodPosition(test, test.size() - 1);
                    if (targetMethodCallPosition != -1) {
                        // Keep insertions before the target-method call.
                        position = targetMethodCallPosition - 1;
                    }
                }

                // The first inserted statement is the target-method call; once it is present, turn
                // the test into an object-construction template for the working branch.
                if (num == 1 && targetMethodCallPosition != -1 && applyObjectRule) {
                    test = synthesizeAndLegitimize(test, allowNullValue);
                    break;
                } else {
                    int success = testFactory.insertRandomStatement(test, position);
                    int count = 0;
                    while (test.size() == 0 || success == -1) {
                        test = new DefaultTestCase();
                        success = testFactory.insertRandomStatement(test, 0);
                        if (test.size() != 0 && success != -1 && !allowNullValue) {
                            mutateNullStatements(test);
                        }
                        count++;
                        if (count > 10) {
                            break;
                        }
                    }
                }

                num++;
            }
        } finally {
            Properties.PRIMITIVE_REUSE_PROBABILITY = oldPrimitiveReuse;
        }

        return test;
    }

    /**
     * Builds the object-construction template for {@code test} against the current working branch
     * and, if the template throws before the target call, runs the legitimizer to try to reach it.
     *
     * @return the legitimized test if legitimization ran, otherwise the template test
     */
    private TestCase synthesizeAndLegitimize(TestCase test, boolean allowNullValue) {
        Branch b = workingBranch4ObjectGraph;
        if (b == null) {
            Map<Branch, Set<DepVariable>> interestedBranches =
                    ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
            if (interestedBranches == null || interestedBranches.isEmpty()) {
                return test;
            }
            b = Randomness.choice(interestedBranches.keySet());
        }

        long synthesisStart = System.currentTimeMillis();
        boolean reachedTargetCall = false;
        try {
            ConstructionPathSynthesizer synthesizer = new ConstructionPathSynthesizer(false);
            synthesizer.buildNodeStatementCorrespondence(test, b, allowNullValue);
            templateInjections++;
            if (!allowNullValue) {
                mutateNullStatements(test);
            }

            PartialGraph graph = synthesizer.getPartialGraph();
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap = synthesizer.getGraph2CodeMap();

            TestChromosome templateChromosome = new TestChromosome();
            templateChromosome.setTestCase(test);
            ExecutionResult result = TestCaseExecutor.getInstance().execute(test);
            templateChromosome.setLastExecutionResult(result);
            double oldLegitimacyDistance = templateChromosome.getLegitimacyDistance();

            if (oldLegitimacyDistance == 0) {
                reachedTargetCall = true;
            } else {
                legitimizationTrials++;
                // Bound legitimization by both the configured total budget and the remaining search
                // phase time, so a tight search_budget still terminates on time (plan E8).
                long now = System.currentTimeMillis();
                boolean withinTotalBudget =
                        now - TestCaseLegitimizer.startTime <= Properties.TOTAL_LEGITIMIZATION_BUDGET * 1000L;
                if (withinTotalBudget && TimeController.getInstance().isThereStillTimeInThisPhase()) {
                    TestChromosome legitimizedChromosome =
                            TestCaseLegitimizer.getInstance().legitimize(templateChromosome, graph, graph2CodeMap);
                    // Keep the legitimized test (the fork discarded it, plan §7).
                    test = legitimizedChromosome.getTestCase();
                    if (legitimizedChromosome.getLegitimacyDistance() == 0) {
                        legitimizationSuccess++;
                        reachedTargetCall = true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Object-rule synthesis/legitimization failed for branch " + b + ": " + e.getMessage(), e);
        } finally {
            templateSynthesisTimeMs += System.currentTimeMillis() - synthesisStart;
            // Feed the outcome back to the branch selector's negative cache (plan E3).
            ObjectRuleBranchSelector.getInstance().recordSynthesisAttempt(b, reachedTargetCall);
        }

        return test;
    }

    /**
     * Resets the object-rule statistics counters. Invoked from
     * {@link ObjectConstructionContext#reset()} so runs do not leak counts into one another
     * (plan E7/E8).
     */
    public static void resetStatistics() {
        legitimizationTrials = 0;
        legitimizationSuccess = 0;
        templateInjections = 0;
        templateSynthesisTimeMs = 0;
    }

    /**
     * Reports the EvoObj object-rule statistics as {@link RuntimeVariable}s (plan E8). Called once
     * at the end of the search from {@link org.evosuite.statistics.StatisticsListener}; a no-op
     * effect unless {@code apply_object_rule} is enabled.
     */
    public static void trackObjectRuleStatistics() {
        double successRate = legitimizationTrials == 0
                ? 0.0 : (double) legitimizationSuccess / legitimizationTrials;

        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectGraphBranches, countObjectGraphBranches());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectGraphMaxSize, maxObjectGraphSize());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.TemplateSynthesisTime, templateSynthesisTimeMs);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.TemplateInjections, templateInjections);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LegitimizationSuccessRate, successRate);
    }

    /** Number of branches whose object-construction graph has at least one dependent variable. */
    static int countObjectGraphBranches() {
        int graphBranches = 0;
        for (Map<Branch, Set<DepVariable>> perBranch : ObjectConstructionContext.branchInterestedVarsMap.values()) {
            for (Set<DepVariable> vars : perBranch.values()) {
                if (vars != null && !vars.isEmpty()) {
                    graphBranches++;
                }
            }
        }
        return graphBranches;
    }

    /** Size (in dependent variables) of the largest object-construction graph over all branches. */
    static int maxObjectGraphSize() {
        int maxGraphSize = 0;
        for (Map<Branch, Set<DepVariable>> perBranch : ObjectConstructionContext.branchInterestedVarsMap.values()) {
            for (Set<DepVariable> vars : perBranch.values()) {
                if (vars != null) {
                    maxGraphSize = Math.max(maxGraphSize, vars.size());
                }
            }
        }
        return maxGraphSize;
    }

    private void mutateNullStatements(TestCase test) {
        for (int i = 0; i < test.size(); i++) {
            Statement s = test.getStatement(i);
            if (s instanceof NullStatement) {
                TestFactory.getInstance().changeNullStatement(test, s);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generate a random chromosome.
     */
    @Override
    public TestChromosome getChromosome() {
        TestChromosome c = new TestChromosome();
        c.setTestCase(getRandomTestCase(Properties.CHROMOSOME_LENGTH));
        return c;
    }

    /**
     * Provided so that subtypes of this factory type can modify the returned
     * TestCase.
     *
     * @return a {@link org.evosuite.testcase.TestCase} object.
     */
    protected TestCase getNewTestCase() {
        return new DefaultTestCase(); // empty test case
    }

}
