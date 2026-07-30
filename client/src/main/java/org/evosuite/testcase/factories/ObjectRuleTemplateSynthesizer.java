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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.VariableResolver;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionPathSynthesizer;
import org.evosuite.testcase.synthesizer.FunctionalMockSetupCoordinator;
import org.evosuite.testcase.synthesizer.ObjectConstructionActionTrace;
import org.evosuite.testcase.synthesizer.ObjectConstructionGraphDiagnostics;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.PartialGraph;
import org.evosuite.testcase.synthesizer.TemplateCache;
import org.evosuite.testcase.synthesizer.TestCaseLegitimizer;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.utils.Randomness;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds, executes, repairs, scores, and caches EvoObj synthesis templates. */
class ObjectRuleTemplateSynthesizer {

    private static final Logger logger = LoggerFactory.getLogger(ObjectRuleTemplateSynthesizer.class);
    private static final double TEMPLATE_REUSE_PROBABILITY = 0.5;
    private static final double NO_PAYOFF_SYNTHESIS_BUDGET_FRACTION = 0.05;
    private static final int NO_PAYOFF_MAX_CONSECUTIVE_ATTEMPTS = 30;
    private static final int NO_PAYOFF_MIN_ATTEMPTS = 1;

    private final ObjectRuleGenerationCoordinator owner;

    ObjectRuleTemplateSynthesizer(ObjectRuleGenerationCoordinator owner) {
        this.owner = owner;
    }

    private static ObjectRuleSession session() {
        return org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession();
    }

    TestCase synthesize(
            TestCase test, boolean allowNullValue, int size,
            ObjectRuleGenerationCoordinator.SpeculativeGenerationState stateBeforeAttempt) {
        ObjectRuleSession session = session();
        Branch b = session.getWorkingBranch();
        if (b == null) {
            Map<Branch, Set<DepVariable>> interestedBranches =
                    ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);
            if (interestedBranches == null || interestedBranches.isEmpty()) {
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }
            b = Randomness.choice(interestedBranches.keySet());
        }

