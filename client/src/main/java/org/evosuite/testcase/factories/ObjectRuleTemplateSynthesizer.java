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
 */
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.TemplateCache;
import org.evosuite.utils.Randomness;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** Builds, executes, repairs, scores, and caches EvoObj synthesis templates. */
class ObjectRuleTemplateSynthesizer {

    private static final Logger logger = LoggerFactory.getLogger(ObjectRuleTemplateSynthesizer.class);
    private static final double TEMPLATE_REUSE_PROBABILITY = 0.5;
    private static final double NO_PAYOFF_SYNTHESIS_BUDGET_FRACTION = 0.05;
    private static final int NO_PAYOFF_MAX_CONSECUTIVE_ATTEMPTS = 30;
    private static final int NO_PAYOFF_MIN_ATTEMPTS = 1;
    // Log the synthesis-timeout fallback once per class rather than once per attempt: a class
    // whose construction genuinely needs more than the budget would otherwise log on every
    // generation.
    private static final Set<String> TIMEOUT_WARNED_CLASSES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    static void resetSynthesisTimeoutWarnings() {
        TIMEOUT_WARNED_CLASSES.clear();
    }

    private final ObjectRuleGenerationCoordinator owner;

    ObjectRuleTemplateSynthesizer(ObjectRuleGenerationCoordinator owner) {
        this.owner = owner;
    }

    private static ObjectRuleSession session() {
        return org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime().getSession();
    }

    private static ObjectRuleBranchSelector branchSelector() {
        return org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime()
                .getBranchSelector();
    }

    private static TemplateCache templateCache() {
        return org.evosuite.TestGenerationContext.getInstance()
                .getObjectRuleRuntime()
                .getTemplateCache();
    }

