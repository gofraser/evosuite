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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
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
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.ObjectRuleTelemetry;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.synthesizer.TemplateCache;
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
 * synthesized object-construction template for that branch (EvoObj). With {@code apply_object_rule=false} this class behaves exactly as upstream.
 *
 * @author Gordon Fraser
 */
public class RandomLengthTestFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -5202578461625984100L;

    /**
     * The logger for this class.
     */
    protected static final Logger logger = LoggerFactory.getLogger(RandomLengthTestFactory.class);

    /**
     * Number of throwing templates for which the legitimizer was actually run (statistics). Only
     * counted when the legitimizer is invoked; templates skipped because the total budget was
     * already spent are counted in {@link #legitimizationSkipped}, not here, so the success rate
     * reflects the legitimizer's efficacy rather than budget starvation.
     */
    public static int legitimizationTrials = 0;

    /** Number of attempted legitimizations that reached distance 0 (statistics). */
    public static int legitimizationSuccess = 0;

    /**
     * Number of throwing templates skipped without legitimization because the total legitimization
     * budget (or the remaining search phase time) was already exhausted (statistics). Large relative
     * to {@link #legitimizationTrials} means the budget, not the algorithm, is the bottleneck.
     */
    public static int legitimizationSkipped = 0;

    /** Number of random tests replaced by a synthesized template (statistics). */
    public static int templateInjections = 0;

    /** Current GA iteration, stamped by the search before object-rule branch selection. */
    public static int currentIteration = 0;

    /** Last generation in which a fresh non-cache synthesis was attempted. */
    public static int lastFreshSynthesisIteration = 0;

    /** Number of cached template clones reused. */
    public static int templateCacheHits = 0;

    /** Number of fresh non-cache synthesis attempts. */
    public static int freshSyntheses = 0;

    /** Number of fresh syntheses whose first execution reached legitimacy distance 0. */
    public static int firstShotSuccesses = 0;

    /** Number of fresh syntheses whose first execution covered a selected working goal. */
    public static int immediateCoverage = 0;

    /** Number of fresh syntheses that reached the call but missed the selected working goals. */
    public static int reachedNotCovered = 0;

    /** Number of already-reaching fresh syntheses with no selected working goals to score. */
    public static int workingGoalUnavailable = 0;

    /** Number of already-reaching fresh syntheses that entered the R1 tuning gate. */
    public static int tuningOpportunities = 0;

    /** Number of R1 tuning opportunities skipped because the template had no primitive slots. */
    public static int tuningNoSlotSkips = 0;

    /** Extra executions spent tuning primitive slots on already-reaching templates. */
    public static int tuningExecutions = 0;

    /** Number of tuning passes that found a strictly better working-goal fitness. */
    public static int tuningWins = 0;

    /** Number of tuning passes that covered a working branch-direction goal. */
    public static int tuningCoverage = 0;

    /** Number of same-generation slot-mutated template variants injected. */
    public static int variantInjections = 0;

    private static int nextTemplateId = 1;
    private static int lastProducedTemplateId = 0;
    private static TestCase lastInjectedTemplate = null;
    private static int lastInjectedTemplateIteration = -1;

    /** Cumulative time (ms) spent synthesizing + legitimizing templates (statistics). */
    public static long templateSynthesisTimeMs = 0;

    /**
     * Phase breakdown of {@link #templateSynthesisTimeMs} (statistics). Together these three partition
     * the bulk of the combined timer: {@link #synthesisTimeMs} is building the template (OCG graph
     * construction via the synthesizer plus null-slot mutation and the consistency check),
     * {@link #templateExecTimeMs} is the single template execution that measures its initial legitimacy
     * distance, and {@link #legitimizationTimeMs} is the legitimizer repair GA. The small remainder
     * (caching, the post-legitimization consistency re-check) is only in the combined total.
     */
    public static long synthesisTimeMs = 0;
    public static long templateExecTimeMs = 0;
    public static long legitimizationTimeMs = 0;

    /**
     * Number of synthesized/legitimized templates discarded because they were internally
     * inconsistent (a dangling variable reference that makes the test uncloneable) or threw while
     * being built. Diagnostic only: a healthy synthesizer should keep this at 0, so it doubles as
     * the regression target when fixing the underlying array-element synthesis defect.
     */
    public static int inconsistentTemplatesDiscarded = 0;

    /**
     * The uncovered branch the object-construction template should target for the next generated
     * test. Set by the GAs each generation (MonotonicGA / AbstractMOSA); {@code null} disables
     * template synthesis for this test.
     */
    public static Branch workingBranch4ObjectGraph = null;

    /**
     * Uncovered branch-direction goals for {@link #workingBranch4ObjectGraph}. Cache reuses do not
     * use this handoff; fresh synthesis uses it for immediate-payoff telemetry.
     */
    public static List<BranchCoverageTestFitness> workingGoals4ObjectGraph = new ArrayList<>();

    /** Probability of reusing a cached template instead of re-synthesizing one. */
    private static final double TEMPLATE_REUSE_PROBABILITY = 0.5;

    private static final double VARIANT_INJECTION_PROBABILITY = 0.5;

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

        final TestCase test;
        if (!Properties.APPLY_OBJECT_RULE) {
            test = getDefaultRandomTestCase(size);
        } else if (shouldProduceVariant()) {
            test = produceVariantTestCase();
        } else if (isWholeClassMode()) {
            test = getWholeClassObjectRuleTestCase(size);
        } else {
            test = getObjectRuleTestCase(size);
        }

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
     * EvoObj generation path. Only reached when {@code apply_object_rule=true}. After
     * the first inserted statement the test contains the target-method call (guaranteed by the M1
     * {@code TestCluster.getRandomTestCall} changes); at that point, with probability
     * {@link Properties#P_OBJECT_RULE_APPLICATION}, the test is turned into an object-construction
     * template for {@link #workingBranch4ObjectGraph} and legitimized.
     */
    private TestCase getObjectRuleTestCase(int size) {
        TestCase test = getNewTestCase();
        final TestFactory testFactory = TestFactory.getInstance();

        // Object-rule construction disables primitive reuse so template "slots" get fresh values
        // (the fork's implicit slot mechanism). Saved and restored around this method.
        final double oldPrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;
        Properties.PRIMITIVE_REUSE_PROBABILITY = 0;
        try {
            if (size <= 1) {
                logger.warn("Requested random test case size {} is too small, defaulting to empty test case.", size);
                return test;
            }
            int length = Randomness.nextInt(1, size);

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
                    // Occasionally reuse a proven cached template instead of re-synthesizing; the
                    // main GA mutates the clone as usual.
                    TestCase cached = null;
                    if (TemplateCache.getInstance().hasTemplate(workingBranch4ObjectGraph)
                            && Randomness.nextDouble() < TEMPLATE_REUSE_PROBABILITY) {
                        cached = TemplateCache.getInstance().getClonedTemplate(workingBranch4ObjectGraph);
                    }
                    if (cached != null) {
                        test = cached;
                        templateInjections++;
                        templateCacheHits++;
                        markLastProducedTemplate();
                        rememberLastInjectedTemplate(test);
                    } else {
                        test = synthesizeAndLegitimize(test, allowNullValue, size);
                    }
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

    /** Whole-class object-rule mode: apply the object rule without {@code -Dtarget_method}. */
    private static boolean isWholeClassMode() {
        return Properties.OBJECT_RULE_WHOLE_CLASS
                && (Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty());
    }

    /**
     * EvoObj whole-class generation path. There is no globally fixed target method, so the
     * entry method is derived from the working branch's own containing method: a call to that method
     * is inserted first, then the object-construction template is synthesized against it (with the
     * {@link org.evosuite.testcase.synthesizer.TargetContext} published for the synthesis). Falls back
     * to a plain random test when the object rule does not apply or the branch is not a valid entry.
     */
    private TestCase getWholeClassObjectRuleTestCase(int size) {
        Branch b = workingBranch4ObjectGraph;
        org.evosuite.testcase.synthesizer.TargetContext context =
                (b == null) ? null : org.evosuite.testcase.synthesizer.TargetContext.forBranch(b);
        boolean applyObjectRule =
                context != null && Randomness.nextDouble() <= Properties.P_OBJECT_RULE_APPLICATION;
        if (!applyObjectRule) {
            return getDefaultRandomTestCase(size);
        }

        boolean allowNullValue = Properties.NULL_PROBABILITY > Randomness.nextDouble();
        final double oldPrimitiveReuse = Properties.PRIMITIVE_REUSE_PROBABILITY;
        Properties.PRIMITIVE_REUSE_PROBABILITY = 0;
        org.evosuite.testcase.synthesizer.TargetContext.setActive(context);
        try {
            // Occasionally reuse a proven cached template.
            if (TemplateCache.getInstance().hasTemplate(b)
                    && Randomness.nextDouble() < TEMPLATE_REUSE_PROBABILITY) {
                TestCase cached = TemplateCache.getInstance().getClonedTemplate(b);
                if (cached != null) {
                    templateInjections++;
                    templateCacheHits++;
                    markLastProducedTemplate();
                    rememberLastInjectedTemplate(cached);
                    return cached;
                }
            }

            TestCase test = getNewTestCase();
            if (!insertEntryMethodCall(test, context)) {
                return getDefaultRandomTestCase(size);
            }
            return synthesizeAndLegitimize(test, allowNullValue, size);
        } finally {
            Properties.PRIMITIVE_REUSE_PROBABILITY = oldPrimitiveReuse;
            org.evosuite.testcase.synthesizer.TargetContext.clearActive();
        }
    }

    /**
     * Inserts a call to the whole-class entry method (the working branch's containing method) into
     * {@code test}, constructing its receiver and parameters via the normal factory path. Returns
     * whether the call is present afterward.
     */
    private boolean insertEntryMethodCall(TestCase test, org.evosuite.testcase.synthesizer.TargetContext context) {
        try {
            Class<?> clazz = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT()
                    .loadClass(context.getClassName());
            if (context.isConstructorEntry()) {
                for (java.lang.reflect.Constructor<?> c : clazz.getDeclaredConstructors()) {
                    if (("<init>" + org.evosuite.utils.MethodUtil.getSignature(c)).equals(context.getMethodSignature())) {
                        org.evosuite.utils.generic.GenericConstructor gConstructor =
                                new org.evosuite.utils.generic.GenericConstructor(c, clazz);
                        TestFactory.getInstance().addConstructor(test, gConstructor, test.size(), 0);
                        return findConstructorCallStatement(test, context.getMethodSignature()) != null;
                    }
                }
                return false;
            }
            java.lang.reflect.Method entry = null;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if ((m.getName() + org.evosuite.utils.MethodUtil.getSignature(m)).equals(context.getMethodSignature())) {
                    entry = m;
                    break;
                }
            }
            if (entry == null) {
                return false;
            }
            org.evosuite.utils.generic.GenericMethod gMethod =
                    new org.evosuite.utils.generic.GenericMethod(entry, clazz);
            TestFactory.getInstance().addMethod(test, gMethod, test.size(), 0);
            return test.findMethodCallStatement(context.getMethodSignature()) != null;
        } catch (Throwable t) {
            logger.debug("Whole-class entry-method insertion failed for {}: {}",
                    context.getMethodSignature(), t.toString());
            return false;
        }
    }

    static ConstructorStatement findConstructorCallStatement(TestCase test, String constructorSignature) {
        if (test == null || constructorSignature == null || constructorSignature.isEmpty()) {
            return null;
        }
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) statement;
                if (constructorStatement.getConstructor().getNameWithDescriptor().equals(constructorSignature)) {
                    return constructorStatement;
                }
            }
        }
        return null;
    }

    /**
     * Builds the object-construction template for {@code test} against the current working branch
     * and, if the template throws before the target call, runs the legitimizer to try to reach it.
     *
     * @return the legitimized test if legitimization ran, otherwise the template test
     */
    private TestCase synthesizeAndLegitimize(TestCase test, boolean allowNullValue, int size) {
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
        boolean producedUsableTemplate = false;
        boolean freshSynthesisStarted = false;
        boolean immediateCoveredWorkingGoal = false;
        double finalDistance = Double.MAX_VALUE;
        try {
            freshSyntheses++;
            lastFreshSynthesisIteration = currentIteration;
            freshSynthesisStarted = true;
            ConstructionPathSynthesizer synthesizer = new ConstructionPathSynthesizer();
            synthesizer.buildNodeStatementCorrespondence(test, b, allowNullValue);
            templateInjections++;
            if (!allowNullValue) {
                mutateNullStatements(test);
            }

            // Synthesis is best-effort and can, on pathological classes, leave the test with a
            // dangling variable reference (e.g. an array-index assignment whose backing array
            // statement is missing). Such a test may still execute but throws AssertionError when
            // cloned, which would otherwise crash the GA later in breedNextGeneration. Never emit
            // an inconsistent test: fall back to a plain random test instead.
            if (!isConsistent(test)) {
                synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
                inconsistentTemplatesDiscarded++;
                logger.debug("Discarding inconsistent synthesized test for branch {}", b);
                return getDefaultRandomTestCase(size);
            }

            PartialGraph graph = synthesizer.getPartialGraph();
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap = synthesizer.getGraph2CodeMap();
            synthesisTimeMs += System.currentTimeMillis() - synthesisStart;

            TestChromosome templateChromosome = new TestChromosome();
            templateChromosome.setTestCase(test);
            long execStart = System.currentTimeMillis();
            ExecutionResult result = TestCaseExecutor.getInstance().execute(test);
            templateExecTimeMs += System.currentTimeMillis() - execStart;
            templateChromosome.setLastExecutionResult(result);
            double oldLegitimacyDistance = templateChromosome.getLegitimacyDistance();

            if (oldLegitimacyDistance == 0) {
                firstShotSuccesses++;
                reachedTargetCall = true;
                finalDistance = 0;
                if (!hasWorkingGoals()) {
                    workingGoalUnavailable++;
                } else {
                    double workingGoalFitness = bestWorkingGoalFitness(test, result);
                    immediateCoveredWorkingGoal = workingGoalFitness == 0.0;
                    if (immediateCoveredWorkingGoal) {
                        immediateCoverage++;
                    } else {
                        reachedNotCovered++;
                        TuningResult tuningResult = tuneSlotsForWorkingGoals(test, workingGoalFitness);
                        if (tuningResult != null) {
                            test = tuningResult.test;
                            result = tuningResult.result;
                            templateChromosome.setTestCase(test);
                            templateChromosome.setLastExecutionResult(result);
                        }
                    }
                }
            } else if (!Properties.OBJECT_RULE_LEGITIMIZATION) {
                // Legitimizer disabled: keep the synthesized template as-is even though it throws
                // before the target call. Not counted as a skip (a skip means budget ran out, not
                // that repair was switched off). Isolates pure-synthesis cost/effect.
                finalDistance = oldLegitimacyDistance;
            } else {
                finalDistance = oldLegitimacyDistance;
                // Bound legitimization by both the (scaled) total budget and the remaining search
                // phase time, so a tight search_budget still terminates on time. Only count a trial
                // when the legitimizer actually runs: a template skipped here is a budget casualty,
                // not a legitimizer failure, and counting it would deflate the success rate.
                long now = System.currentTimeMillis();
                boolean withinTotalBudget =
                        now - TestCaseLegitimizer.startTime <= effectiveTotalLegitimizationBudgetMs();
                if (withinTotalBudget && TimeController.getInstance().isThereStillTimeInThisPhase()) {
                    legitimizationTrials++;
                    long legitStart = System.currentTimeMillis();
                    TestChromosome legitimizedChromosome =
                            TestCaseLegitimizer.getInstance().legitimize(templateChromosome, graph, graph2CodeMap);
                    legitimizationTimeMs += System.currentTimeMillis() - legitStart;
                    // Keep the legitimized test (the fork discarded it).
                    test = legitimizedChromosome.getTestCase();
                    finalDistance = legitimizedChromosome.getLegitimacyDistance();
                    if (finalDistance == 0) {
                        legitimizationSuccess++;
                        reachedTargetCall = true;
                    }
                } else {
                    legitimizationSkipped++;
                }
            }

            // Legitimization mutates the test further and can likewise leave it inconsistent;
            // re-check before caching or returning so neither the cache nor the GA ever sees a
            // test that cannot be cloned.
            if (!isConsistent(test)) {
                inconsistentTemplatesDiscarded++;
                logger.debug("Discarding inconsistent legitimized test for branch {}", b);
                return getDefaultRandomTestCase(size);
            }

            producedUsableTemplate = true;
            // Cache the best template seen for this branch for later reuse.
            TemplateCache.getInstance().store(b, test, finalDistance);
            markLastProducedTemplate(immediateCoveredWorkingGoal);
            rememberLastInjectedTemplate(test);
        } catch (Throwable e) {
            // Synthesis and legitimization are strictly best-effort: any failure here (including
            // Errors such as a LinkageError/NoClassDefFoundError from initializing a SUT class, an
            // AssertionError from an invalid intermediate test, or a StackOverflowError from a deep
            // object graph) must degrade to a plain random test rather than abort generation for a
            // class that vanilla EvoSuite handles fine.
            inconsistentTemplatesDiscarded++;
            logger.debug("Object-rule synthesis/legitimization failed for branch " + b + ": " + e, e);
            return getDefaultRandomTestCase(size);
        } finally {
            templateSynthesisTimeMs += System.currentTimeMillis() - synthesisStart;
            // Feed the outcome back to the branch selector's negative cache.
            if (freshSynthesisStarted) {
                ObjectRuleBranchSelector.getInstance().recordSynthesisAttempt(b, producedUsableTemplate);
            }
        }

        return test;
    }

    private static void markLastProducedTemplate() {
        markLastProducedTemplate(false);
    }

    private static void markLastProducedTemplate(boolean immediateCoverage) {
        int id = nextTemplateId++;
        lastProducedTemplateId = id;
        ObjectRuleTelemetry.getInstance().recordInjection(id, currentIteration);
        if (immediateCoverage) {
            ObjectRuleTelemetry.getInstance().recordImmediateCoverage(id);
        }
    }

    static void rememberLastInjectedTemplate(TestCase test) {
        try {
            lastInjectedTemplate = test == null ? null : test.clone();
            lastInjectedTemplateIteration = lastInjectedTemplate == null ? -1 : currentIteration;
        } catch (Throwable t) {
            lastInjectedTemplate = null;
            lastInjectedTemplateIteration = -1;
        }
    }

    public static void setCurrentIteration(int iteration) {
        if (currentIteration != iteration) {
            lastInjectedTemplate = null;
            lastInjectedTemplateIteration = -1;
        }
        currentIteration = iteration;
    }

    /**
     * Effective overall legitimization budget in milliseconds. When the search is time-bounded
     * (MAXTIME), this is the larger of the absolute {@code total_legitimization_budget} and
     * {@code legitimization_budget_fraction} of the search budget, so the legitimizer scales with
     * the search rather than being pinned to a fixed 30s that a 120s search would starve. For
     * non-time-bounded stopping conditions the fraction is meaningless, so the absolute floor is
     * used unchanged.
     */
    private static long effectiveTotalLegitimizationBudgetMs() {
        long absoluteMs = Properties.TOTAL_LEGITIMIZATION_BUDGET * 1000L;
        if (Properties.STOPPING_CONDITION == Properties.StoppingCondition.MAXTIME
                && Properties.SEARCH_BUDGET > 0) {
            long fractionMs =
                    (long) (Properties.LEGITIMIZATION_BUDGET_FRACTION * Properties.SEARCH_BUDGET * 1000L);
            return Math.max(absoluteMs, fractionMs);
        }
        return absoluteMs;
    }

    /**
     * Resets the object-rule statistics counters. Invoked from
     * {@link ObjectConstructionContext#reset()} so runs do not leak counts into one another.
     */
    public static void resetStatistics() {
        legitimizationTrials = 0;
        legitimizationSuccess = 0;
        legitimizationSkipped = 0;
        templateInjections = 0;
        templateSynthesisTimeMs = 0;
        synthesisTimeMs = 0;
        templateExecTimeMs = 0;
        legitimizationTimeMs = 0;
        inconsistentTemplatesDiscarded = 0;
        currentIteration = 0;
        lastFreshSynthesisIteration = 0;
        templateCacheHits = 0;
        freshSyntheses = 0;
        firstShotSuccesses = 0;
        immediateCoverage = 0;
        reachedNotCovered = 0;
        workingGoalUnavailable = 0;
        tuningOpportunities = 0;
        tuningNoSlotSkips = 0;
        tuningExecutions = 0;
        tuningWins = 0;
        tuningCoverage = 0;
        variantInjections = 0;
        nextTemplateId = 1;
        lastProducedTemplateId = 0;
        lastInjectedTemplate = null;
        lastInjectedTemplateIteration = -1;
        workingGoals4ObjectGraph = new ArrayList<>();
    }

    /**
     * Reports the EvoObj object-rule statistics as {@link RuntimeVariable}s. Called once
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
                .trackOutputVariable(RuntimeVariable.ObjectRuleSynthesisTime, synthesisTimeMs);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTemplateExecTime, templateExecTimeMs);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleLegitimizationTime, legitimizationTimeMs);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.TemplateInjections, templateInjections);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LegitimizationSuccessRate, successRate);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LegitimizationAttempts, legitimizationTrials);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LegitimizationSuccesses, legitimizationSuccess);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LegitimizationSkipped, legitimizationSkipped);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleEverEligibleBranches,
                        ObjectRuleBranchSelector.getInstance().getEverEligibleCount());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleBlacklistedBranches,
                        ObjectRuleBranchSelector.getInstance().getBlacklistedCount());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleLastFreshSynthesisIteration,
                        lastFreshSynthesisIteration);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleEntryRejectedConstructor,
                        TargetContext.getRejectedConstructorEntries());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleEntryRejectedNonPublic,
                        TargetContext.getRejectedNonPublicEntries());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleEntryRejectedOther,
                        TargetContext.getRejectedOtherEntries());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTemplateSurvivalMean,
                        ObjectRuleTelemetry.getInstance().meanSurvival());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTemplateSurvival5,
                        ObjectRuleTelemetry.getInstance().survivalAtLeastFive());
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.TemplateCacheHits, templateCacheHits);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleFreshSyntheses, freshSyntheses);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleFirstShotSuccesses, firstShotSuccesses);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleImmediateCoverage, immediateCoverage);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleReachedNotCovered, reachedNotCovered);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleWorkingGoalUnavailable, workingGoalUnavailable);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTuningOpportunities, tuningOpportunities);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTuningNoSlotSkips, tuningNoSlotSkips);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTuningExecutions, tuningExecutions);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTuningWins, tuningWins);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleTuningCoverage, tuningCoverage);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.ObjectRuleVariantInjections, variantInjections);
    }

    private static boolean coversWorkingGoal(TestCase test, ExecutionResult result) {
        return bestWorkingGoalFitness(test, result) == 0.0;
    }

    private static boolean hasWorkingGoals() {
        return workingGoals4ObjectGraph != null && !workingGoals4ObjectGraph.isEmpty();
    }

    private static double bestWorkingGoalFitness(TestCase test, ExecutionResult result) {
        if (workingGoals4ObjectGraph == null || workingGoals4ObjectGraph.isEmpty()
                || test == null || result == null) {
            return Double.MAX_VALUE;
        }
        TestChromosome probe = new TestChromosome();
        try {
            probe.setTestCase(test.clone());
        } catch (Throwable t) {
            probe.setTestCase(test);
        }
        probe.setLastExecutionResult(result);
        double best = Double.MAX_VALUE;
        boolean oldArchive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;
        try {
            for (BranchCoverageTestFitness goal : workingGoals4ObjectGraph) {
                if (goal != null) {
                    try {
                        best = Math.min(best, goal.getFitness(probe, result));
                    } catch (Throwable t) {
                        logger.debug("Object-rule working-goal fitness evaluation failed", t);
                    }
                }
            }
        } finally {
            Properties.TEST_ARCHIVE = oldArchive;
        }
        return best;
    }

    private static class TuningResult {
        private final TestCase test;
        private final ExecutionResult result;

        private TuningResult(TestCase test, ExecutionResult result) {
            this.test = test;
            this.result = result;
        }
    }

    private static TuningResult tuneSlotsForWorkingGoals(TestCase original, double originalFitness) {
        if (!Properties.OBJECT_RULE_SLOT_TUNING || Properties.OBJECT_RULE_SLOT_TUNING_ATTEMPTS <= 0
                || originalFitness == 0.0 || !hasWorkingGoals()) {
            return null;
        }
        tuningOpportunities++;
        if (!hasObjectRulePrimitiveSlot(original)) {
            tuningNoSlotSkips++;
            return null;
        }

        TestCase bestTest = original;
        ExecutionResult bestResult = null;
        double bestFitness = originalFitness;
        boolean improved = false;
        boolean covered = false;

        for (int attempt = 0; attempt < Properties.OBJECT_RULE_SLOT_TUNING_ATTEMPTS; attempt++) {
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                break;
            }
            TestCase candidate;
            try {
                candidate = bestTest.clone();
            } catch (Throwable t) {
                break;
            }
            if (!mutateObjectRuleSlots(candidate)) {
                continue;
            }

            long execStart = System.currentTimeMillis();
            ExecutionResult candidateResult = TestCaseExecutor.getInstance().execute(candidate);
            templateExecTimeMs += System.currentTimeMillis() - execStart;
            tuningExecutions++;

            double candidateFitness = bestWorkingGoalFitness(candidate, candidateResult);
            if (candidateFitness < bestFitness) {
                bestFitness = candidateFitness;
                bestTest = candidate;
                bestResult = candidateResult;
                improved = true;
                if (candidateFitness == 0.0) {
                    covered = true;
                    break;
                }
            }
        }

        if (!improved) {
            return null;
        }
        tuningWins++;
        if (covered) {
            tuningCoverage++;
        }
        return new TuningResult(bestTest, bestResult);
    }

    private static boolean hasObjectRulePrimitiveSlot(TestCase test) {
        if (test == null) {
            return false;
        }
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof PrimitiveStatement
                    && ((PrimitiveStatement<?>) statement).isObjectRuleSlot()) {
                return true;
            }
        }
        return false;
    }

    static boolean mutateObjectRuleSlots(TestCase test) {
        boolean mutated = false;
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof PrimitiveStatement
                    && ((PrimitiveStatement<?>) statement).isObjectRuleSlot()
                    && Randomness.nextDouble() < 0.5) {
                ((PrimitiveStatement<?>) statement).mutate(test, TestFactory.getInstance());
                mutated = true;
            }
        }
        return mutated;
    }

    static boolean shouldProduceVariant() {
        return Properties.OBJECT_RULE_VARIANT_INJECTIONS
                && lastInjectedTemplate != null
                && lastInjectedTemplateIteration == currentIteration
                && Randomness.nextDouble() < VARIANT_INJECTION_PROBABILITY;
    }

    static TestCase produceVariantTestCase() {
        try {
            TestCase variant = lastInjectedTemplate.clone();
            if (!mutateObjectRuleSlots(variant)) {
                return getStaticDefaultRandomTestCase();
            }
            templateInjections++;
            variantInjections++;
            markLastProducedTemplate();
            return variant;
        } catch (Throwable t) {
            logger.debug("Failed to create object-rule variant injection", t);
            return getStaticDefaultRandomTestCase();
        }
    }

    private static TestCase getStaticDefaultRandomTestCase() {
        return new RandomLengthTestFactory().getDefaultRandomTestCase(Properties.CHROMOSOME_LENGTH);
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
        // Snapshot the null statements before mutating any of them. changeNullStatement replaces a
        // null with a freshly constructed object, and that construction can recursively append many
        // new statements -- including new NullStatements for the object's own reference parameters.
        // Iterating the live test.size() would then keep discovering and expanding those newly added
        // nulls, growing the test without bound; on classes with deep object graphs this spins for
        // hundreds of CPU-seconds until the master kills the client (yielding no result at all).
        // Processing only the original nulls keeps this pass linear and bounded.
        List<Statement> originalNullStatements = new ArrayList<>();
        for (int i = 0; i < test.size(); i++) {
            Statement s = test.getStatement(i);
            if (s instanceof NullStatement) {
                originalNullStatements.add(s);
            }
        }
        for (Statement s : originalNullStatements) {
            // Also respect the search-phase budget: this runs inside a single getChromosome call, so
            // without a check here a slow pass would blow past the whole search_budget uninterrupted.
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                break;
            }
            // An earlier change may have removed this statement already; skip if it is gone.
            if (!test.contains(s)) {
                continue;
            }
            TestFactory.getInstance().changeNullStatement(test, s);
        }
    }

    /**
     * Returns whether {@code test} is internally consistent, i.e. every variable reference it uses
     * resolves to a defining statement within the test. Cloning is the exact operation that later
     * fails on a dangling reference (VariableReference.getStPosition throws an AssertionError
     * regardless of whether assertions are enabled), so a clone probe is a faithful, side-effect-free
     * check that a synthesized test can safely enter the population.
     */
    private static boolean isConsistent(TestCase test) {
        try {
            test.clone();
            return true;
        } catch (Throwable t) {
            return false;
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
        if (Properties.APPLY_OBJECT_RULE && lastProducedTemplateId != 0) {
            c.setObjectRuleTemplateId(lastProducedTemplateId);
            lastProducedTemplateId = 0;
        }
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