        long synthesisStart = System.currentTimeMillis();
        boolean reachedTargetCall = false;
        boolean producedUsableTemplate = false;
        boolean freshSynthesisStarted = false;
        boolean immediateCoveredWorkingGoal = false;
        boolean improvedWorkingGoal = false;
        boolean benefitedWorkingGoal = false;
        double finalDistance = Double.MAX_VALUE;
        boolean graphDiagnostics = Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS;
        if (graphDiagnostics) {
            ObjectConstructionActionTrace.begin();
        }
        int originalStatementCount = test == null ? 0 : test.size();
        Integer diagnosticGeneratedStatementCount = null;
        PartialGraph diagnosticGraph = null;
        Map<DepVariableWrapper, VarRelevance> diagnosticGraph2CodeMap = null;
        ExecutionResult diagnosticResult = null;
        Boolean diagnosticConsistent = null;
        String diagnosticOutcome = "not_started";
        Throwable diagnosticFailure = null;
        Double diagnosticWorkingGoalFitness = null;
        List<Boolean> diagnosticGoalDirections = new ArrayList<>();
        if (graphDiagnostics) {
            for (BranchCoverageTestFitness goal : session.getWorkingGoals()) {
                if (goal != null) {
                    diagnosticGoalDirections.add(goal.getValue());
                }
            }
        }
        try {
            session.freshSyntheses++;
            session.lastFreshSynthesisIteration = session.currentIteration;
            freshSynthesisStarted = true;
            ConstructionPathSynthesizer synthesizer = owner.newConstructionPathSynthesizer();
            boolean previousSynthesisConstruction =
                    org.evosuite.testcase.VariableResolver
                            .setObjectRuleSynthesisConstruction(true);
            try {
                synthesizer.buildNodeStatementCorrespondence(test, b, allowNullValue);
            } finally {
                org.evosuite.testcase.VariableResolver
                        .setObjectRuleSynthesisConstruction(previousSynthesisConstruction);
            }
            diagnosticGeneratedStatementCount =
                    Math.max(0, test.size() - originalStatementCount);
            diagnosticGraph = synthesizer.getPartialGraph();
            diagnosticGraph2CodeMap = synthesizer.getGraph2CodeMap();

			// A graph whose required parameter root is still null (or is not the exact target
			// argument) has not been realized. Do not execute or cache it as a usable template.
			if (synthesizer.hasUnresolvedRequiredRoot()) {
				diagnosticConsistent = owner.isSynthesizedTestConsistent(test);
				diagnosticOutcome = "unresolved_required_graph_root";
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
				diagnosticConsistent = owner.isSynthesizedTestConsistent(test);
				diagnosticOutcome = "no_synthesis_actions";
				session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
				return owner.fallbackToDefault(size, stateBeforeAttempt);
			}
            if (!allowNullValue) {
                previousSynthesisConstruction =
                        org.evosuite.testcase.VariableResolver
                                .setObjectRuleSynthesisConstruction(true);
                try {
                    mutateNullStatements(test);
                } finally {
                    org.evosuite.testcase.VariableResolver
                            .setObjectRuleSynthesisConstruction(previousSynthesisConstruction);
                }
            }

            // Synthesis is best-effort and can, on pathological classes, leave the test with a
            // dangling variable reference (e.g. an array-index assignment whose backing array
            // statement is missing). Such a test may still execute but throws AssertionError when
            // cloned, which would otherwise crash the GA later in breedNextGeneration. Never emit
            // an inconsistent test: fall back to a plain random test instead.
            if (!owner.isSynthesizedTestConsistent(test)) {
                diagnosticConsistent = false;
                diagnosticOutcome = "inconsistent_after_synthesis";
                session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;
                session.inconsistentTemplatesDiscarded++;
                logger.debug("Discarding inconsistent synthesized test for branch {}", b);
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }

            PartialGraph graph = diagnosticGraph;
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap = diagnosticGraph2CodeMap;
            diagnosticConsistent = true;
            // Cached functional-mock recipes are part of the normal first execution, not an
            // additional execution, and apply only to a previously improving shape.
            FunctionalMockSetupCoordinator.applyCachedSetup(test);
            session.synthesisTimeMs += System.currentTimeMillis() - synthesisStart;

            TestChromosome templateChromosome = new TestChromosome();
            templateChromosome.setTestCase(test);
            long execStart = System.currentTimeMillis();
            ExecutionResult result = TestCaseExecutor.getInstance().execute(test);
            diagnosticResult = result;
            session.templateExecTimeMs += System.currentTimeMillis() - execStart;
            templateChromosome.setLastExecutionResult(result);
            double oldLegitimacyDistance = templateChromosome.getLegitimacyDistance();
            boolean reachedBySetupRepair = false;
            boolean acceptedSetupProposal = false;

            // Do not speculate before executing a shape that has no successful recipe. Only an
            // execution which exposes unstubbed mock invocations earns a repaired-clone execution.
            // A normally returning mock may also pin an uncovered goal
            // through a default primitive or empty-container return, so score any observed mock
            // setup before deciding whether one bounded repair execution is warranted.
            double originalGoalFitness = Double.MAX_VALUE;
            boolean observedMockSetup = FunctionalMockSetupCoordinator.hasObservedSetup(test);
            if (oldLegitimacyDistance == 0 && observedMockSetup && hasWorkingGoals()) {
                try {
                    originalGoalFitness = bestWorkingGoalFitness(test, result);
                } catch (Throwable failure) {
                    logger.debug("Unable to score the initial functional-mock branch direction",
                            failure);
                }
            }
            if (oldLegitimacyDistance > 0
                    || (observedMockSetup && originalGoalFitness > 0.0
                    && originalGoalFitness < Double.MAX_VALUE)) {
                List<FunctionalMockSetupCoordinator.RepairProposal> setupRepairs =
                        FunctionalMockSetupCoordinator.proposeRepairs(
                                test, result, numericMockSetupHint(b));
                FunctionalMockSetupCoordinator.RepairProposal bestRepair = null;
                TestCase bestRepairTest = null;
                ExecutionResult bestRepairResult = null;
                TestChromosome bestRepairChromosome = null;
                double bestRepairDistance = oldLegitimacyDistance;
                double bestRepairGoalFitness = originalGoalFitness;
                for (FunctionalMockSetupCoordinator.RepairProposal setupRepair : setupRepairs) {
                    long repairExecStart = System.currentTimeMillis();
                    ExecutionResult repairedResult =
                            TestCaseExecutor.getInstance().execute(setupRepair.getTest());
                    session.templateExecTimeMs += System.currentTimeMillis() - repairExecStart;
                    FunctionalMockSetupCoordinator.recordExtraExecution(setupRepair);

                    TestChromosome repairedChromosome = new TestChromosome();
                    repairedChromosome.setTestCase(setupRepair.getTest());
                    repairedChromosome.setLastExecutionResult(repairedResult);
                    double repairedDistance = repairedChromosome.getLegitimacyDistance();
                    double repairedGoalFitness = Double.MAX_VALUE;
                    if (repairedDistance == 0 && hasWorkingGoals()) {
                        try {
                            repairedGoalFitness = bestWorkingGoalFitness(
                                    setupRepair.getTest(), repairedResult);
                        } catch (Throwable failure) {
                            logger.debug("Unable to score setup-repair branch direction", failure);
                        }
                    }
                    if (repairedDistance < bestRepairDistance
                            || (repairedDistance == bestRepairDistance
                            && repairedGoalFitness < bestRepairGoalFitness)) {
                        bestRepair = setupRepair;
                        bestRepairTest = setupRepair.getTest();
                        bestRepairResult = repairedResult;
                        bestRepairChromosome = repairedChromosome;
                        bestRepairDistance = repairedDistance;
                        bestRepairGoalFitness = repairedGoalFitness;
                    }
                }
                boolean improvesLegitimacy = bestRepairDistance < oldLegitimacyDistance;
                boolean improvesWorkingGoal = bestRepairDistance == oldLegitimacyDistance
                        && bestRepairGoalFitness < originalGoalFitness;
                boolean acceptsBestRepair = bestRepair != null
                        && (improvesLegitimacy || improvesWorkingGoal);
                for (FunctionalMockSetupCoordinator.RepairProposal setupRepair : setupRepairs) {
                    if (acceptsBestRepair && setupRepair == bestRepair) {
                        // A lower legitimacy distance or working-goal fitness makes this repaired
                        // individual worth injecting once, but only immediate coverage justifies
                        // applying the same mock stubs to every later synthesis. In particular,
                        // "throws later" is not a reusable mock recipe.
                        FunctionalMockSetupCoordinator.recordSuccess(
                                setupRepair, bestRepairGoalFitness == 0.0);
                    } else {
                        FunctionalMockSetupCoordinator.recordFailure(setupRepair);
                    }
                }
                if (acceptsBestRepair) {
                    test = bestRepairTest;
                    result = bestRepairResult;
                    diagnosticResult = bestRepairResult;
                    templateChromosome = bestRepairChromosome;
                    oldLegitimacyDistance = bestRepairDistance;
                    reachedBySetupRepair = improvesLegitimacy && bestRepairDistance == 0;
                    acceptedSetupProposal = true;
                    improvedWorkingGoal = improvesWorkingGoal;
                    immediateCoveredWorkingGoal = bestRepairGoalFitness == 0.0;
                }
            }

            if (oldLegitimacyDistance == 0) {
                reachedTargetCall = true;
                finalDistance = 0;
                // First-shot statistics deliberately exclude the conditional repair execution.
                // A repaired template is still usable and cacheable, but is reported separately.
                if (!reachedBySetupRepair && !acceptedSetupProposal) {
                    FirstShotClassification classification = classifyFirstShot(test, result);
                    immediateCoveredWorkingGoal = classification.immediateCoverage;
                    improvedWorkingGoal = classification.improvedWorkingGoal;
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
                    session.legitimizationTrials++;
                    long legitStart = System.currentTimeMillis();
                    TestChromosome legitimizedChromosome =
                            TestCaseLegitimizer.getInstance().legitimize(templateChromosome, graph, graph2CodeMap);
                    session.legitimizationTimeMs += System.currentTimeMillis() - legitStart;
                    // Keep the legitimized test (the fork discarded it).
                    test = legitimizedChromosome.getTestCase();
                    if (legitimizedChromosome.getLastExecutionResult() != null) {
                        diagnosticResult = legitimizedChromosome.getLastExecutionResult();
                    }
                    finalDistance = legitimizedChromosome.getLegitimacyDistance();
                    if (finalDistance == 0) {
                        session.legitimizationSuccess++;
                        reachedTargetCall = true;
                        if (diagnosticResult != null && hasWorkingGoals()) {
                            try {
                                immediateCoveredWorkingGoal =
                                        coversWorkingGoal(test, diagnosticResult);
                            } catch (Throwable failure) {
                                recordWorkingGoalEvaluationFailure(
                                        "post-legitimization scoring", failure);
                            }
                        }
                    }
                } else {
                    session.legitimizationSkipped++;
                }
            }

            // Legitimization mutates the test further and can likewise leave it inconsistent;
            // re-check before caching or returning so neither the cache nor the GA ever sees a
            // test that cannot be cloned.
            if (!owner.isSynthesizedTestConsistent(test)) {
                diagnosticConsistent = false;
                diagnosticOutcome = "inconsistent_after_legitimization";
                session.inconsistentTemplatesDiscarded++;
                logger.debug("Discarding inconsistent legitimized test for branch {}", b);
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }

            /*
             * With legitimization disabled, an execution that threw before the selected entry is
             * evidence that this is not yet a usable seed. Injecting it used to replace an ordinary
             * random individual with a template known not to execute its intended operation. On
             * large parser classes those dead templates consumed nearly the complete search
             * budget. Preserve the analysis feedback, but restore the speculative RNG/state and
             * emit the baseline individual.
             */
            if (!reachedTargetCall) {
                diagnosticOutcome = "target_not_reached_discarded";
                return owner.fallbackToDefault(size, stateBeforeAttempt);
            }

            // Count only a fresh template that is actually returned to the GA. Attempts discarded
            // by either consistency gate or by the required-root gate are syntheses, not injections.
            session.templateInjections++;
            // Structural consistency and target-call reachability do not make a template useful
            // for the selected branch. Cache/reset feedback only when setup improved
            // the working goal, or when the template covered it outright. Reached-but-unimproved
            // templates are still returned once to the GA, but repeated inert synthesis is bounded.
            producedUsableTemplate = reachedTargetCall;
            benefitedWorkingGoal = immediateCoveredWorkingGoal || improvedWorkingGoal;
            diagnosticOutcome = immediateCoveredWorkingGoal
                    ? "usable_immediate_coverage"
                    : (reachedTargetCall ? "usable_target_reached" : "usable_target_not_reached");
            if (shouldCacheTemplate(
                    reachedTargetCall, immediateCoveredWorkingGoal,
                    improvedWorkingGoal, acceptedSetupProposal)) {
                TemplateCache.getInstance().store(b, test, finalDistance);
            }
            markLastProducedTemplate(immediateCoveredWorkingGoal);
        } catch (ConstructionFailedException | ClassNotFoundException | RuntimeException | LinkageError
                | AssertionError | StackOverflowError e) {
            // Synthesis and legitimization are strictly best-effort: any failure here (including
            // Errors such as a LinkageError/NoClassDefFoundError from initializing a SUT class, an
            // AssertionError from an invalid intermediate test, or a StackOverflowError from a deep
            // object graph) must degrade to a plain random test rather than abort generation for a
            // class that vanilla EvoSuite handles fine.
            session.inconsistentTemplatesDiscarded++;
            diagnosticFailure = e;
            diagnosticOutcome = "synthesis_failure";
            logger.debug("Object-rule synthesis/legitimization failed for branch " + b + ": " + e, e);
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
            if (graphDiagnostics) {
                if (diagnosticResult != null && hasWorkingGoals()) {
                    try {
                        diagnosticWorkingGoalFitness = bestWorkingGoalFitness(test, diagnosticResult);
                    } catch (Throwable failure) {
                        if (diagnosticFailure == null) {
                            diagnosticFailure = failure;
                        }
                    }
                }
                ObjectConstructionGraphDiagnostics.recordSynthesisAttempt(
                        b, diagnosticGraph, diagnosticGraph2CodeMap, test, diagnosticResult,
                        originalStatementCount, diagnosticGeneratedStatementCount,
                        diagnosticConsistent, reachedTargetCall,
                        diagnosticWorkingGoalFitness, diagnosticGoalDirections,
                        diagnosticOutcome, diagnosticFailure);
            }
            ObjectConstructionActionTrace.clear();
            // Feed the outcome back to the branch selector's negative cache.
            if (freshSynthesisStarted) {
                ObjectRuleBranchSelector.getInstance().recordSynthesisAttempt(
                        b, producedUsableTemplate,
                        immediateCoveredWorkingGoal);
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

    private static void markLastProducedTemplate() {
        markLastProducedTemplate(false);
    }

    private static void markLastProducedTemplate(boolean immediateCoverage) {
        session().markProducedTemplate(immediateCoverage);
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

    private static boolean coversWorkingGoal(TestCase test, ExecutionResult result) {
        return bestWorkingGoalFitness(test, result) == 0.0;
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
        TemplateCache cache = TemplateCache.getInstance();
        return cache.hasTemplate(branch)
                && Randomness.nextDouble() < TEMPLATE_REUSE_PROBABILITY
                ? cache.getClonedTemplate(branch) : null;
    }


    void mutateNullStatements(TestCase test) {
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
    static boolean isConsistent(TestCase test) {
        try {
            test.clone();
            return true;
        } catch (RuntimeException | AssertionError t) {
            return false;
        }
    }
}