    TestCase synthesize(
            TestCase test, boolean allowNullValue, int size,
            ObjectRuleGenerationCoordinator.SpeculativeGenerationState stateBeforeAttempt) {
        ObjectRuleSession session = session();
        Branch b = session.getWorkingBranch();
        if (b == null) {
            Map<Branch, Set<DepVariable>> interestedBranches =
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .getBranchInterestedVarsMap()
                            .get(Properties.TARGET_METHOD);
            if (interestedBranches == null || interestedBranches.isEmpty()) {
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }
            b = Randomness.choice(interestedBranches.keySet());
        }
        if (b == null) {
            return owner.fallbackToDefault(size, stateBeforeAttempt);
        }
        TestCase synthesisTest = Objects.requireNonNull(test, "test");
        Branch synthesisBranch = Objects.requireNonNull(b, "branch");

        long synthesisStart = System.currentTimeMillis();
        boolean reachedTargetCall = false;
        boolean producedUsableTemplate = false;
        boolean freshSynthesisStarted = false;
        boolean immediateCoveredWorkingGoal = false;
        boolean improvedWorkingGoal = false;
        boolean benefitedWorkingGoal = false;

        int originalStatementCount = synthesisTest.size();

        try {
            session.freshSyntheses++;

            freshSynthesisStarted = true;
            ConstructionPathSynthesizer synthesizer = owner.newConstructionPathSynthesizer();
            org.evosuite.testcase.factories.ObjectRuleRuntime runtime =
                    org.evosuite.TestGenerationContext.getInstance().getObjectRuleRuntime();
            boolean previousSynthesisConstruction = runtime.setSynthesisConstruction(true);
            long synthesisTimeoutMillis = Properties.OBJECT_RULE_SYNTHESIS_TIMEOUT;
            // Bounds the graph-construction/statement-correspondence phase, which is where the
            // interprocedural field-setter search (DataDependencyUtil/UsedReferenceSearcher) can
            // recurse deeply into a large dependency; without this a single pathological class can
            // stall the whole GA thread instead of falling back to a plain random test.
            runtime.beginSynthesisAttempt(
                    synthesisTimeoutMillis > 0
                            ? System.nanoTime() + synthesisTimeoutMillis * 1_000_000L
                            : 0L);
            try {
                org.evosuite.testcase.synthesizer.PartialGraph preparedGraph =
                        owner.preparedGraph(b);
                if (preparedGraph == null) {
                    preparedGraph = synthesizer.constructPartialComputationGraph(b);
                }
                preparedGraph = Objects.requireNonNull(preparedGraph, "partial graph");
                synthesizer.buildNodeStatementCorrespondence(
                        synthesisTest, synthesisBranch, allowNullValue, preparedGraph, size);
            } finally {
                if (runtime.synthesisAttemptTimedOut()) {
                    String targetClass = org.evosuite.testcase.synthesizer.TargetContext.activeClassName();
                    if (targetClass != null && !targetClass.isEmpty()
                            && TIMEOUT_WARNED_CLASSES.add(targetClass)) {
                        logger.warn("EvoObj synthesis for {} exceeded the {} ms synthesis budget; "
                                        + "falling back to ordinary generation for this attempt",
                                targetClass, synthesisTimeoutMillis);
                    }
                }
                runtime.endSynthesisAttempt();
                runtime.setSynthesisConstruction(previousSynthesisConstruction);
            }

            // A graph whose required parameter root is still null (or is not the exact target
            // argument) has not been realized. Do not execute or cache it as a usable template.
            if (synthesizer.hasUnresolvedRequiredRoot()) {

                session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }
            /*
             * Reaching an already-present target invocation is not object synthesis. If graph
             * traversal emitted no setup action, returning the unchanged seed merely replaces a
             * normal random individual with an inert template and can falsely claim easy default
             * branch directions as immediate EvoObj coverage.
             */
            if (!hasGraphDerivedActions(originalStatementCount, test)) {

                session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }
            if (!allowNullValue) {
                previousSynthesisConstruction =
                        org.evosuite.TestGenerationContext.getInstance()
                                .getObjectRuleRuntime()
                                .setSynthesisConstruction(true);
                try {
                    mutateNullStatements(test);
                } finally {
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .setSynthesisConstruction(previousSynthesisConstruction);
                }
            }

            // Synthesis is best-effort and can, on pathological classes, leave the test with a
            // dangling variable reference (e.g. an array-index assignment whose backing array
            // statement is missing). Such a test may still execute but throws AssertionError when
            // cloned, which would otherwise crash the GA later in breedNextGeneration. Never emit
            // an inconsistent test: fall back to a plain random test instead.
            if (!owner.isSynthesizedTestConsistent(test)) {

                session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
                session.inconsistentTemplatesDiscarded++;
                logger.debug("Discarding inconsistent synthesized test for branch {}", b);
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }

            // Cached functional-mock recipes are part of the normal first execution, not an
            // additional execution, and apply only to a previously improving shape.
            FunctionalMockSetupCoordinator.applyCachedSetup(test);
            session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;

            long execStart = System.currentTimeMillis();
            ExecutionResult result = TestCaseExecutor.getInstance().execute(test);

            session.templateExecTimeMs += System.currentTimeMillis() - execStart;

            boolean reachedEntry =
                    TemplateExecutionOutcome.from(test, result, Double.MAX_VALUE).reachedEntry();
            boolean acceptedSetupProposal = false;

            // Do not speculate before executing a shape that has no successful recipe. Only an
            // execution which exposes unstubbed mock invocations earns a repaired-clone execution.
            // A normally returning mock may also pin an uncovered goal
            // through a default primitive or empty-container return, so score any observed mock
            // setup before deciding whether one bounded repair execution is warranted.
            double originalGoalFitness = Double.MAX_VALUE;
            boolean observedMockSetup = FunctionalMockSetupCoordinator.hasObservedSetup(test);
            if (reachedEntry && observedMockSetup && hasWorkingGoals()) {
                try {
                    originalGoalFitness = bestWorkingGoalFitness(test, result);
                } catch (Throwable failure) {
                    logger.debug("Unable to score the initial functional-mock branch direction",
                            failure);
                }
            }
            if (!reachedEntry
                    || (observedMockSetup
                            && originalGoalFitness > 0.0
                            && originalGoalFitness < Double.MAX_VALUE)) {
                List<FunctionalMockSetupCoordinator.RepairProposal> setupRepairs =
                        FunctionalMockSetupCoordinator.proposeRepairs(
                                test, result, numericMockSetupHint(b));
                FunctionalMockSetupCoordinator.RepairProposal bestRepair = null;
                TestCase bestRepairTest = null;
                ExecutionResult bestRepairResult = null;

                boolean bestRepairReachedEntry = reachedEntry;
                double bestRepairGoalFitness = originalGoalFitness;
                for (FunctionalMockSetupCoordinator.RepairProposal setupRepair : setupRepairs) {
                    long repairExecStart = System.currentTimeMillis();
                    ExecutionResult repairedResult =
                            TestCaseExecutor.getInstance().execute(setupRepair.getTest());
                    session.templateExecTimeMs += System.currentTimeMillis() - repairExecStart;
                    FunctionalMockSetupCoordinator.recordExtraExecution(setupRepair);

                    boolean repairedReachedEntry =
                            TemplateExecutionOutcome.from(
                                            setupRepair.getTest(), repairedResult, Double.MAX_VALUE)
                                    .reachedEntry();
                    double repairedGoalFitness = Double.MAX_VALUE;
                    if (repairedReachedEntry && hasWorkingGoals()) {
                        try {
                            repairedGoalFitness = bestWorkingGoalFitness(
                                    setupRepair.getTest(), repairedResult);
                        } catch (Throwable failure) {
                            logger.debug("Unable to score setup-repair branch direction", failure);
                        }
                    }
                    if ((repairedReachedEntry && !bestRepairReachedEntry)
                            || (repairedReachedEntry == bestRepairReachedEntry
                                    && repairedGoalFitness < bestRepairGoalFitness)) {
                        bestRepair = setupRepair;
                        bestRepairTest = setupRepair.getTest();
                        bestRepairResult = repairedResult;

                        bestRepairReachedEntry = repairedReachedEntry;
                        bestRepairGoalFitness = repairedGoalFitness;
                    }
                }
                boolean improvesEntryReachability = bestRepairReachedEntry && !reachedEntry;
                boolean improvesWorkingGoal =
                        bestRepairReachedEntry == reachedEntry
                                && bestRepairGoalFitness < originalGoalFitness;
                boolean acceptsBestRepair =
                        bestRepair != null && (improvesEntryReachability || improvesWorkingGoal);
                for (FunctionalMockSetupCoordinator.RepairProposal setupRepair : setupRepairs) {
                    if (acceptsBestRepair && setupRepair == bestRepair) {

                        // Entry reachability or working-goal fitness makes this repaired individual
                        // worth injecting once, but only immediate coverage justifies applying the
                        // same mock stubs to every later synthesis.
                        FunctionalMockSetupCoordinator.recordSuccess(
                                setupRepair, bestRepairGoalFitness == 0.0);
                    } else {
                        FunctionalMockSetupCoordinator.recordFailure(setupRepair);
                    }
                }
                if (acceptsBestRepair) {
                    test = bestRepairTest;
                    result = bestRepairResult;

                    reachedEntry = bestRepairReachedEntry;
                    acceptedSetupProposal = true;
                    improvedWorkingGoal = improvesWorkingGoal;
                    immediateCoveredWorkingGoal = bestRepairGoalFitness == 0.0;
                }
            }

            if (reachedEntry) {
                reachedTargetCall = true;
                // First-shot statistics deliberately exclude the conditional repair execution.
                // A repaired template is still usable and cacheable, but is reported separately.
                if (!acceptedSetupProposal) {
                    FirstShotClassification classification = classifyFirstShot(test, result);
                    immediateCoveredWorkingGoal = classification.immediateCoverage;
                    improvedWorkingGoal = classification.improvedWorkingGoal;
                }
            } else {
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }

            // Count only a fresh template that is actually returned to the GA. Attempts discarded

            // by either consistency gate or by the required-root gate are syntheses, not
            // injections.
            session.templateInjections++;
            // Structural consistency and target-call reachability do not make a template useful
            // for the selected branch. Cache/reset feedback only when setup improved
            // the working goal, or when the template covered it outright. Reached-but-unimproved
            // templates are still returned once to the GA, but repeated inert synthesis is bounded.
            producedUsableTemplate = reachedTargetCall;
            benefitedWorkingGoal = immediateCoveredWorkingGoal || improvedWorkingGoal;

            if (shouldCacheTemplate(
                    reachedTargetCall, immediateCoveredWorkingGoal,
                    improvedWorkingGoal, acceptedSetupProposal)) {

                templateCache().store(b, test);
            }
        } catch (ConstructionFailedException | ClassNotFoundException | RuntimeException | LinkageError
                | AssertionError | StackOverflowError e) {

            // Synthesis is strictly best-effort: any failure here (including
            // Errors such as a LinkageError/NoClassDefFoundError from initializing a SUT class, an
            // AssertionError from an invalid intermediate test, or a StackOverflowError from a deep
            // object graph) must degrade to a plain random test rather than abort generation for a
            // class that vanilla EvoSuite handles fine.
            session.inconsistentTemplatesDiscarded++;

            logger.debug("Object-rule synthesis failed for branch " + b + ": " + e, e);
            return owner.fallbackToDefault(size, stateBeforeAttempt);
        } finally {
            long elapsed = System.currentTimeMillis() - synthesisStart;
            session.templateSynthesisTimeMs += elapsed;
            if (freshSynthesisStarted) {
                if (reachedTargetCall) {
                    session.consecutiveTargetUnreached = 0;
                    session.constructionTimeAtLastTargetReachMs = session.synthesisTimeMs;
                } else {
                    session.consecutiveTargetUnreached++;
                }
                if (benefitedWorkingGoal) {
                    session.consecutiveUnbeneficialSyntheses = 0;
                    session.constructionTimeAtLastBenefitMs = session.synthesisTimeMs;
                } else {
                    session.consecutiveUnbeneficialSyntheses++;
                }
            }

            // Feed the outcome back to the branch selector's negative cache.
            if (freshSynthesisStarted) {

                branchSelector()
                        .recordSynthesisAttempt(
                                b, producedUsableTemplate, immediateCoveredWorkingGoal);
            }
        }

        return test;
    }

    /**
     * Stops class-wide synthesis after a shared non-beneficial setup consumes a bounded share of a
     * time-based search. Per-branch failure bounds remain useful for cheap failures; this circuit
     * handles many distinct branches sharing one expensive construction shape, whether it fails
     * before the entry call or reaches the entry without improving its working goal.
     */
    static boolean shouldStopSynthesisForNoPayoff() {
        ObjectRuleSession session = session();
        return shouldStopSynthesisForNoPayoff(
                session.consecutiveTargetUnreached,
                session.synthesisTimeMs - session.constructionTimeAtLastTargetReachMs,
                session.consecutiveUnbeneficialSyntheses,
                session.synthesisTimeMs - session.constructionTimeAtLastBenefitMs);
    }

    static boolean shouldStopSynthesisForNoPayoff(
            int consecutiveUnreached, long constructionTimeSinceReachMs) {
        return shouldStopSynthesisForNoPayoff(
                consecutiveUnreached, constructionTimeSinceReachMs, 0, 0);
    }

    static boolean shouldStopSynthesisForNoPayoff(
            int consecutiveUnreached, long constructionTimeSinceReachMs,
            int consecutiveUnbeneficial, long constructionTimeSinceBenefitMs) {
        if (consecutiveUnreached >= NO_PAYOFF_MAX_CONSECUTIVE_ATTEMPTS
                || consecutiveUnbeneficial >= NO_PAYOFF_MAX_CONSECUTIVE_ATTEMPTS) {
            return true;
        }
        if (Properties.STOPPING_CONDITION != Properties.StoppingCondition.MAXTIME
                || Properties.SEARCH_BUDGET <= 0) {
            return false;
        }
        long limit = Math.max(1L, (long) (Properties.SEARCH_BUDGET * 1000L
                * NO_PAYOFF_SYNTHESIS_BUDGET_FRACTION));
        boolean unreachableRecipeExceededBudget =
                consecutiveUnreached >= NO_PAYOFF_MIN_ATTEMPTS
                        && constructionTimeSinceReachMs >= limit;
        boolean unbeneficialRecipeExceededBudget =
                consecutiveUnbeneficial >= NO_PAYOFF_MIN_ATTEMPTS
                        && constructionTimeSinceBenefitMs >= limit;
        return unreachableRecipeExceededBudget || unbeneficialRecipeExceededBudget;
    }

    /**
     * Returns a small value on the requested side of a simple integer comparison. This lets a
     * functional mock for calls such as {@code Map.size()} target the selected branch without
     * drawing an unrestricted integer from the global constant pool.
     */
    private Number numericMockSetupHint(Branch branch) {
        if (branch == null || branch.getInstruction() == null) {
            return null;
        }
        AbstractInsnNode branchNode = branch.getInstruction().getASMNode();
        if (branchNode == null) {
            return null;
        }
        int opcode = branchNode.getOpcode();
        Integer constant = isUnaryIntegerComparison(opcode)
                ? Integer.valueOf(0) : precedingIntegerConstant(branchNode);
        if (constant == null) {
            return null;
        }
        Boolean jumpTaken = null;
        for (BranchCoverageTestFitness goal : session().getWorkingGoals()) {
                if (goal != null && branch.equals(goal.getBranch())) {
                    jumpTaken = goal.getValue();
                    break;
                }
        }
        if (jumpTaken == null) {
            return null;
        }
        return integerValueForComparison(opcode, constant, jumpTaken);
    }

    private static boolean isUnaryIntegerComparison(int opcode) {
        return opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE;
    }

    private static Integer precedingIntegerConstant(AbstractInsnNode branchNode) {
        AbstractInsnNode node = branchNode.getPrevious();
        while (node != null && node.getOpcode() < 0) {
            node = node.getPrevious();
        }
        if (node == null) {
            return null;
        }
        int opcode = node.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (node instanceof IntInsnNode
                && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return ((IntInsnNode) node).operand;
        }
        if (node instanceof LdcInsnNode && ((LdcInsnNode) node).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) node).cst;
        }
        return null;
    }

    static Integer integerValueForComparison(int opcode, int constant, boolean jumpTaken) {
        long below = (long) constant - 1L;
        long above = (long) constant + 1L;
        long value;
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IF_ICMPEQ:
                value = jumpTaken ? constant : above;
                break;
            case Opcodes.IFNE:
            case Opcodes.IF_ICMPNE:
                value = jumpTaken ? above : constant;
                break;
            case Opcodes.IFLT:
            case Opcodes.IF_ICMPLT:
                value = jumpTaken ? below : constant;
                break;
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPLE:
                value = jumpTaken ? constant : above;
                break;
            case Opcodes.IFGT:
            case Opcodes.IF_ICMPGT:
                value = jumpTaken ? above : constant;
                break;
            case Opcodes.IFGE:
            case Opcodes.IF_ICMPGE:
                value = jumpTaken ? constant : below;
                break;
            default:
                return null;
        }
        int limit = Math.max(1024,
                Math.min(Properties.COLLECTION_CAPACITY_LIMIT, 1_000_000));
        return (int) Math.max(-limit, Math.min(limit, value));
    }

    /**
     * Abandons all random choices made by a speculative EvoObj attempt and performs precisely the
     * ordinary factory operation from the pre-attempt state.
     */

    static boolean hasGraphDerivedActions(int originalStatementCount, TestCase test) {
        return test != null && test.size() > Math.max(0, originalStatementCount);
    }

    /**
     * Runtime-guided setup gets one chance as a GA individual when it improves fitness. Reusing
     * that repaired template requires immediate coverage; otherwise the general template cache
     * would bypass the branch selector after its fresh-synthesis failure bound was reached.
     */
    static boolean shouldCacheTemplate(boolean reachedTargetCall,
                                       boolean immediateCoveredWorkingGoal,
                                       boolean improvedWorkingGoal,
                                       boolean acceptedSetupProposal) {
        return reachedTargetCall && (immediateCoveredWorkingGoal
                || (improvedWorkingGoal && !acceptedSetupProposal));
    }

    private static boolean hasWorkingGoals() {
        return session().hasWorkingGoals();
    }

    static double bestWorkingGoalFitness(TestCase test, ExecutionResult result) {
        List<BranchCoverageTestFitness> workingGoals = session().getWorkingGoals();
        if (workingGoals.isEmpty() || test == null || result == null) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        Throwable firstFailure = null;
        boolean evaluated = false;
        for (BranchCoverageTestFitness goal : workingGoals) {
            if (goal != null) {
                try {
                    double fitness = goal.getBranchGoal().getDistance(result)
                            .getResultingBranchFitness();
                    best = Math.min(best, fitness);
                    evaluated = true;
                } catch (Throwable failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                    logger.debug("Object-rule working-goal fitness evaluation failed", failure);
                }
            }
        }
        if (!evaluated) {
            throw new IllegalStateException("No object-rule working goal could be evaluated", firstFailure);
        }
        return best;
    }

    static final class FirstShotClassification {
        final boolean immediateCoverage;
        final boolean improvedWorkingGoal;

        private FirstShotClassification(boolean immediateCoverage) {
            this.immediateCoverage = immediateCoverage;
            this.improvedWorkingGoal = immediateCoverage;
        }
    }

    private static FirstShotClassification classifyFirstShot(TestCase test, ExecutionResult result) {
        return classifyFirstShot(
                test,
                result,
                ObjectRuleTemplateSynthesizer::bestWorkingGoalFitness);
    }

    static FirstShotClassification classifyFirstShot(
            TestCase test,
            ExecutionResult result,
            BiFunction<TestCase, ExecutionResult, Double> fitnessEvaluator) {
        ObjectRuleSession session = session();
        session.firstShotSuccesses++;

        if (!hasWorkingGoals()) {
            session.workingGoalUnavailable++;
            return new FirstShotClassification(false);
        }

        final double workingGoalFitness;
        try {
            Double evaluatedFitness = fitnessEvaluator.apply(test, result);
            if (evaluatedFitness == null) {
                throw new IllegalStateException("Working-goal fitness evaluator returned null");
            }
            workingGoalFitness = evaluatedFitness;
        } catch (Throwable failure) {
            session.workingGoalUnavailable++;
            recordWorkingGoalEvaluationFailure("initial scoring", failure);
            return new FirstShotClassification(false);
        }

        if (workingGoalFitness == 0.0) {
            session.immediateCoverage++;
            return new FirstShotClassification(true);
        }

        session.reachedNotCovered++;
        return new FirstShotClassification(false);
    }

    private static synchronized void recordWorkingGoalEvaluationFailure(String phase, Throwable failure) {
        ObjectRuleSession session = session();
        session.workingGoalEvaluationFailures++;
        String message = "Object-rule " + phase + " failed; keeping the usable first-shot template";
        try {
            if (!session.workingGoalFailureLogged) {
                session.workingGoalFailureLogged = true;
                logger.warn(message, failure);
            } else {
                logger.debug(message, failure);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never turn a usable first-shot template into a synthesis failure.
        }
    }

    /** Reuses an exact-branch template with the configured cache probability. */
    static TestCase tryReuseCachedTemplate(Branch branch) {
        TemplateCache cache = templateCache();
        return cache.hasTemplate(branch)
                && Randomness.nextDouble() < TEMPLATE_REUSE_PROBABILITY
                ? cache.getClonedTemplate(branch) : null;
    }

    void mutateNullStatements(TestCase test) {
        // Snapshot the null statements before mutating any of them. changeNullStatement replaces a
        // null with a freshly constructed object, and that construction can recursively append many
        // new statements -- including new NullStatements for the object's own reference parameters.

        // Iterating the live test.size() would then keep discovering and expanding those newly
        // added
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

            // Also respect the search-phase budget: this runs inside a single getChromosome call,
            // so
            // without a check here a slow pass would blow past the whole search_budget
            // uninterrupted.
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
     * regardless of whether assertions are enabled), so a clone probe is a faithful,
     * side-effect-free check that a synthesized test can safely enter the population.
     */
    static boolean isConsistent(TestCase test) {
        try {
            test.clone();
            return true;
        } catch (RuntimeException | AssertionError t) {
            return false;
        }
    }
}
