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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts ranked diagnostic cards from uncovered goals and population evidence.
 *
 * <p>This implementation intentionally starts with a conservative subset of
 * card types (unreached methods and exception barriers). Additional signals
 * such as branch polarity and CDG bottlenecks can be added incrementally.
 */
public class ProblemCardExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ProblemCardExtractor.class);

    private static final int MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER = 2;
    private static final int MIN_ATTEMPTS_FOR_TYPE_BARRIER = 2;
    private static final int MIN_POLARITY_GAP_OPPOSITE_OBSERVATIONS = 2;
    private static final int MIN_LOW_STEERABILITY_BRANCH_EXECUTIONS = 4;
    private static final int MIN_ATTEMPTS_FOR_STATE_DIVERSIFICATION = 4;
    private static final double MIN_FAILURE_RATE_FOR_PRACTICAL_ACQUISITION_BARRIER = 0.6;
    private static final double MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER = 2.0 / 3.0;
    private static final double MIN_FAILURE_RATE_FOR_STATE_SETUP_BARRIER = 2.0 / 3.0;
    private static final double MIN_CONTEXT_SHARE_FOR_STATE_DIVERSIFICATION = 0.75;

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();

    /**
     * Builds ranked problem cards.
     *
     * @param uncoveredGoals remaining goals
     * @param population current population snapshot
     * @param maxCards maximum number of cards to return
     * @return ranked cards (highest priority first)
     */
    public List<ProblemCard> extract(Collection<TestFitnessFunction> uncoveredGoals,
                                     List<TestChromosome> population,
                                     int maxCards) {
        return extractWithTelemetry(uncoveredGoals, population, maxCards, Collections.emptyMap()).getCards();
    }

    /**
     * Builds ranked problem cards with optional cross-snapshot exception stats.
     *
     * @param uncoveredGoals remaining goals
     * @param population current population snapshot
     * @param maxCards maximum number of cards to return
     * @param persistentExceptionStats optional method-level historical exception stats
     * @return ranked cards (highest priority first)
     */
    public List<ProblemCard> extract(Collection<TestFitnessFunction> uncoveredGoals,
                                     List<TestChromosome> population,
                                     int maxCards,
                                     Map<String, ExceptionBarrierTracker.AggregatedStats> persistentExceptionStats) {
        return extractWithTelemetry(uncoveredGoals, population, maxCards, persistentExceptionStats).getCards();
    }

    public ExtractionResult extractWithTelemetry(Collection<TestFitnessFunction> uncoveredGoals,
                                                 List<TestChromosome> population,
                                                 int maxCards) {
        return extractWithTelemetry(uncoveredGoals, population, maxCards, Collections.emptyMap());
    }

    public ExtractionResult extractWithTelemetry(
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> population,
            int maxCards,
            Map<String, ExceptionBarrierTracker.AggregatedStats> persistentExceptionStats) {
        if (uncoveredGoals == null || uncoveredGoals.isEmpty() || maxCards <= 0) {
            return ExtractionResult.empty();
        }

        List<TestChromosome> snapshot = population == null ? Collections.emptyList() : population;
        Map<String, List<TestFitnessFunction>> goalsByMethod = groupGoalsByMethod(uncoveredGoals);
        if (goalsByMethod.isEmpty()) {
            return ExtractionResult.empty();
        }
        ExtractorTelemetry telemetry = new ExtractorTelemetry();
        boolean traceEnabled = shouldTraceExtractorForCurrentTarget();

        Set<String> coveredMethods = collectCoveredMethods(snapshot);
        Map<String, AttemptStats> methodStats = collectMethodAttemptStats(
                snapshot, goalsByMethod.keySet(), telemetry);
        Map<String, BranchGoalSignal> branchSignals = collectBranchSignals(uncoveredGoals, snapshot);
        Map<String, CdgDependencySignal> cdgSignals = collectCdgDependencySignals(uncoveredGoals, snapshot);
        Map<String, TypeBarrierSignal> typeBarrierSignals = collectTypeBarrierSignals(
                goalsByMethod, snapshot, telemetry);
        Map<String, OuterTypeAttemptSignal> outerTypeSignals = collectOuterTypeAttemptSignals(snapshot);

        List<ProblemCard> cards = new ArrayList<>();
        addIndirectReachabilityCards(cards, goalsByMethod, coveredMethods, methodStats,
                typeBarrierSignals, outerTypeSignals);
        addUnreachedMethodCards(cards, goalsByMethod, coveredMethods, snapshot.size(), typeBarrierSignals);
        addTypeBarrierCards(cards, typeBarrierSignals, telemetry);
        addExceptionBarrierCards(cards, goalsByMethod, methodStats, persistentExceptionStats, telemetry);
        addStateDiversificationGapCards(cards, goalsByMethod, methodStats);
        addBranchPolarityGapCards(cards, branchSignals);
        addCdgBottleneckCards(cards, cdgSignals);

        cards.sort(Comparator.comparingDouble(ProblemCard::getPriority).reversed());
        traceExtractionSummary(traceEnabled, goalsByMethod, coveredMethods, methodStats,
                typeBarrierSignals, outerTypeSignals, cards, telemetry);
        if (cards.size() <= maxCards) {
            return new ExtractionResult(cards, telemetry.snapshotRejectCounts(), telemetry.snapshotCandidateCounts());
        }
        return new ExtractionResult(new ArrayList<>(cards.subList(0, maxCards)),
                telemetry.snapshotRejectCounts(), telemetry.snapshotCandidateCounts());
    }

    private Map<String, List<TestFitnessFunction>> groupGoalsByMethod(
            Collection<TestFitnessFunction> uncoveredGoals) {
        Map<String, List<TestFitnessFunction>> goalsByMethod = new LinkedHashMap<>();
        for (TestFitnessFunction goal : uncoveredGoals) {
            String key = methodKey(goal);
            if (key == null) {
                continue;
            }
            goalsByMethod.computeIfAbsent(key, ignored -> new ArrayList<>()).add(goal);
        }
        return goalsByMethod;
    }

    private Set<String> collectCoveredMethods(List<TestChromosome> snapshot) {
        java.util.LinkedHashSet<String> coveredMethods = new java.util.LinkedHashSet<>();
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null || result.getTrace() == null) {
                continue;
            }
            Set<String> methods = result.getTrace().getCoveredMethods();
            if (methods != null) {
                for (String method : methods) {
                    String key = coveredMethodKey(method);
                    if (!key.isEmpty()) {
                        coveredMethods.add(key);
                    }
                }
            }
        }
        return coveredMethods;
    }

    private Map<String, AttemptStats> collectMethodAttemptStats(
            List<TestChromosome> snapshot, Set<String> goalMethods, ExtractorTelemetry telemetry) {
        Map<String, AttemptStats> statsByMethod = new HashMap<>();
        for (String key : goalMethods) {
            statsByMethod.put(key, new AttemptStats());
        }
        boolean traceEnabled = shouldTraceExtractorForCurrentTarget();
        int chromosomeIndex = 0;
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                chromosomeIndex++;
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                chromosomeIndex++;
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            if (result.hasTestException() && trace != null && trace.getCoveredMethods() != null) {
                for (String coveredMethod : trace.getCoveredMethods()) {
                    AttemptStats stats = statsByMethod.get(coveredMethodKey(coveredMethod));
                    if (stats != null) {
                        stats.coveredInFailingTests++;
                    }
                }
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                chromosomeIndex++;
                continue;
            }
            int executedStatements = result.getExecutedStatements();
            int safeSize = safeTestSize(test);
            if (safeSize <= 0) {
                chromosomeIndex++;
                continue;
            }
            int thrownPos = firstThrownPosition(result);
            if (result.hasTestException() && thrownPos >= 0) {
                // ExecutionResult stores the index of the last fully completed statement.
                // Include the throwing statement itself so exception barriers are counted.
                executedStatements = Math.max(executedStatements, thrownPos + 1);
            }
            if (executedStatements <= 0 || executedStatements > safeSize) {
                executedStatements = safeSize;
            }
            String exceptionKey = result.hasTestException() ? exceptionKey(result) : "";
            traceExtractor(traceEnabled, "method_stats",
                    "test={} executed_statements={} safe_size={} thrown_pos={} has_exception={} timeout={} exception={}",
                    chromosomeIndex, executedStatements, safeSize, thrownPos, result.hasTestException(),
                    result.hasTimeout(), exceptionKey);
            for (int i = 0; i < executedStatements; i++) {
                Statement statement = statementAt(test, i);
                if (!(statement instanceof MethodStatement)) {
                    traceExtractor(traceEnabled, "method_stats",
                            "test={} stmt={} action=skip_non_method kind={}",
                            chromosomeIndex, i, statementDebugKind(statement));
                    continue;
                }
                MethodStatement methodStatement = (MethodStatement) statement;
                GoalDescriptionMapper.OperationTarget invocation =
                        goalDescriptionMapper.describeMethodOperation(methodStatement);
                AttemptStats stats = statsByMethod.get(invocation.getExecutionKey());
                boolean exceptionOutcome = result.hasTestException() && thrownPos == i;
                if (stats != null) {
                    InvocationContextClassifier.InvocationContext context =
                            InvocationContextClassifier.classify(methodStatement);
                    traceExtractor(traceEnabled, "method_stats",
                            "test={} stmt={} action=direct execution_key={} label={} exception_outcome={} "
                                    + "context={} timeout={}",
                            chromosomeIndex, i, invocation.getExecutionKey(), invocation.getDisplayLabel(),
                            exceptionOutcome, context.getKey(), result.hasTimeout());
                    ContextAttemptStats contextStats = stats.contextStats(context);
                    stats.attempts++;
                    contextStats.attempts++;
                    if (exceptionOutcome) {
                        stats.exceptions++;
                        contextStats.exceptions++;
                        stats.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        contextStats.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        if (!exceptionKey.isEmpty()) {
                            stats.exceptionTypeCounts.put(exceptionKey,
                                    stats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
                            contextStats.exceptionTypeCounts.put(exceptionKey,
                                    contextStats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
                        }
                    } else if (!result.hasTimeout()) {
                        stats.successes++;
                        contextStats.successes++;
                    }
                    continue;
                }
                if (exceptionOutcome) {
                    telemetry.recordUpstreamExceptionObservation(invocation.getExecutionKey());
                }
                Set<String> downstreamGoalMethods = downstreamGoalMethodsAfter(test, i, goalMethods);
                traceExtractor(traceEnabled, "method_stats",
                        "test={} stmt={} action=upstream execution_key={} label={} exception_outcome={} "
                                + "downstream_goals={} timeout={}",
                        chromosomeIndex, i, invocation.getExecutionKey(), invocation.getDisplayLabel(),
                        exceptionOutcome, downstreamGoalMethods, result.hasTimeout());
                if (downstreamGoalMethods.isEmpty()) {
                    if (exceptionOutcome) {
                        telemetry.increment(ExtractorRejectReason.UPSTREAM_EXCEPTION_WITHOUT_BLOCKED_GOAL);
                    }
                    continue;
                }
                if (exceptionOutcome) {
                    telemetry.recordUpstreamBlockedGoalMethods(downstreamGoalMethods);
                }
                for (String blockedGoalMethod : downstreamGoalMethods) {
                    recordUpstreamAttempt(statsByMethod, blockedGoalMethod, invocation, exceptionOutcome,
                            exceptionKey, i > 0, result.hasTimeout());
                }
            }
            chromosomeIndex++;
        }
        return statsByMethod;
    }

    private void recordUpstreamAttempt(Map<String, AttemptStats> statsByMethod,
                                       String blockedGoalMethod,
                                       GoalDescriptionMapper.OperationTarget invocation,
                                       boolean exceptionOutcome,
                                       String exceptionKey,
                                       boolean hadSuccessfulPrefix,
                                       boolean timeout) {
        if (statsByMethod == null || blockedGoalMethod == null || blockedGoalMethod.isEmpty()
                || invocation == null || invocation.getExecutionKey().isEmpty()) {
            return;
        }
        AttemptStats blockedStats = statsByMethod.get(blockedGoalMethod);
        if (blockedStats == null) {
            return;
        }
        UpstreamAttemptStats upstreamStats = blockedStats.upstreamStats(
                invocation.getExecutionKey(), invocation.getDisplayLabel());
        upstreamStats.attempts++;
        if (exceptionOutcome) {
            upstreamStats.exceptions++;
            upstreamStats.recordFailingInvocation(invocation.getDisplayLabel(), hadSuccessfulPrefix);
            if (!exceptionKey.isEmpty()) {
                upstreamStats.exceptionTypeCounts.put(exceptionKey,
                        upstreamStats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
            }
        } else if (!timeout) {
            upstreamStats.successes++;
        }
    }

    private Set<String> downstreamGoalMethodsAfter(TestCase test, int statementIndex, Set<String> goalMethods) {
        if (test == null || goalMethods == null || goalMethods.isEmpty()) {
            return Collections.emptySet();
        }
        int safeSize = safeTestSize(test);
        if (statementIndex < 0 || statementIndex >= safeSize - 1) {
            return Collections.emptySet();
        }
        Set<String> downstream = new LinkedHashSet<>();
        for (int i = statementIndex + 1; i < safeSize; i++) {
            Statement statement = statementAt(test, i);
            if (!(statement instanceof MethodStatement)) {
                continue;
            }
            String executionKey = goalDescriptionMapper.describeMethodOperation((MethodStatement) statement)
                    .getExecutionKey();
            if (goalMethods.contains(executionKey)) {
                downstream.add(executionKey);
            }
        }
        return downstream;
    }

    private Map<String, OuterTypeAttemptSignal> collectOuterTypeAttemptSignals(List<TestChromosome> snapshot) {
        Map<String, OuterTypeAttemptSignal> signals = new LinkedHashMap<>();
        if (snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                continue;
            }
            int executedStatements = result.getExecutedStatements();
            int safeSize = safeTestSize(test);
            if (safeSize <= 0) {
                continue;
            }
            int thrownPos = firstThrownPosition(result);
            if (result.hasTestException() && thrownPos >= 0) {
                executedStatements = Math.max(executedStatements, thrownPos + 1);
            }
            if (executedStatements <= 0 || executedStatements > safeSize) {
                executedStatements = safeSize;
            }
            for (int i = 0; i < executedStatements; i++) {
                Statement statement = statementAt(test, i);
                if (!(statement instanceof MethodStatement)) {
                    continue;
                }
                MethodStatement methodStatement = (MethodStatement) statement;
                String outerType = outerTypeName(declaringTypeForMethod(methodStatement));
                if (outerType.isEmpty()) {
                    continue;
                }
                OuterTypeAttemptSignal signal = signals.computeIfAbsent(outerType, OuterTypeAttemptSignal::new);
                String label = goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel();
                signal.attempts++;
                if (result.hasTestException() && thrownPos == i) {
                    signal.exceptions++;
                    String key = exceptionKey(result);
                    if (!key.isEmpty()) {
                        signal.exceptionTypeCounts.put(key,
                                signal.exceptionTypeCounts.getOrDefault(key, 0) + 1);
                    }
                    incrementLabel(signal.failingEntryPointLabelCounts, label);
                } else if (!result.hasTimeout()) {
                    signal.successes++;
                    incrementLabel(signal.successfulEntryPointLabelCounts, label);
                }
            }
        }
        return signals;
    }

    private void addUnreachedMethodCards(List<ProblemCard> cards,
                                         Map<String, List<TestFitnessFunction>> goalsByMethod,
                                         Set<String> coveredMethods,
                                         int populationSize,
                                         Map<String, TypeBarrierSignal> typeBarrierSignals) {
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            if (coveredMethods.contains(methodKey)) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = entry.getValue();
            MethodPromptContext methodContext = describeMethodContext(methodKey, relatedGoals);
            TypeBarrierSignal typeSignal = typeBarrierSignals.get(methodContext.typeName);
            if (typeSignal == null || !typeSignal.hasReusableSuccessfulPrefix(methodKey)) {
                continue;
            }
            double impact = leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            double confidence = populationSize >= 3 ? 0.9 : 0.7;
            List<String> evidence = new ArrayList<>();
            evidence.add("Goal method had zero observed executions in the current population.");
            evidence.add("Observed executions: 0/" + Math.max(1, populationSize) + " tests.");
            evidence.add("Type reachability: " + methodContext.typeName
                    + " was reached successfully, but " + methodContext.displayLabel
                    + " was never observed.");
            String reusablePrefix = typeSignal.describeReusableSuccessfulPrefix(3, methodKey);
            if (!reusablePrefix.isEmpty()) {
                evidence.add("Reusable successful prefix on this type: " + reusablePrefix + ".");
            }
            evidence.add("Related uncovered goals: " + relatedGoals.size() + ".");
            addOverloadEvidence(evidence, methodContext);
            cards.add(new ProblemCard(
                    ProblemCardType.UNREACHED_METHOD,
                    "Method not reached: " + methodContext.displayLabel,
                    evidence,
                    relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.EXECUTION,
                    rootCauseKeyForType(methodContext.typeName, methodKey),
                    methodKey,
                    "unreached:" + methodKey
                            + "|population=" + populationSize
                            + "|goals=" + relatedGoals.size()
                            + "|typeReachable=true"
                            + "|acquired=" + typeSignal.hasSuccessfulAcquisition()
                            + "|prefix=" + typeSignal.prefixFingerprint(methodKey)));
        }
    }

    private void addIndirectReachabilityCards(List<ProblemCard> cards,
                                              Map<String, List<TestFitnessFunction>> goalsByMethod,
                                              Set<String> coveredMethods,
                                              Map<String, AttemptStats> methodStats,
                                              Map<String, TypeBarrierSignal> typeBarrierSignals,
                                              Map<String, OuterTypeAttemptSignal> outerTypeSignals) {
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return;
        }
        addInvocationGapIndirectReachabilityCards(cards, goalsByMethod, methodStats, typeBarrierSignals);
        Map<String, List<TestFitnessFunction>> goalsBySyntheticType = new LinkedHashMap<>();
        Map<String, List<String>> methodKeysBySyntheticType = new LinkedHashMap<>();
        Map<String, MethodPromptContext> contextBySyntheticType = new LinkedHashMap<>();

        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            if (coveredMethods.contains(methodKey)) {
                continue;
            }
            MethodPromptContext context = describeMethodContext(methodKey, entry.getValue());
            if (!isLikelyIndirectHelperType(context.typeName)) {
                continue;
            }
            TypeBarrierSignal typeSignal = typeBarrierSignals.get(context.typeName);
            if (typeSignal != null && typeSignal.hasReusableSuccessfulPrefix(methodKey)) {
                continue;
            }
            goalsBySyntheticType.computeIfAbsent(context.typeName, ignored -> new ArrayList<>())
                    .addAll(entry.getValue());
            methodKeysBySyntheticType.computeIfAbsent(context.typeName, ignored -> new ArrayList<>())
                    .add(methodKey);
            contextBySyntheticType.putIfAbsent(context.typeName, context);
        }

        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsBySyntheticType.entrySet()) {
            String syntheticType = entry.getKey();
            List<TestFitnessFunction> relatedGoals = deduplicateGoals(entry.getValue());
            MethodPromptContext context = contextBySyntheticType.get(syntheticType);
            if (context == null || relatedGoals.isEmpty()) {
                continue;
            }
            String outerType = outerTypeName(syntheticType);
            OuterTypeAttemptSignal outerSignal = outerTypeSignals.get(outerType);

            List<String> evidence = new ArrayList<>();
            evidence.add("Unreached goals are concentrated in synthetic/local helper type: "
                    + syntheticType + ".");
            evidence.add("These helper methods are usually reached indirectly through outer entrypoint workflows.");
            List<String> methodKeys = methodKeysBySyntheticType.getOrDefault(syntheticType, Collections.emptyList());
            evidence.add("Blocked helper methods: " + methodKeys.size() + ".");
            if (!methodKeys.isEmpty()) {
                evidence.add("Example blocked helper: " + methodKeys.get(0) + ".");
            }
            evidence.add("Inferred outer entrypoint type: " + outerType + ".");
            if (outerSignal == null || outerSignal.attempts <= 0) {
                evidence.add("No observed outer entrypoint invocations for " + outerType
                        + " in current executions.");
            } else {
                evidence.add("Observed outer entrypoint invocations: " + outerSignal.attempts
                        + " (successes=" + outerSignal.successes + ", exceptions=" + outerSignal.exceptions + ").");
                String successfulEntry = dominantLabel(outerSignal.successfulEntryPointLabelCounts);
                if (!successfulEntry.isEmpty()) {
                    evidence.add("Most frequent successful outer entrypoint: " + successfulEntry + ".");
                }
                String failingEntry = dominantLabel(outerSignal.failingEntryPointLabelCounts);
                if (!failingEntry.isEmpty()) {
                    evidence.add("Most frequent failing outer entrypoint: " + failingEntry + ".");
                }
                String dominantException = dominantException(outerSignal.exceptionTypeCounts);
                if (!dominantException.isEmpty()) {
                    evidence.add("Dominant outer entrypoint exception: " + dominantException + ".");
                }
            }
            addOverloadEvidence(evidence, context);

            double impact = leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            int attempts = outerSignal == null ? 0 : outerSignal.attempts;
            double confidence = attempts > 0 ? barrierConfidence(attempts) : 0.6;
            cards.add(new ProblemCard(
                    ProblemCardType.INDIRECT_REACHABILITY_BARRIER,
                    "Indirect reachability barrier for helper type: " + syntheticType,
                    evidence,
                    relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.STRUCTURAL,
                    syntheticType,
                    "indirect:" + syntheticType,
                    "indirect:" + syntheticType
                            + "|outer=" + outerType
                            + "|methods=" + methodKeys.size()
                            + "|outerAttempts=" + attempts
                            + "|outerExceptions=" + (outerSignal == null ? 0 : outerSignal.exceptions)));
        }
    }

    private void addInvocationGapIndirectReachabilityCards(List<ProblemCard> cards,
                                                           Map<String, List<TestFitnessFunction>> goalsByMethod,
                                                           Map<String, AttemptStats> methodStats,
                                                           Map<String, TypeBarrierSignal> typeBarrierSignals) {
        Map<String, TypeInvocationSignal> invocationSignals =
                collectTypeInvocationSignals(goalsByMethod, methodStats);
        for (TypeInvocationSignal signal : invocationSignals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty() || signal.typeName.isEmpty()) {
                continue;
            }
            if (isLikelyIndirectHelperType(signal.typeName) || signal.directAttempts > 0) {
                continue;
            }
            TypeBarrierSignal typeSignal = typeBarrierSignals.get(signal.typeName);
            if (typeSignal == null
                    || (!typeSignal.hasSuccessfulAcquisition() && typeSignal.successfulMethodLabelCounts.isEmpty())) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Observed setup/acquisition activity for " + signal.typeName
                    + ", but no direct goal-bearing method on this type executed.");
            evidence.add("Observed direct goal-method executions: 0 across "
                    + signal.methodKeys.size() + " blocked methods.");
            evidence.add("Blocked goal methods on this type: " + signal.describeBlockedMethodLabels(3) + ".");
            evidence.add("Successful acquisitions observed for this type: "
                    + typeSignal.totalConstructionSuccesses() + ".");
            if (typeSignal.totalConstructionSuccessesWithProgress() > 0) {
                evidence.add("Acquisitions that progressed into same-type or goal-bearing execution: "
                        + typeSignal.totalConstructionSuccessesWithProgress() + ".");
            }
            String successfulSteps = typeSignal.describeSuccessfulMethodLabels(3, null);
            if (!successfulSteps.isEmpty()) {
                evidence.add("Observed successful setup/lifecycle steps: " + successfulSteps + ".");
            }
            String reusablePrefix = typeSignal.describeReusableSuccessfulPrefix(3, null);
            if (!reusablePrefix.isEmpty()) {
                evidence.add("Reusable setup prefix that still stopped before the direct target call: "
                        + reusablePrefix + ".");
            }
            evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");

            double impact = leverageAwareImpact(signal.relatedGoals, 5.0);
            double blockage = 1.0;
            int reachabilityEvidence = typeSignal.totalConstructionSuccesses()
                    + typeSignal.successfulMethodLabelCounts.size();
            double confidence = barrierConfidence(Math.max(1, reachabilityEvidence));
            cards.add(new ProblemCard(
                    ProblemCardType.INDIRECT_REACHABILITY_BARRIER,
                    "Indirect reachability barrier before direct target invocation: " + signal.typeName,
                    evidence,
                    signal.relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.STRUCTURAL,
                    rootCauseKeyForType(signal.typeName, signal.typeName),
                    "indirect-invocation:" + signal.typeName,
                    "indirect:" + signal.typeName
                            + "|mode=invocation_gap"
                            + "|blockedMethods=" + signal.methodKeys.size()
                            + "|successfulAcquisitions=" + typeSignal.totalConstructionSuccesses()
                            + "|successfulSetup=" + typeSignal.successfulMethodLabelCounts.size()
                            + "|directAttempts=0"));
        }
    }

    private void addExceptionBarrierCards(List<ProblemCard> cards,
                                          Map<String, List<TestFitnessFunction>> goalsByMethod,
                                          Map<String, AttemptStats> statsByMethod,
                                          Map<String, ExceptionBarrierTracker.AggregatedStats> persistentExceptionStats,
                                          ExtractorTelemetry telemetry) {
        Map<String, ExceptionBarrierTracker.AggregatedStats> persistent =
                persistentExceptionStats == null ? Collections.emptyMap() : persistentExceptionStats;
        for (Map.Entry<String, AttemptStats> entry : statsByMethod.entrySet()) {
            String method = entry.getKey();
            AttemptStats stats = entry.getValue();
            ExceptionBarrierTracker.AggregatedStats historical = persistent.get(method);
            int totalAttempts = stats == null ? 0 : stats.attempts;
            int totalSuccesses = stats == null ? 0 : stats.successes;
            int totalExceptions = stats == null ? 0 : stats.exceptions;
            int coveredInFailingTests = stats == null ? 0 : stats.coveredInFailingTests;
            if (historical != null) {
                totalAttempts += historical.getAttempts();
                totalSuccesses += historical.getSuccesses();
                totalExceptions += historical.getExceptions();
                coveredInFailingTests += historical.getCoveredInFailingTests();
            }
            double blockage = totalAttempts == 0 ? 0.0 : ((double) totalExceptions / (double) totalAttempts);
            recordExceptionBarrierMethodTelemetry(totalAttempts, totalExceptions, blockage, telemetry);
            recordExceptionBarrierContextTelemetry(stats, historical, telemetry);
            recordExceptionBarrierUpstreamTelemetry(stats, historical, telemetry);
            boolean methodLevelBarrier = totalAttempts >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && totalExceptions >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && blockage >= MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER;
            ExceptionBarrierContextEvidence contextEvidence = selectExceptionBarrierContext(stats, historical);
            boolean contextBarrier = contextEvidence != null
                    && !contextEvidence.isMethodLevel()
                    && !InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(contextEvidence.getContextKey())
                    && contextEvidence.getAttempts() >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && contextEvidence.getExceptions() >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && contextEvidence.getBlockage() >= MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER;
            UpstreamExceptionBarrierEvidence upstreamEvidence = selectUpstreamExceptionBarrier(stats, historical);
            boolean upstreamBarrier = !methodLevelBarrier && !contextBarrier
                    && upstreamEvidence != null
                    && upstreamEvidence.getAttempts() >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && upstreamEvidence.getExceptions() >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    && upstreamEvidence.getBlockage() >= MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER;
            if (!methodLevelBarrier && !contextBarrier && !upstreamBarrier) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = goalsByMethod.getOrDefault(method, Collections.emptyList());
            double impact = leverageAwareImpact(relatedGoals, 5.0);
            int evidenceAttempts = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getAttempts() : totalAttempts;
            int evidenceExceptions = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getExceptions() : totalExceptions;
            int evidenceSuccesses = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getSuccesses() : totalSuccesses;
            double confidence = barrierConfidence(evidenceAttempts);
            List<String> evidence = new ArrayList<>();
            MethodPromptContext methodContext = describeMethodContext(method, relatedGoals);
            if (upstreamBarrier && upstreamEvidence != null) {
                evidence.add("Observed direct target invocations: " + totalAttempts + ".");
                evidence.add("Direct non-exception completions: " + totalSuccesses + ".");
                evidence.add("Direct exception outcomes: " + totalExceptions + ".");
                evidence.add("Repeated upstream invocation blocked the target before it was reached: "
                        + upstreamEvidence.getBlockerDisplayLabel() + ".");
                evidence.add("Upstream invocations before the target call: " + upstreamEvidence.getAttempts() + ".");
                evidence.add("Upstream non-exception completions before the target call: "
                        + upstreamEvidence.getSuccesses() + ".");
                evidence.add("Upstream exception outcomes before the target call: "
                        + upstreamEvidence.getExceptions() + ".");
                evidence.add("Upstream exception dominance: "
                        + upstreamEvidence.getExceptions() + "/" + upstreamEvidence.getAttempts() + ".");
            } else if (contextEvidence != null && !contextEvidence.isMethodLevel()) {
                evidence.add("Method-wide direct invocations: " + totalAttempts + ".");
                evidence.add("Method-wide non-exception completions: " + totalSuccesses + ".");
                evidence.add("Method-wide exception outcomes: " + totalExceptions + ".");
                evidence.add("Blocked invocation context: " + contextEvidence.getContextLabel() + ".");
                evidence.add("Context-local direct invocations: " + contextEvidence.getAttempts() + ".");
                evidence.add("Context-local non-exception completions: " + contextEvidence.getSuccesses() + ".");
                evidence.add("Context-local exception outcomes: " + contextEvidence.getExceptions() + ".");
                evidence.add("Context-local exception dominance: "
                        + contextEvidence.getExceptions() + "/" + contextEvidence.getAttempts() + ".");
            } else {
                evidence.add("Observed direct invocations: " + totalAttempts + ".");
                evidence.add("Direct non-exception completions: " + totalSuccesses + ".");
                evidence.add("Direct exception outcomes: " + totalExceptions + ".");
                evidence.add("Direct exception dominance: " + totalExceptions + "/" + totalAttempts + ".");
            }
            if (coveredInFailingTests > 0) {
                evidence.add("Covered in failing tests (not all directly attributable): "
                        + coveredInFailingTests + ".");
            }
            if (historical != null) {
                evidence.add(upstreamBarrier
                        ? "Includes persistent upstream-blocker evidence from recent stagnation windows."
                        : "Includes persistent direct-invocation evidence from recent stagnation windows.");
            }
            String dominant = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getDominantException()
                    : contextEvidence != null
                    ? contextEvidence.getDominantException()
                    : dominantException(stats.exceptionTypeCounts);
            if (dominant.isEmpty() && historical != null) {
                dominant = historical.getDominantException();
            }
            if (!dominant.isEmpty()) {
                evidence.add("Dominant exception: " + dominant + ".");
            }
            String dominantInvocation = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getDominantInvocationLabel()
                    : contextEvidence != null
                    ? contextEvidence.getDominantInvocationLabel()
                    : (stats == null ? "" : stats.dominantFailingInvocationLabel());
            if (!dominantInvocation.isEmpty()) {
                evidence.add("Dominant failing invocation: " + dominantInvocation + ".");
            }
            int failingInvocations = upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.getExceptions()
                    : contextEvidence != null ? contextEvidence.getExceptions()
                    : (stats == null ? 0 : stats.exceptions);
            int failingPrefixes = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getFailingInvocationsWithSuccessfulPrefix()
                    : contextEvidence != null ? contextEvidence.getFailingInvocationsWithSuccessfulPrefix()
                    : (stats == null ? 0 : stats.failingInvocationsWithSuccessfulPrefix);
            if (failingInvocations > 0) {
                if (failingPrefixes > 0) {
                    evidence.add("Earlier successful statements preceded the throw in "
                            + failingPrefixes + "/" + failingInvocations
                            + " directly observed failing executions.");
                } else {
                    evidence.add("Direct throws occurred without earlier successful statements in the same failing tests.");
                }
            }
            addOverloadEvidence(evidence, methodContext);
            cards.add(new ProblemCard(
                    ProblemCardType.EXCEPTION_BARRIER,
                    upstreamBarrier && upstreamEvidence != null
                            ? "Upstream invocation is exception-dominated before target call: "
                            + methodContext.displayLabel
                    : contextEvidence != null && !contextEvidence.isMethodLevel()
                            ? "Direct invocations are exception-dominated in context: "
                            + methodContext.displayLabel + " [" + contextEvidence.getContextLabel() + "]"
                            : "Direct invocations are exception-dominated: " + methodContext.displayLabel,
                    evidence,
                    relatedGoals,
                    impact,
                    upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.getBlockage()
                            : contextEvidence != null ? contextEvidence.getBlockage() : blockage,
                    confidence,
                    ProblemCardFamily.EXECUTION,
                    upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.getBlockerExecutionKey() : method,
                    method,
                    "exception:" + method
                            + "|attempts=" + evidenceAttempts
                            + "|exceptions=" + evidenceExceptions
                            + "|successes=" + evidenceSuccesses
                            + "|dominance=" + evidenceExceptions + "/" + evidenceAttempts
                            + (upstreamBarrier && upstreamEvidence != null
                            ? "|upstream=" + upstreamEvidence.getBlockerExecutionKey()
                            : "")
                            + (contextEvidence != null && !contextEvidence.isMethodLevel()
                            ? "|context=" + contextEvidence.getContextKey()
                            + "|contextDominance=" + contextEvidence.getExceptions()
                            + "/" + contextEvidence.getAttempts()
                            : "")
                            + "|dominant=" + dominant));
        }
    }

    private void addStateDiversificationGapCards(List<ProblemCard> cards,
                                                 Map<String, List<TestFitnessFunction>> goalsByMethod,
                                                 Map<String, AttemptStats> statsByMethod) {
        if (goalsByMethod == null || goalsByMethod.isEmpty() || statsByMethod == null || statsByMethod.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            AttemptStats stats = statsByMethod.get(methodKey);
            if (stats == null
                    || stats.successes < MIN_ATTEMPTS_FOR_STATE_DIVERSIFICATION
                    || stats.attempts != stats.successes
                    || stats.exceptions > 0) {
                continue;
            }
            StateDiversificationContextEvidence dominantContext = selectStateDiversificationContext(stats);
            if (dominantContext == null) {
                continue;
            }
            double dominantShare = dominantContext.successShare(stats.successes);
            if (dominantContext.successes < MIN_ATTEMPTS_FOR_STATE_DIVERSIFICATION
                    || dominantShare < MIN_CONTEXT_SHARE_FOR_STATE_DIVERSIFICATION) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = entry.getValue();
            MethodPromptContext methodContext = describeMethodContext(methodKey, relatedGoals);
            if (isSyntheticCompilerMethod(baseMethodNameFromExecutionKey(methodContext.executionKey))) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Direct executions repeatedly reached " + methodContext.displayLabel
                    + " but stayed in the same successful regime.");
            evidence.add("Observed direct executions: " + stats.attempts + " (successes="
                    + stats.successes + ", exceptions=0).");
            evidence.add("Dominant successful context: " + dominantContext.contextLabel
                    + " (" + dominantContext.successes + "/" + stats.successes + " successful executions).");
            evidence.add("Dominant-regime share: " + dominantContext.successes + "/"
                    + stats.successes + " successful executions.");
            evidence.add("Distinct successful contexts observed: " + countSuccessfulContexts(stats) + ".");
            evidence.add("Related uncovered goals still remaining for this method: " + relatedGoals.size() + ".");
            addOverloadEvidence(evidence, methodContext);

            double impact = leverageAwareImpact(relatedGoals, 5.0);
            double blockage = dominantShare;
            double confidence = barrierConfidence(stats.successes);
            cards.add(new ProblemCard(
                    ProblemCardType.STATE_DIVERSIFICATION_GAP,
                    "Direct executions stay in one regime: " + methodContext.displayLabel,
                    evidence,
                    relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.LOCAL,
                    methodKey,
                    "diversification:" + methodKey,
                    "state_diversification:" + methodKey
                            + "|context=" + dominantContext.contextKey
                            + "|successes=" + stats.successes
                            + "|share=" + String.format("%.3f", dominantShare)));
        }
    }

    private void recordExceptionBarrierMethodTelemetry(int totalAttempts,
                                                       int totalExceptions,
                                                       double blockage,
                                                       ExtractorTelemetry telemetry) {
        if (telemetry == null || totalAttempts <= 0 || totalExceptions <= 0) {
            return;
        }
        if (totalAttempts < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                || totalExceptions < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER) {
            telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            return;
        }
        if (blockage < MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER) {
            telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
            return;
        }
        telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES);
    }

    private void recordExceptionBarrierContextTelemetry(AttemptStats current,
                                                        ExceptionBarrierTracker.AggregatedStats historical,
                                                        ExtractorTelemetry telemetry) {
        if (telemetry == null) {
            return;
        }
        for (ExceptionBarrierContextEvidence candidate : mergedExceptionBarrierContexts(current, historical).values()) {
            if (candidate == null
                    || candidate.isMethodLevel()
                    || InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(candidate.getContextKey())
                    || candidate.getAttempts() <= 0
                    || candidate.getExceptions() <= 0) {
                continue;
            }
            if (candidate.getAttempts() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    || candidate.getExceptions() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER) {
                telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
                continue;
            }
            if (candidate.getBlockage() < MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER) {
                telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
                continue;
            }
            telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_CONTEXT_CANDIDATES);
        }
    }

    private void recordExceptionBarrierUpstreamTelemetry(AttemptStats current,
                                                         ExceptionBarrierTracker.AggregatedStats historical,
                                                         ExtractorTelemetry telemetry) {
        if (telemetry == null) {
            return;
        }
        for (UpstreamExceptionBarrierEvidence candidate : mergedUpstreamExceptionBarriers(current, historical).values()) {
            if (candidate == null || candidate.getAttempts() <= 0 || candidate.getExceptions() <= 0) {
                continue;
            }
            if (candidate.getAttempts() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    || candidate.getExceptions() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER) {
                telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
                continue;
            }
            if (candidate.getBlockage() < MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER) {
                telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
                continue;
            }
            telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_UPSTREAM_CANDIDATES);
        }
    }

    private Map<String, ExceptionBarrierContextEvidence> mergedExceptionBarrierContexts(
            AttemptStats current,
            ExceptionBarrierTracker.AggregatedStats historical) {
        Map<String, ExceptionBarrierContextEvidence> mergedContexts = new LinkedHashMap<>();
        if (current != null) {
            for (ContextAttemptStats contextStats : current.contextStats.values()) {
                mergeExceptionBarrierContext(mergedContexts, contextStats);
            }
        }
        if (historical != null) {
            for (ExceptionBarrierTracker.ContextStats contextStats : historical.getContextStats().values()) {
                mergeExceptionBarrierContext(mergedContexts, contextStats);
            }
        }
        return mergedContexts;
    }

    private ExceptionBarrierContextEvidence selectExceptionBarrierContext(
            AttemptStats current,
            ExceptionBarrierTracker.AggregatedStats historical) {
        Map<String, ExceptionBarrierContextEvidence> mergedContexts = mergedExceptionBarrierContexts(current, historical);

        ExceptionBarrierContextEvidence best = null;
        for (ExceptionBarrierContextEvidence candidate : mergedContexts.values()) {
            if (candidate == null
                    || candidate.isMethodLevel()
                    || InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(candidate.getContextKey())) {
                continue;
            }
            if (candidate.getAttempts() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    || candidate.getExceptions() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER) {
                continue;
            }
            if (candidate.getBlockage() < MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER) {
                continue;
            }
            if (best == null || candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }

        if (current == null && historical == null) {
            return null;
        }
        int attempts = current == null ? 0 : current.attempts;
        int successes = current == null ? 0 : current.successes;
        int exceptions = current == null ? 0 : current.exceptions;
        String dominantException = current == null ? "" : dominantException(current.exceptionTypeCounts);
        String dominantInvocation = current == null ? "" : current.dominantFailingInvocationLabel();
        int failingPrefixes = current == null ? 0 : current.failingInvocationsWithSuccessfulPrefix;
        if (historical != null) {
            attempts += historical.getAttempts();
            successes += historical.getSuccesses();
            exceptions += historical.getExceptions();
            if (dominantException.isEmpty()) {
                dominantException = historical.getDominantException();
            }
            if (dominantInvocation.isEmpty()) {
                dominantInvocation = historical.getDominantFailingInvocationLabel();
            }
            failingPrefixes += historical.getFailingInvocationsWithSuccessfulPrefix();
        }
        return new ExceptionBarrierContextEvidence(
                InvocationContextClassifier.DEFAULT_CONTEXT.getKey(),
                InvocationContextClassifier.DEFAULT_CONTEXT.getLabel(),
                attempts,
                successes,
                exceptions,
                dominantException,
                dominantInvocation,
                failingPrefixes,
                true);
    }

    private UpstreamExceptionBarrierEvidence selectUpstreamExceptionBarrier(
            AttemptStats current,
            ExceptionBarrierTracker.AggregatedStats historical) {
        Map<String, UpstreamExceptionBarrierEvidence> merged = mergedUpstreamExceptionBarriers(current, historical);
        UpstreamExceptionBarrierEvidence best = null;
        for (UpstreamExceptionBarrierEvidence candidate : merged.values()) {
            if (candidate == null) {
                continue;
            }
            if (candidate.getAttempts() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    || candidate.getExceptions() < MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER
                    || candidate.getBlockage() < MIN_FAILURE_RATE_FOR_EXCEPTION_BARRIER) {
                continue;
            }
            if (best == null || candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private Map<String, UpstreamExceptionBarrierEvidence> mergedUpstreamExceptionBarriers(
            AttemptStats current,
            ExceptionBarrierTracker.AggregatedStats historical) {
        Map<String, UpstreamExceptionBarrierEvidence> merged = new LinkedHashMap<>();
        if (current != null) {
            for (UpstreamAttemptStats upstream : current.upstreamStats.values()) {
                mergeUpstreamExceptionEvidence(merged, upstream);
            }
        }
        if (historical != null) {
            for (ExceptionBarrierTracker.UpstreamStats upstream : historical.getUpstreamStats().values()) {
                mergeUpstreamExceptionEvidence(merged, upstream);
            }
        }
        return merged;
    }

    private void mergeUpstreamExceptionEvidence(Map<String, UpstreamExceptionBarrierEvidence> merged,
                                                UpstreamAttemptStats source) {
        if (merged == null || source == null || source.blockerExecutionKey == null
                || source.blockerExecutionKey.isEmpty()) {
            return;
        }
        UpstreamExceptionBarrierEvidence current = merged.computeIfAbsent(source.blockerExecutionKey,
                ignored -> new UpstreamExceptionBarrierEvidence(source.blockerExecutionKey,
                        source.blockerDisplayLabel, 0, 0, 0, "", "", 0));
        current.add(source.attempts, source.successes, source.exceptions,
                dominantException(source.exceptionTypeCounts),
                source.dominantFailingInvocationLabel(),
                source.failingInvocationsWithSuccessfulPrefix,
                source.blockerDisplayLabel);
    }

    private void mergeUpstreamExceptionEvidence(Map<String, UpstreamExceptionBarrierEvidence> merged,
                                                ExceptionBarrierTracker.UpstreamStats source) {
        if (merged == null || source == null || source.getBlockerExecutionKey() == null
                || source.getBlockerExecutionKey().isEmpty()) {
            return;
        }
        UpstreamExceptionBarrierEvidence current = merged.computeIfAbsent(source.getBlockerExecutionKey(),
                ignored -> new UpstreamExceptionBarrierEvidence(source.getBlockerExecutionKey(),
                        source.getBlockerDisplayLabel(), 0, 0, 0, "", "", 0));
        current.add(source.getAttempts(), source.getSuccesses(), source.getExceptions(),
                source.getDominantException(),
                source.getDominantFailingInvocationLabel(),
                source.getFailingInvocationsWithSuccessfulPrefix(),
                source.getBlockerDisplayLabel());
    }

    private void mergeExceptionBarrierContext(
            Map<String, ExceptionBarrierContextEvidence> merged,
            ContextAttemptStats source) {
        if (merged == null || source == null) {
            return;
        }
        ExceptionBarrierContextEvidence current = merged.computeIfAbsent(source.contextKey,
                ignored -> new ExceptionBarrierContextEvidence(source.contextKey,
                        source.contextLabel, 0, 0, 0, "", "", 0, false));
        current.add(source.attempts, source.successes, source.exceptions,
                dominantException(source.exceptionTypeCounts),
                source.dominantFailingInvocationLabel(),
                source.failingInvocationsWithSuccessfulPrefix);
    }

    private void mergeExceptionBarrierContext(
            Map<String, ExceptionBarrierContextEvidence> merged,
            ExceptionBarrierTracker.ContextStats source) {
        if (merged == null || source == null) {
            return;
        }
        ExceptionBarrierContextEvidence current = merged.computeIfAbsent(source.getContextKey(),
                ignored -> new ExceptionBarrierContextEvidence(source.getContextKey(),
                        source.getContextLabel(), 0, 0, 0, "", "", 0, false));
        current.add(source.getAttempts(), source.getSuccesses(), source.getExceptions(),
                source.getDominantException(),
                source.getDominantFailingInvocationLabel(),
                source.getFailingInvocationsWithSuccessfulPrefix());
    }

    private Map<String, TypeBarrierSignal> collectTypeBarrierSignals(
            Map<String, List<TestFitnessFunction>> goalsByMethod,
            List<TestChromosome> snapshot,
            ExtractorTelemetry telemetry) {
        Map<String, TypeBarrierSignal> signals = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        Map<String, List<TestFitnessFunction>> goalsByType = groupGoalsByType(goalsByMethod);
        List<TestFitnessFunction> allGoals = flattenGoals(goalsByMethod);
        boolean traceEnabled = shouldTraceExtractorForCurrentTarget();
        int chromosomeIndex = 0;
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                chromosomeIndex++;
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                chromosomeIndex++;
                continue;
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                chromosomeIndex++;
                continue;
            }

            int thrownPos = firstThrownPosition(result);
            traceExtractor(traceEnabled, "type_barriers",
                    "test={} safe_size={} executed_statements={} thrown_pos={} has_exception={} timeout={}",
                    chromosomeIndex, safeTestSize(test), result.getExecutedStatements(), thrownPos,
                    result.hasTestException(), result.hasTimeout());
            recordSuccessfulTypeLifecycleSteps(signals, goalsByType, allGoals, test, result, thrownPos, telemetry,
                    traceEnabled, chromosomeIndex);

            if (!result.hasTestException() || thrownPos < 0) {
                chromosomeIndex++;
                continue;
            }
            Statement thrownStatement = statementAt(test, thrownPos);
            if (thrownStatement == null) {
                traceExtractor(traceEnabled, "type_barriers",
                        "test={} stmt={} action=skip_missing_thrown_statement",
                        chromosomeIndex, thrownPos);
                chromosomeIndex++;
                continue;
            }
            String exception = exceptionKey(result);
            if (thrownStatement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) thrownStatement;
                String observedTypeName = constructorType(constructorStatement);
                if (observedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_constructor_without_observed_type",
                            chromosomeIndex, thrownPos);
                    chromosomeIndex++;
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, thrownPos, observedTypeName, goalsByType, telemetry, traceEnabled,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_unmapped_constructor_type_barrier observed_type={}",
                            chromosomeIndex, thrownPos, observedTypeName);
                    chromosomeIndex++;
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                signal.recordFailingConstructor(
                        goalDescriptionMapper.describeConstructorOperation(constructorStatement).getDisplayLabel());
                signal.exceptionTypeCounts.put(exception,
                        signal.exceptionTypeCounts.getOrDefault(exception, 0) + 1);
                traceExtractor(traceEnabled, "type_barriers",
                        "test={} stmt={} action=failing_constructor observed_type={} blocked_type={} effective_type={} "
                                + "related_goals={} exception={}",
                        chromosomeIndex, thrownPos, observedTypeName, blockedTypeName, blockedTypeName,
                        signal.relatedGoals.size(), exception);
                chromosomeIndex++;
                continue;
            }
            if (thrownStatement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) thrownStatement;
                String observedTypeName = thrownTypeForMethod(methodStatement);
                if (observedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_method_without_observed_type label={}",
                            chromosomeIndex, thrownPos, statementDebugLabel(methodStatement));
                    chromosomeIndex++;
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, thrownPos, observedTypeName, goalsByType, telemetry, traceEnabled,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_unmapped_method_type_barrier observed_type={} label={}",
                            chromosomeIndex, thrownPos, observedTypeName, statementDebugLabel(methodStatement));
                    chromosomeIndex++;
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                if (isFactoryMethod(methodStatement)) {
                    signal.recordFailingFactory(
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel());
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=failing_factory label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} exception={}",
                            chromosomeIndex, thrownPos, statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(), exception);
                } else {
                    signal.recordFailingSetupMethod(setupMethodKey(methodStatement),
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel());
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=failing_setup label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} exception={}",
                            chromosomeIndex, thrownPos, statementDebugLabel(methodStatement),
                            observedTypeName, blockedTypeName, blockedTypeName, signal.relatedGoals.size(), exception);
                }
                signal.exceptionTypeCounts.put(exception,
                        signal.exceptionTypeCounts.getOrDefault(exception, 0) + 1);
            }
            chromosomeIndex++;
        }
        return signals;
    }

    private void addTypeBarrierCards(List<ProblemCard> cards,
                                     Map<String, TypeBarrierSignal> signals,
                                     ExtractorTelemetry telemetry) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        for (TypeBarrierSignal signal : signals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty()) {
                continue;
            }
            if (signal.totalConstructionAttempts() > 0) {
                telemetry.increment(ExtractorCandidateMetric.TYPE_BARRIER_SIGNALS_WITH_CONSTRUCTION_FAILURE);
            }
            if (signal.setupAttempts > 0) {
                telemetry.increment(ExtractorCandidateMetric.TYPE_BARRIER_SIGNALS_WITH_SETUP_FAILURE);
            }
            addUninstantiableTypeCard(cards, signal, telemetry);
            addStateSetupBarrierCard(cards, signal, telemetry);
        }
    }

    private void addUninstantiableTypeCard(List<ProblemCard> cards,
                                           TypeBarrierSignal signal,
                                           ExtractorTelemetry telemetry) {
        if (signal.totalConstructionAttempts() < MIN_ATTEMPTS_FOR_TYPE_BARRIER) {
            if (signal.totalConstructionAttempts() > 0) {
                telemetry.increment(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            }
            return;
        }
        telemetry.increment(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_CANDIDATES);
        if (signal.hasAcquisitionProgressBeyondCreation()) {
            telemetry.increment(ExtractorRejectReason.UNINSTANTIABLE_PROGRESS_BEYOND_CREATION);
            return;
        }
        double impact = leverageAwareImpact(signal.relatedGoals, 5.0);
        double blockage = signal.acquisitionFailureRate();
        if (signal.totalConstructionSuccesses() > 0
                && blockage < MIN_FAILURE_RATE_FOR_PRACTICAL_ACQUISITION_BARRIER) {
            telemetry.increment(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE);
            return;
        }
        double confidence = barrierConfidence(signal.totalConstructionAttempts());
        List<String> evidence = new ArrayList<>();
        evidence.add("Acquisition attempts that ended with exceptions: "
                + signal.totalConstructionAttempts() + ".");
        if (signal.constructorAttempts > 0 || signal.factoryAttempts > 0) {
            evidence.add("Constructor failures: " + signal.constructorAttempts
                    + "; factory failures: " + signal.factoryAttempts + ".");
        }
        evidence.add("Successful acquisitions observed: " + signal.totalConstructionSuccesses() + ".");
        if (signal.constructorSuccesses > 0 || signal.factorySuccesses > 0) {
            evidence.add("Constructor successes: " + signal.constructorSuccesses
                    + "; factory successes: " + signal.factorySuccesses + ".");
        }
        evidence.add("Acquisitions that progressed into same-type or goal-bearing execution: "
                + signal.totalConstructionSuccessesWithProgress() + ".");
        if (signal.totalConstructionSuccessesWithoutProgress() > 0) {
            evidence.add("Successful acquisitions without any later same-type or goal-bearing step: "
                    + signal.totalConstructionSuccessesWithoutProgress() + ".");
        }
        if (signal.totalConstructionSuccesses() > 0 && !signal.hasAcquisitionProgressBeyondCreation()) {
            evidence.add("Observed acquisitions never led to a usable post-construction step.");
        }
        evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");
        String dominant = dominantException(signal.exceptionTypeCounts);
        if (!dominant.isEmpty()) {
            evidence.add("Dominant exception: " + dominant + ".");
        }
        String dominantAcquisition = signal.dominantFailingAcquisitionLabel();
        if (!dominantAcquisition.isEmpty()) {
            evidence.add("Dominant failing acquisition entry point: " + dominantAcquisition + ".");
        }
        addBootstrapDependencyEvidence(evidence, signal.typeName, dominantAcquisition);
        cards.add(new ProblemCard(
                ProblemCardType.UNINSTANTIABLE_TYPE,
                "Cannot instantiate/acquire type: " + signal.typeName,
                evidence,
                signal.relatedGoals,
                impact,
                blockage,
                confidence,
                ProblemCardFamily.STRUCTURAL,
                signal.typeName,
                "acquisition:" + signal.typeName,
                "uninstantiable:" + signal.typeName
                        + "|constructors=" + signal.constructorAttempts
                        + "|factories=" + signal.factoryAttempts
                        + "|successes=" + signal.totalConstructionSuccesses()
                        + "|progressed=" + signal.totalConstructionSuccessesWithProgress()
                        + "|exception=" + dominant));
    }

    private void addStateSetupBarrierCard(List<ProblemCard> cards,
                                          TypeBarrierSignal signal,
                                          ExtractorTelemetry telemetry) {
        if (signal.setupAttempts <= 0) {
            return;
        }
        if (signal.totalConstructionSuccesses() <= 0) {
            telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_NO_SUCCESSFUL_ACQUISITION);
            return;
        }
        if (signal.setupAttempts < MIN_ATTEMPTS_FOR_TYPE_BARRIER) {
            telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            return;
        }
        telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_CANDIDATES);
        String dominantSetupKey = signal.dominantFailingSetupMethodKey();
        int failingExecutions = signal.failingExecutionsForSetupMethod(dominantSetupKey);
        int successfulExecutions = signal.successfulExecutionsForSetupMethod(dominantSetupKey);
        boolean distributedSetupBreakdown = false;
        if (failingExecutions < MIN_ATTEMPTS_FOR_TYPE_BARRIER) {
            distributedSetupBreakdown = signal.distinctFailingSetupSteps() >= 2;
            if (!distributedSetupBreakdown) {
                telemetry.increment(ExtractorCandidateMetric.STATE_SETUP_BARRIER_SUPPRESSED_INCONSISTENT_FAILING_STEP);
                return;
            }
            failingExecutions = signal.setupAttempts;
            successfulExecutions = signal.countSuccessfulExecutionsForFailingSetupMethods();
        }
        double blockage = failureRate(failingExecutions, successfulExecutions);
        if (blockage < MIN_FAILURE_RATE_FOR_STATE_SETUP_BARRIER) {
            telemetry.increment(ExtractorRejectReason.STATE_SETUP_DILUTED_SUCCESS);
            return;
        }
        double impact = leverageAwareImpact(signal.relatedGoals, 5.0);
        double confidence = barrierConfidence(signal.setupAttempts);
        List<String> evidence = new ArrayList<>();
        evidence.add("Construction succeeded, but setup/lifecycle calls failed: "
                + signal.setupAttempts + " exception outcomes.");
        evidence.add("Dominant failing setup-step outcomes: "
                + failingExecutions + "/" + (failingExecutions + successfulExecutions) + ".");
        evidence.add("Successful executions of the dominant failing setup step observed: "
                + successfulExecutions + ".");
        evidence.add("Successful acquisitions observed: " + signal.totalConstructionSuccesses() + ".");
        evidence.add("Related uncovered goals: " + signal.relatedGoals.size() + ".");
        String dominant = dominantException(signal.exceptionTypeCounts);
        if (!dominant.isEmpty()) {
            evidence.add("Dominant exception: " + dominant + ".");
        }
        String dominantSetup = signal.dominantFailingSetupLabel();
        if (distributedSetupBreakdown) {
            evidence.add("Failing setup/lifecycle steps are distributed across multiple bootstrap operations.");
            String failingSteps = signal.describeFailingSetupLabels(3);
            if (!failingSteps.isEmpty()) {
                evidence.add("Observed failing setup/lifecycle steps: " + failingSteps + ".");
            }
        } else if (!dominantSetup.isEmpty()) {
            evidence.add("Dominant failing setup/lifecycle step: " + dominantSetup + ".");
        }
        addBootstrapDependencyEvidence(evidence, signal.typeName,
                distributedSetupBreakdown ? signal.describeFailingSetupLabels(1) : dominantSetup);
        cards.add(new ProblemCard(
                ProblemCardType.STATE_SETUP_BARRIER,
                "Type setup/lifecycle fails before target behavior: " + signal.typeName,
                evidence,
                signal.relatedGoals,
                impact,
                blockage,
                confidence,
                ProblemCardFamily.STRUCTURAL,
                signal.typeName,
                "setup:" + signal.typeName,
                "setup:" + signal.typeName
                        + "|attempts=" + signal.setupAttempts
                        + "|dominantFailures=" + failingExecutions
                        + "|dominantSuccesses=" + successfulExecutions
                        + "|acquisitions=" + signal.totalConstructionSuccesses()
                        + "|setup=" + dominantSetup));
    }

    private Map<String, BranchGoalSignal> collectBranchSignals(
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> snapshot) {
        Map<String, BranchGoalSignal> signals = new LinkedHashMap<>();
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return signals;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            BranchGoalSignal signal = branchSignalForGoal(goal);
            if (signal == null) {
                continue;
            }
            String key = signal.signalKey();
            BranchGoalSignal existing = signals.get(key);
            if (existing == null) {
                signals.put(key, signal);
            } else {
                existing.relatedGoals.add(goal);
            }
        }
        if (signals.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }

        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            if (trace == null) {
                continue;
            }
            Set<Integer> trueBranches = safeSet(trace.getCoveredTrueBranches());
            Set<Integer> falseBranches = safeSet(trace.getCoveredFalseBranches());
            for (BranchGoalSignal signal : signals.values()) {
                boolean seenDesired = signal.desiredValue
                        ? trueBranches.contains(signal.branchId)
                        : falseBranches.contains(signal.branchId);
                boolean seenOpposite = signal.desiredValue
                        ? falseBranches.contains(signal.branchId)
                        : trueBranches.contains(signal.branchId);
                if (seenDesired || seenOpposite) {
                    signal.predicateExecutions++;
                }
                if (seenDesired) {
                    signal.desiredHits++;
                }
                if (seenOpposite) {
                    signal.oppositeHits++;
                }
            }
        }
        return signals;
    }

    private void addBranchPolarityGapCards(List<ProblemCard> cards,
                                           Map<String, BranchGoalSignal> branchSignals) {
        if (branchSignals == null || branchSignals.isEmpty()) {
            return;
        }
        List<BranchGoalSignal> candidates = new ArrayList<>();
        for (BranchGoalSignal signal : branchSignals.values()) {
            if (signal == null) {
                continue;
            }
            if (signal.desiredHits > 0) {
                continue;
            }
            if (signal.oppositeHits < MIN_POLARITY_GAP_OPPOSITE_OBSERVATIONS) {
                continue;
            }
            candidates.add(signal);
        }

        Map<String, Integer> saturatedCandidatesByMethod = new HashMap<>();
        for (BranchGoalSignal signal : candidates) {
            if (isSaturatedPolarityGap(signal)) {
                saturatedCandidatesByMethod.put(signal.methodKey,
                        saturatedCandidatesByMethod.getOrDefault(signal.methodKey, 0) + 1);
            }
        }

        for (BranchGoalSignal signal : candidates) {
            if (isSyntheticCompilerMethod(signal.branchTarget.getTarget().getBaseMethodName())) {
                continue;
            }
            double impact = normalizeCount(signal.relatedGoals.size(), 3.0);
            double blockage = signal.predicateExecutions <= 0
                    ? 0.0
                    : ((double) signal.oppositeHits / (double) signal.predicateExecutions);
            double confidence = normalizeCount(signal.oppositeHits, 6.0);
            boolean lowSteerability = isLikelyLowSteerabilityBranchGap(
                    signal, saturatedCandidatesByMethod.getOrDefault(signal.methodKey, 0));
            if (lowSteerability) {
                // Saturated polarity gaps repeated across one method are often caller-locked/noise.
                impact *= 0.55;
                blockage *= 0.55;
                confidence *= 0.60;
            }
            String branchLocation = signal.branchTarget.getLocationLabel();
            String desiredLabel = signal.branchTarget.getOutcomeLabel();
            List<String> evidence = new ArrayList<>();
            evidence.add("Desired branch outcome " + desiredLabel
                    + " was never observed for " + branchLocation + ".");
            evidence.add("Observed non-target branch outcome in "
                    + signal.oppositeHits + " tests.");
            evidence.add("Predicate executed in " + signal.predicateExecutions + " tests.");
            evidence.add("Opposite-outcome dominance: " + signal.oppositeHits
                    + "/" + Math.max(1, signal.predicateExecutions) + ".");
            if (lowSteerability) {
                evidence.add("Observed a saturated opposite-outcome pattern across multiple branches in this method; "
                        + "this gap is likely caller-locked from available public entrypoints.");
            }
            cards.add(new ProblemCard(
                    ProblemCardType.BRANCH_POLARITY_GAP,
                    "Branch polarity gap in " + branchLocation + " (" + signal.branchTarget.getNeedLabel() + ")",
                    evidence,
                    signal.relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.LOCAL,
                    signal.signalKey(),
                    signal.signalKey(),
                    "branch:" + signal.signalKey()
                            + "|opposite=" + signal.oppositeHits
                            + "|desired=" + signal.desiredHits
                            + "|executions=" + signal.predicateExecutions
                            + "|steer=" + (lowSteerability ? "low" : "normal")));
        }
    }

    private boolean isSaturatedPolarityGap(BranchGoalSignal signal) {
        return signal != null
                && signal.predicateExecutions >= MIN_LOW_STEERABILITY_BRANCH_EXECUTIONS
                && signal.oppositeHits >= signal.predicateExecutions
                && signal.desiredHits == 0;
    }

    private boolean isLikelyLowSteerabilityBranchGap(BranchGoalSignal signal, int saturatedPeersInMethod) {
        if (!isSaturatedPolarityGap(signal)) {
            return false;
        }
        if (saturatedPeersInMethod >= 2) {
            return true;
        }
        if (signal.predicateExecutions >= 8) {
            return true;
        }
        String baseMethodName = signal == null || signal.branchTarget == null
                || signal.branchTarget.getTarget() == null
                ? ""
                : signal.branchTarget.getTarget().getBaseMethodName();
        return looksLikeHelperMethod(baseMethodName) && signal.predicateExecutions >= 6;
    }

    private boolean looksLikeHelperMethod(String baseMethodName) {
        if (baseMethodName == null || baseMethodName.isEmpty()) {
            return false;
        }
        String method = baseMethodName.toLowerCase(java.util.Locale.ROOT);
        return method.startsWith("write")
                || method.startsWith("build")
                || method.startsWith("create")
                || method.startsWith("to")
                || method.startsWith("from")
                || method.startsWith("setup")
                || method.startsWith("configure")
                || method.startsWith("init");
    }

    private boolean isSyntheticCompilerMethod(String baseMethodName) {
        if (baseMethodName == null || baseMethodName.isEmpty()) {
            return false;
        }
        return baseMethodName.startsWith("access$")
                || baseMethodName.startsWith("lambda$")
                || baseMethodName.startsWith("$deserializeLambda$")
                || baseMethodName.startsWith("$SWITCH_TABLE$");
    }

    private String baseMethodNameFromExecutionKey(String executionKey) {
        if (executionKey == null || executionKey.isEmpty()) {
            return "";
        }
        int dot = executionKey.lastIndexOf('.');
        if (dot < 0 || dot >= executionKey.length() - 1) {
            return executionKey;
        }
        return executionKey.substring(dot + 1);
    }

    private BranchGoalSignal branchSignalForGoal(TestFitnessFunction goal) {
        if (goal instanceof BranchCoverageTestFitness) {
            BranchCoverageGoal branchGoal = ((BranchCoverageTestFitness) goal).getBranchGoal();
            return fromBranchGoal(branchGoal, goal);
        }
        if (goal instanceof OnlyBranchCoverageTestFitness) {
            BranchCoverageGoal branchGoal = ((OnlyBranchCoverageTestFitness) goal).getBranchGoal();
            return fromBranchGoal(branchGoal, goal);
        }
        return null;
    }

    private BranchGoalSignal fromBranchGoal(BranchCoverageGoal branchGoal, TestFitnessFunction goal) {
        if (branchGoal == null) {
            return null;
        }
        Branch branch = branchGoal.getBranch();
        if (branch == null) {
            return null;
        }
        GoalDescriptionMapper.BranchTarget branchTarget = goalDescriptionMapper.describeBranchTarget(branchGoal);
        if (branchTarget.isSwitchCaseBranch() && branchTarget.isDefaultCase()) {
            // Enum-switch synthetic default branches are commonly infeasible and create noisy local cards.
            return null;
        }
        if (isSyntheticCompilerMethod(branchTarget.getTarget().getBaseMethodName())) {
            return null;
        }
        String methodKey = qualifiedMethodKey(branchTarget.getTarget());
        if (methodKey.isEmpty()) {
            return null;
        }
        return new BranchGoalSignal(methodKey,
                branch.getActualBranchId(),
                branchGoal.getValue(),
                branchTarget,
                goal);
    }

    private Map<String, CdgDependencySignal> collectCdgDependencySignals(
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> snapshot) {
        Map<String, CdgDependencySignal> signals = new LinkedHashMap<>();
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return signals;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            BranchCoverageGoal branchGoal = branchCoverageGoalFor(goal);
            if (branchGoal == null || branchGoal.getBranch() == null
                    || branchGoal.getBranch().getInstruction() == null) {
                continue;
            }
            Set<ControlDependency> deps = branchGoal.getBranch().getInstruction().getControlDependencies();
            if (deps == null || deps.isEmpty()) {
                continue;
            }
            for (ControlDependency dep : deps) {
                if (dep == null || dep.getBranch() == null) {
                    continue;
                }
                Branch depBranch = dep.getBranch();
                GoalDescriptionMapper.BranchTarget branchTarget =
                        goalDescriptionMapper.describeBranchTarget(depBranch, dep.getBranchExpressionValue());
                if (isSyntheticCompilerMethod(branchTarget.getTarget().getBaseMethodName())) {
                    continue;
                }
                String depMethod = qualifiedMethodKey(branchTarget.getTarget());
                if (depMethod.isEmpty()) {
                    continue;
                }
                CdgDependencySignal signal = new CdgDependencySignal(
                        depMethod,
                        depBranch.getActualBranchId(),
                        dep.getBranchExpressionValue(),
                        branchTarget);
                CdgDependencySignal existing = signals.get(signal.signalKey());
                if (existing == null) {
                    existing = signal;
                    signals.put(existing.signalKey(), existing);
                }
                existing.relatedGoals.add(goal);
            }
        }
        if (signals.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null || result.getTrace() == null) {
                continue;
            }
            ExecutionTrace trace = result.getTrace();
            Set<Integer> trueBranches = safeSet(trace.getCoveredTrueBranches());
            Set<Integer> falseBranches = safeSet(trace.getCoveredFalseBranches());
            for (CdgDependencySignal signal : signals.values()) {
                boolean seenDesired = signal.desiredValue
                        ? trueBranches.contains(signal.branchId)
                        : falseBranches.contains(signal.branchId);
                boolean seenOpposite = signal.desiredValue
                        ? falseBranches.contains(signal.branchId)
                        : trueBranches.contains(signal.branchId);
                if (seenDesired || seenOpposite) {
                    signal.predicateExecutions++;
                }
                if (seenDesired) {
                    signal.desiredHits++;
                }
                if (seenOpposite) {
                    signal.oppositeHits++;
                }
            }
        }
        return signals;
    }

    private void addCdgBottleneckCards(List<ProblemCard> cards,
                                       Map<String, CdgDependencySignal> cdgSignals) {
        if (cdgSignals == null || cdgSignals.isEmpty()) {
            return;
        }
        for (CdgDependencySignal signal : cdgSignals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty()) {
                continue;
            }
            if (isSyntheticCompilerMethod(signal.branchTarget.getTarget().getBaseMethodName())) {
                continue;
            }
            if (signal.desiredHits > 0 || signal.oppositeHits <= 0) {
                continue;
            }
            double impact = normalizeCount(signal.relatedGoals.size(), 5.0);
            double blockage = signal.predicateExecutions <= 0
                    ? 1.0
                    : ((double) signal.oppositeHits / (double) signal.predicateExecutions);
            double confidence = signal.predicateExecutions <= 0
                    ? 0.6
                    : normalizeCount(signal.oppositeHits, 6.0);
            String branchLocation = signal.branchTarget.getLocationLabel();
            String desiredLabel = signal.branchTarget.getOutcomeLabel();
            List<String> evidence = new ArrayList<>();
            evidence.add("Required control dependency outcome " + desiredLabel
                    + " was never observed for " + branchLocation + ".");
            evidence.add("Uncovered goals transitively blocked by this dependency: "
                    + signal.relatedGoals.size() + ".");
            if (signal.predicateExecutions > 0) {
                evidence.add("Opposite dependency outcome observed in "
                        + signal.oppositeHits + " tests.");
            } else {
                evidence.add("Dependency branch was not observed in current executions.");
            }
            cards.add(new ProblemCard(
                    ProblemCardType.CDG_BOTTLENECK,
                    "Control-dependency bottleneck in " + branchLocation + " ("
                            + signal.branchTarget.getNeedLabel() + ")",
                    evidence,
                    signal.relatedGoals,
                    impact,
                    blockage,
                    confidence,
                    ProblemCardFamily.LOCAL,
                    signal.signalKey(),
                    signal.signalKey(),
                    "cdg:" + signal.signalKey()
                            + "|opposite=" + signal.oppositeHits
                            + "|desired=" + signal.desiredHits
                            + "|executions=" + signal.predicateExecutions
                            + "|blockedGoals=" + signal.relatedGoals.size()));
        }
    }

    private BranchCoverageGoal branchCoverageGoalFor(TestFitnessFunction goal) {
        if (goal instanceof BranchCoverageTestFitness) {
            return ((BranchCoverageTestFitness) goal).getBranchGoal();
        }
        if (goal instanceof OnlyBranchCoverageTestFitness) {
            return ((OnlyBranchCoverageTestFitness) goal).getBranchGoal();
        }
        return null;
    }

    private String methodKey(TestFitnessFunction goal) {
        if (goal == null) {
            return null;
        }
        String method = goalDescriptionMapper.describeMethodOperation(goal).getExecutionKey();
        if (method.isEmpty()) {
            return null;
        }
        return method;
    }

    private String qualifiedMethodKey(GoalDescriptionMapper.GoalTarget target) {
        if (target == null) {
            return "";
        }
        return target.getExecutionKey();
    }

    private Map<String, List<TestFitnessFunction>> groupGoalsByType(
            Map<String, List<TestFitnessFunction>> goalsByMethod) {
        Map<String, List<TestFitnessFunction>> goalsByType = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return goalsByType;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            int idx = methodKey.lastIndexOf('.');
            if (idx <= 0) {
                continue;
            }
            String typeName = methodKey.substring(0, idx);
            List<TestFitnessFunction> bucket = goalsByType.computeIfAbsent(typeName,
                    ignored -> new ArrayList<>());
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                bucket.addAll(entry.getValue());
            }
        }
        return goalsByType;
    }

    private List<TestFitnessFunction> flattenGoals(Map<String, List<TestFitnessFunction>> goalsByMethod) {
        List<TestFitnessFunction> flattened = new ArrayList<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return flattened;
        }
        for (List<TestFitnessFunction> goals : goalsByMethod.values()) {
            mergeGoals(flattened, goals);
        }
        return flattened;
    }

    private int firstThrownPosition(ExecutionResult result) {
        try {
            Integer pos = result.getFirstPositionOfThrownException();
            return pos == null ? -1 : pos;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void recordSuccessfulTypeLifecycleSteps(Map<String, TypeBarrierSignal> signals,
                                                    Map<String, List<TestFitnessFunction>> goalsByType,
                                                    List<TestFitnessFunction> allGoals,
                                                    TestCase test,
                                                    ExecutionResult result,
                                                    int firstThrownPosition,
                                                    ExtractorTelemetry telemetry,
                                                    boolean traceEnabled,
                                                    int chromosomeIndex) {
        int executedStatements = result == null ? 0 : result.getExecutedStatements();
        int safeSize = safeTestSize(test);
        if (safeSize <= 0) {
            return;
        }
        if (executedStatements <= 0 || executedStatements > safeSize) {
            executedStatements = safeSize;
        }
        int upperExclusive = firstThrownPosition >= 0
                ? Math.min(executedStatements, firstThrownPosition)
                : executedStatements;
        for (int i = 0; i < upperExclusive; i++) {
            Statement statement = statementAt(test, i);
            if (statement == null) {
                continue;
            }
            if (statement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) statement;
                String observedTypeName = constructorType(constructorStatement);
                if (observedTypeName.isEmpty()) {
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, i, observedTypeName, goalsByType, telemetry, traceEnabled,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_unmapped_successful_constructor observed_type={}",
                            chromosomeIndex, i, observedTypeName);
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                boolean meaningfulProgress = hasMeaningfulProgressAfterStatement(test, i, upperExclusive,
                        blockedTypeName, goalsByType);
                signal.recordSuccessfulConstructor(
                        goalDescriptionMapper.describeConstructorOperation(constructorStatement).getDisplayLabel(),
                        meaningfulProgress);
                traceExtractor(traceEnabled, "type_barriers",
                        "test={} stmt={} action=successful_constructor label={} observed_type={} blocked_type={} "
                                + "effective_type={} related_goals={} meaningful_progress={}",
                        chromosomeIndex, i, statementDebugLabel(constructorStatement), observedTypeName,
                        blockedTypeName, blockedTypeName, signal.relatedGoals.size(), meaningfulProgress);
                continue;
            }
            if (statement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) statement;
                String observedTypeName = thrownTypeForMethod(methodStatement);
                if (observedTypeName.isEmpty()) {
                    continue;
                }
                String blockedTypeName = blockedTypeForStatement(
                        test, i, observedTypeName, goalsByType, telemetry, traceEnabled,
                        "type_barriers", chromosomeIndex);
                if (blockedTypeName.isEmpty()) {
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=skip_unmapped_successful_method observed_type={} label={}",
                            chromosomeIndex, i, observedTypeName, statementDebugLabel(methodStatement));
                    continue;
                }
                TypeBarrierSignal signal = typeBarrierSignalFor(
                        signals, goalsByType, allGoals, test, blockedTypeName);
                if (isFactoryMethod(methodStatement)) {
                    boolean meaningfulProgress = hasMeaningfulProgressAfterStatement(test, i, upperExclusive,
                            blockedTypeName, goalsByType);
                    signal.recordSuccessfulFactory(
                            goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel(),
                            meaningfulProgress);
                    traceExtractor(traceEnabled, "type_barriers",
                            "test={} stmt={} action=successful_factory label={} observed_type={} blocked_type={} "
                                    + "effective_type={} related_goals={} meaningful_progress={}",
                            chromosomeIndex, i, statementDebugLabel(methodStatement), observedTypeName,
                            blockedTypeName, blockedTypeName, signal.relatedGoals.size(), meaningfulProgress);
                } else {
                    GoalDescriptionMapper.OperationTarget setupOperation =
                            goalDescriptionMapper.describeMethodOperation(methodStatement);
                    boolean meaningfulSetup = isMeaningfulSuccessfulMethodForBlockedType(
                            methodStatement, blockedTypeName, goalsByType);
                    if (meaningfulSetup) {
                        signal.recordSuccessfulSetupMethod(setupMethodKey(methodStatement),
                                setupOperation.getDisplayLabel());
                    }
                        traceExtractor(traceEnabled, "type_barriers",
                                "test={} stmt={} action=successful_setup label={} observed_type={} blocked_type={} "
                                        + "effective_type={} related_goals={} meaningful_setup={}",
                                chromosomeIndex, i, statementDebugLabel(methodStatement), observedTypeName,
                                blockedTypeName, blockedTypeName, signal.relatedGoals.size(), meaningfulSetup);
                }
            }
        }
    }

    private TypeBarrierSignal typeBarrierSignalFor(Map<String, TypeBarrierSignal> signals,
                                                   Map<String, List<TestFitnessFunction>> goalsByType,
                                                   List<TestFitnessFunction> allGoals,
                                                   TestCase test,
                                                   String typeName) {
        TypeBarrierSignal signal = signals.computeIfAbsent(typeName, TypeBarrierSignal::new);
        signal.addRelatedGoals(goalsByType.get(typeName));
        signal.addRelatedGoals(goalsReferencedByTest(goalsByType, test));
        if (signal.relatedGoals.isEmpty()) {
            signal.addRelatedGoals(allGoals);
        }
        return signal;
    }

    private List<TestFitnessFunction> goalsReferencedByTest(Map<String, List<TestFitnessFunction>> goalsByType,
                                                            TestCase test) {
        List<TestFitnessFunction> referenced = new ArrayList<>();
        int safeSize = safeTestSize(test);
        for (int i = 0; i < safeSize; i++) {
            Statement statement = statementAt(test, i);
            if (statement == null) {
                continue;
            }
            if (statement instanceof ConstructorStatement) {
                mergeGoals(referenced, goalsByType.get(constructorType((ConstructorStatement) statement)));
                continue;
            }
            if (statement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) statement;
                mergeGoals(referenced, goalsByType.get(thrownTypeForMethod(methodStatement)));
                mergeGoals(referenced, goalsByType.get(declaringTypeForMethod(methodStatement)));
            }
        }
        return referenced;
    }

    private String blockedTypeForStatement(TestCase test,
                                           int statementIndex,
                                           String observedTypeName,
                                           Map<String, List<TestFitnessFunction>> goalsByType,
                                           ExtractorTelemetry telemetry,
                                           boolean traceEnabled,
                                           String tracePhase,
                                           int chromosomeIndex) {
        if (hasGoalsForType(goalsByType, observedTypeName)) {
            traceExtractor(traceEnabled, tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=true mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, observedTypeName);
            return observedTypeName;
        }
        String downstreamType = firstGoalBearingTypeAfter(test, statementIndex, goalsByType);
        if (!downstreamType.isEmpty()) {
            traceExtractor(traceEnabled, tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=false "
                            + "downstream_type={} mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, downstreamType, downstreamType);
            return downstreamType;
        }
        String referencedType = firstGoalBearingTypeInTest(test, goalsByType);
        if (!referencedType.isEmpty()) {
            traceExtractor(traceEnabled, tracePhase,
                    "test={} stmt={} action=map_blocked_type observed_type={} observed_has_goals=false "
                            + "referenced_type={} mapped_type={}",
                    chromosomeIndex, statementIndex, observedTypeName, referencedType, referencedType);
            return referencedType;
        }
        if (telemetry != null && observedTypeName != null && !observedTypeName.isEmpty()) {
            telemetry.increment(ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE);
        }
        traceExtractor(traceEnabled, tracePhase,
                "test={} stmt={} action=map_blocked_type_failure observed_type={} observed_has_goals=false mapped_type={}",
                chromosomeIndex, statementIndex, observedTypeName, "");
        return "";
    }

    private String firstGoalBearingTypeAfter(TestCase test,
                                             int statementIndex,
                                             Map<String, List<TestFitnessFunction>> goalsByType) {
        int safeSize = safeTestSize(test);
        for (int i = Math.max(0, statementIndex + 1); i < safeSize; i++) {
            String goalType = goalBearingTypeForStatement(statementAt(test, i), goalsByType);
            if (!goalType.isEmpty()) {
                return goalType;
            }
        }
        return "";
    }

    private String firstGoalBearingTypeInTest(TestCase test,
                                              Map<String, List<TestFitnessFunction>> goalsByType) {
        int safeSize = safeTestSize(test);
        for (int i = 0; i < safeSize; i++) {
            String goalType = goalBearingTypeForStatement(statementAt(test, i), goalsByType);
            if (!goalType.isEmpty()) {
                return goalType;
            }
        }
        return "";
    }

    private String goalBearingTypeForStatement(Statement statement,
                                               Map<String, List<TestFitnessFunction>> goalsByType) {
        if (statement instanceof ConstructorStatement) {
            String typeName = constructorType((ConstructorStatement) statement);
            return hasGoalsForType(goalsByType, typeName) ? typeName : "";
        }
        if (statement instanceof MethodStatement) {
            MethodStatement methodStatement = (MethodStatement) statement;
            String receiverType = thrownTypeForMethod(methodStatement);
            if (hasGoalsForType(goalsByType, receiverType)) {
                return receiverType;
            }
            String declaringType = declaringTypeForMethod(methodStatement);
            if (hasGoalsForType(goalsByType, declaringType)) {
                return declaringType;
            }
        }
        return "";
    }

    private boolean hasGoalsForType(Map<String, List<TestFitnessFunction>> goalsByType, String typeName) {
        return goalsByType != null
                && typeName != null
                && !typeName.isEmpty()
                && goalsByType.containsKey(typeName)
                && goalsByType.get(typeName) != null
                && !goalsByType.get(typeName).isEmpty();
    }

    private void mergeGoals(List<TestFitnessFunction> destination,
                            List<TestFitnessFunction> goals) {
        if (destination == null || goals == null || goals.isEmpty()) {
            return;
        }
        for (TestFitnessFunction goal : goals) {
            if (goal != null && !destination.contains(goal)) {
                destination.add(goal);
            }
        }
    }

    private List<TestFitnessFunction> deduplicateGoals(List<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestFitnessFunction> deduplicated = new ArrayList<>();
        mergeGoals(deduplicated, goals);
        return deduplicated;
    }

    private int safeTestSize(TestCase test) {
        try {
            return test == null ? 0 : test.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean hasMeaningfulProgressAfterStatement(TestCase test,
                                                        int statementIndex,
                                                        int upperExclusive,
                                                        String blockedTypeName,
                                                        Map<String, List<TestFitnessFunction>> goalsByType) {
        if (statementIndex < 0 || test == null || blockedTypeName == null || blockedTypeName.isEmpty()) {
            return false;
        }
        int safeUpper = Math.max(0, Math.min(upperExclusive, safeTestSize(test)));
        for (int i = statementIndex + 1; i < safeUpper; i++) {
            Statement next = statementAt(test, i);
            if (!(next instanceof MethodStatement)) {
                continue;
            }
            if (isMeaningfulSuccessfulMethodForBlockedType((MethodStatement) next, blockedTypeName, goalsByType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningfulSuccessfulMethodForBlockedType(MethodStatement methodStatement,
                                                               String blockedTypeName,
                                                               Map<String, List<TestFitnessFunction>> goalsByType) {
        if (methodStatement == null || blockedTypeName == null || blockedTypeName.isEmpty()) {
            return false;
        }
        String receiverType = thrownTypeForMethod(methodStatement);
        if (blockedTypeName.equals(receiverType)) {
            return true;
        }
        String declaringType = declaringTypeForMethod(methodStatement);
        if (blockedTypeName.equals(declaringType)) {
            return true;
        }
        String goalBearingType = goalBearingTypeForStatement(methodStatement, goalsByType);
        return blockedTypeName.equals(goalBearingType);
    }

    private Statement statementAt(TestCase test, int position) {
        if (test == null || position < 0) {
            return null;
        }
        try {
            if (!test.hasStatement(position)) {
                return null;
            }
            return test.getStatement(position);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String constructorType(ConstructorStatement statement) {
        if (statement == null || statement.getConstructor() == null
                || statement.getConstructor().getDeclaringClass() == null) {
            return "";
        }
        return statement.getConstructor().getDeclaringClass().getName();
    }

    private String thrownTypeForMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return "";
        }
        if (isFactoryMethod(statement)) {
            return statement.getMethod().getMethod().getDeclaringClass().getName();
        }
        if (statement.getCallee() != null && statement.getCallee().getVariableClass() != null) {
            return statement.getCallee().getVariableClass().getName();
        }
        Class<?> declaringClass = statement.getMethod().getMethod().getDeclaringClass();
        return declaringClass == null ? "" : declaringClass.getName();
    }

    private String directMethodKey(MethodStatement statement) {
        return goalDescriptionMapper.describeMethodOperation(statement).getExecutionKey();
    }

    private String coveredMethodKey(String coveredMethod) {
        return goalDescriptionMapper.describeQualifiedMethodOperation(coveredMethod).getExecutionKey();
    }

    private String declaringTypeForMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return "";
        }
        Class<?> declaringClass = statement.getMethod().getMethod().getDeclaringClass();
        return declaringClass == null ? "" : declaringClass.getName();
    }

    private boolean shouldTraceExtractorForCurrentTarget() {
        return DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger);
    }

    private void traceExtractor(boolean enabled, String phase, String message, Object... args) {
        if (!enabled) {
            return;
        }
        Object[] prefixed = new Object[args.length + 2];
        prefixed[0] = Properties.TARGET_CLASS;
        prefixed[1] = phase;
        System.arraycopy(args, 0, prefixed, 2, args.length);
        logger.warn("extractor_trace target={} phase={} " + message, prefixed);
    }

    private void traceExtractionSummary(boolean enabled,
                                        Map<String, List<TestFitnessFunction>> goalsByMethod,
                                        Set<String> coveredMethods,
                                        Map<String, AttemptStats> methodStats,
                                        Map<String, TypeBarrierSignal> typeBarrierSignals,
                                        Map<String, OuterTypeAttemptSignal> outerTypeSignals,
                                        List<ProblemCard> cards,
                                        ExtractorTelemetry telemetry) {
        if (!enabled) {
            return;
        }
        Map<ExtractorCandidateMetric, Integer> candidateCounts = telemetry == null
                ? Collections.<ExtractorCandidateMetric, Integer>emptyMap()
                : telemetry.snapshotCandidateCounts();
        Map<ExtractorRejectReason, Integer> rejectCounts = telemetry == null
                ? Collections.<ExtractorRejectReason, Integer>emptyMap()
                : telemetry.snapshotRejectCounts();
        traceExtractor(true, "extraction_summary",
                "goal_methods={} covered_methods={} attempted_goal_methods={} type_signals={} "
                        + "outer_type_signals={} extracted_count={} extracted_by_type={} extracted_top={} "
                        + "extractor_candidates={} extractor_rejects={}",
                goalsByMethod == null ? 0 : goalsByMethod.size(),
                coveredMethods == null ? 0 : coveredMethods.size(),
                countAttemptedGoalMethods(methodStats),
                typeBarrierSignals == null ? 0 : typeBarrierSignals.size(),
                outerTypeSignals == null ? 0 : outerTypeSignals.size(),
                cards == null ? 0 : cards.size(),
                summarizeCardTypes(cards),
                summarizeCards(cards, 4),
                candidateCounts.isEmpty() ? "{}" : candidateCounts.toString(),
                rejectCounts.isEmpty() ? "{}" : rejectCounts.toString());
    }

    private int countAttemptedGoalMethods(Map<String, AttemptStats> methodStats) {
        if (methodStats == null || methodStats.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (AttemptStats stats : methodStats.values()) {
            if (stats != null && stats.attempts > 0) {
                count++;
            }
        }
        return count;
    }

    private String summarizeCardTypes(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "{}";
        }
        EnumMap<ProblemCardType, Integer> counts = new EnumMap<>(ProblemCardType.class);
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            counts.put(card.getType(), counts.getOrDefault(card.getType(), 0) + 1);
        }
        return counts.toString();
    }

    private String summarizeCards(List<ProblemCard> cards, int maxCards) {
        if (cards == null || cards.isEmpty() || maxCards <= 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int emitted = 0;
        for (ProblemCard card : cards) {
            if (card == null) {
                continue;
            }
            if (emitted++ >= maxCards) {
                break;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(card.getType())
                    .append("@")
                    .append(String.format("%.3f", card.getPriority()));
        }
        sb.append("]");
        return sb.toString();
    }

    private String statementDebugKind(Statement statement) {
        return statement == null ? "null" : statement.getClass().getSimpleName();
    }

    private String statementDebugLabel(Statement statement) {
        if (statement instanceof MethodStatement) {
            return goalDescriptionMapper.describeMethodOperation((MethodStatement) statement).getDisplayLabel();
        }
        if (statement instanceof ConstructorStatement) {
            return goalDescriptionMapper.describeConstructorOperation((ConstructorStatement) statement).getDisplayLabel();
        }
        return statementDebugKind(statement);
    }

    private boolean isFactoryMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return false;
        }
        if (!statement.getMethod().isStatic()) {
            return false;
        }
        Class<?> declaring = statement.getMethod().getMethod().getDeclaringClass();
        Class<?> returned = statement.getMethod().getMethod().getReturnType();
        return declaring != null && returned != null && declaring.isAssignableFrom(returned);
    }

    private String setupMethodKey(MethodStatement statement) {
        return goalDescriptionMapper.describeMethodOperation(statement).getExecutionKey();
    }

    private void addBootstrapDependencyEvidence(List<String> evidence, String blockedType, String entryPointLabel) {
        if (evidence == null || blockedType == null || blockedType.isEmpty()
                || entryPointLabel == null || entryPointLabel.isEmpty()) {
            return;
        }
        if (!entryPointLabel.startsWith(blockedType + ".") && !entryPointLabel.startsWith(blockedType + "(")) {
            evidence.add("Observed bootstrap failures came from dependency/helper entry points before reaching "
                    + blockedType + ".");
        }
    }

    private boolean isLikelyIndirectHelperType(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }
        int idx = typeName.lastIndexOf('$');
        if (idx < 0 || idx >= typeName.length() - 1) {
            return false;
        }
        String suffix = typeName.substring(idx + 1);
        return suffix.matches("\\d+.*");
    }

    private String outerTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        int idx = typeName.indexOf('$');
        if (idx <= 0) {
            return typeName;
        }
        return typeName.substring(0, idx);
    }

    private MethodPromptContext describeMethodContext(String methodKey, List<TestFitnessFunction> relatedGoals) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        String typeName = "";
        if (relatedGoals != null) {
            for (TestFitnessFunction goal : relatedGoals) {
                GoalDescriptionMapper.OperationTarget target = goalDescriptionMapper.describeMethodOperation(goal);
                if (!target.getDisplayLabel().isEmpty()) {
                    labels.add(target.getDisplayLabel());
                }
                if (typeName.isEmpty()) {
                    typeName = target.getClassName();
                }
            }
        }
        if (typeName.isEmpty() && methodKey != null) {
            int idx = methodKey.lastIndexOf('.');
            if (idx > 0) {
                typeName = methodKey.substring(0, idx);
            }
        }
        String displayLabel = labels.size() == 1 ? labels.iterator().next()
                : (methodKey == null ? "" : methodKey);
        return new MethodPromptContext(methodKey, displayLabel, typeName, new ArrayList<>(labels));
    }

    private void addOverloadEvidence(List<String> evidence, MethodPromptContext methodContext) {
        if (evidence == null || methodContext == null || methodContext.overloadLabels.size() <= 1) {
            return;
        }
        evidence.add("Grouped uncovered overloads: " + String.join(", ", methodContext.overloadLabels) + ".");
    }

    private String exceptionKey(ExecutionResult result) {
        try {
            Integer pos = result.getFirstPositionOfThrownException();
            if (pos == null) {
                return "";
            }
            Throwable thrown = result.getExceptionThrownAtPosition(pos);
            Throwable root = rootCause(thrown);
            return root == null ? "" : root.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String dominantException(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        String best = "";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        int safety = 0;
        while (current.getCause() != null && current.getCause() != current && safety++ < 32) {
            current = current.getCause();
        }
        return current;
    }

    private double normalizeCount(double count, double divisor) {
        if (Double.isNaN(count) || Double.isInfinite(count) || divisor <= 0.0) {
            return 0.0;
        }
        if (count <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, count / divisor);
    }

    private double barrierConfidence(int attempts) {
        if (attempts <= 0) {
            return 0.0;
        }
        return 1.0 - Math.pow(0.5, Math.max(0, attempts - 1));
    }

    private double leverageAwareImpact(Collection<TestFitnessFunction> relatedGoals, double goalDivisor) {
        double impact = normalizeCount(relatedGoals == null ? 0 : relatedGoals.size(), goalDivisor);
        int distinctMethods = distinctGoalMethods(relatedGoals);
        int distinctTypes = distinctGoalTypes(relatedGoals);
        impact += 0.20 * normalizeCount(Math.max(0, distinctMethods - 1), 3.0);
        impact += 0.15 * normalizeCount(Math.max(0, distinctTypes - 1), 2.0);
        return Math.min(1.0, impact);
    }

    private int distinctGoalMethods(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return 0;
        }
        Set<String> methods = new LinkedHashSet<>();
        for (TestFitnessFunction goal : goals) {
            String key = methodKey(goal);
            if (key != null && !key.isEmpty()) {
                methods.add(key);
            }
        }
        return methods.size();
    }

    private int distinctGoalTypes(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return 0;
        }
        Set<String> types = new LinkedHashSet<>();
        for (TestFitnessFunction goal : goals) {
            GoalDescriptionMapper.OperationTarget target = goalDescriptionMapper.describeMethodOperation(goal);
            if (target.getClassName() != null && !target.getClassName().isEmpty()) {
                types.add(target.getClassName());
            }
        }
        return types.size();
    }

    private Map<String, TypeInvocationSignal> collectTypeInvocationSignals(
            Map<String, List<TestFitnessFunction>> goalsByMethod,
            Map<String, AttemptStats> statsByMethod) {
        Map<String, TypeInvocationSignal> signals = new LinkedHashMap<>();
        if (goalsByMethod == null || goalsByMethod.isEmpty()) {
            return signals;
        }
        for (Map.Entry<String, List<TestFitnessFunction>> entry : goalsByMethod.entrySet()) {
            String methodKey = entry.getKey();
            MethodPromptContext context = describeMethodContext(methodKey, entry.getValue());
            if (context.typeName.isEmpty()) {
                continue;
            }
            TypeInvocationSignal signal = signals.computeIfAbsent(context.typeName,
                    ignored -> new TypeInvocationSignal(context.typeName));
            signal.recordMethod(methodKey, context.displayLabel, entry.getValue(), statsByMethod.get(methodKey));
        }
        return signals;
    }

    private StateDiversificationContextEvidence selectStateDiversificationContext(AttemptStats stats) {
        if (stats == null || stats.contextStats.isEmpty()) {
            return null;
        }
        StateDiversificationContextEvidence best = null;
        for (ContextAttemptStats contextStats : stats.contextStats.values()) {
            if (contextStats == null || contextStats.successes <= 0 || contextStats.exceptions > 0) {
                continue;
            }
            StateDiversificationContextEvidence candidate = new StateDiversificationContextEvidence(
                    contextStats.contextKey, contextStats.contextLabel, contextStats.successes);
            if (best == null || candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private int countSuccessfulContexts(AttemptStats stats) {
        if (stats == null || stats.contextStats.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ContextAttemptStats contextStats : stats.contextStats.values()) {
            if (contextStats != null && contextStats.successes > 0) {
                count++;
            }
        }
        return count;
    }

    private String rootCauseKeyForType(String typeName, String fallback) {
        if (typeName != null && !typeName.isEmpty()) {
            return typeName;
        }
        return fallback == null ? "" : fallback;
    }

    private Set<Integer> safeSet(Set<Integer> input) {
        return input == null ? Collections.emptySet() : input;
    }

    public static final class ExtractionResult {
        private static final ExtractionResult EMPTY =
                new ExtractionResult(Collections.<ProblemCard>emptyList(),
                        Collections.<ExtractorRejectReason, Integer>emptyMap(),
                        Collections.<ExtractorCandidateMetric, Integer>emptyMap());

        private final List<ProblemCard> cards;
        private final Map<ExtractorRejectReason, Integer> rejectCounts;
        private final Map<ExtractorCandidateMetric, Integer> candidateCounts;

        ExtractionResult(List<ProblemCard> cards,
                         Map<ExtractorRejectReason, Integer> rejectCounts,
                         Map<ExtractorCandidateMetric, Integer> candidateCounts) {
            this.cards = cards == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(cards));
            this.rejectCounts = rejectCounts == null || rejectCounts.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new EnumMap<>(rejectCounts));
            this.candidateCounts = candidateCounts == null || candidateCounts.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new EnumMap<>(candidateCounts));
        }

        public List<ProblemCard> getCards() {
            return cards;
        }

        public Map<ExtractorRejectReason, Integer> getRejectCounts() {
            return rejectCounts;
        }

        public Map<ExtractorCandidateMetric, Integer> getCandidateCounts() {
            return candidateCounts;
        }

        static ExtractionResult empty() {
            return EMPTY;
        }
    }

    private static final class ExtractorTelemetry {
        private final EnumMap<ExtractorRejectReason, Integer> rejectCounts =
                new EnumMap<>(ExtractorRejectReason.class);
        private final EnumMap<ExtractorCandidateMetric, Integer> candidateCounts =
                new EnumMap<>(ExtractorCandidateMetric.class);
        private final Map<String, Integer> upstreamExceptionObservations = new LinkedHashMap<>();
        private final Set<String> upstreamBlockedGoalMethods = new LinkedHashSet<>();

        void increment(ExtractorRejectReason reason) {
            if (reason == null) {
                return;
            }
            rejectCounts.put(reason, rejectCounts.getOrDefault(reason, 0) + 1);
        }

        void increment(ExtractorCandidateMetric metric) {
            if (metric == null) {
                return;
            }
            candidateCounts.put(metric, candidateCounts.getOrDefault(metric, 0) + 1);
        }

        void recordUpstreamExceptionObservation(String executionKey) {
            if (executionKey == null || executionKey.isEmpty()) {
                return;
            }
            upstreamExceptionObservations.put(executionKey,
                    upstreamExceptionObservations.getOrDefault(executionKey, 0) + 1);
        }

        void recordUpstreamBlockedGoalMethods(Collection<String> blockedGoalMethods) {
            if (blockedGoalMethods == null || blockedGoalMethods.isEmpty()) {
                return;
            }
            for (String blockedGoalMethod : blockedGoalMethods) {
                if (blockedGoalMethod != null && !blockedGoalMethod.isEmpty()) {
                    upstreamBlockedGoalMethods.add(blockedGoalMethod);
                }
            }
        }

        Map<ExtractorRejectReason, Integer> snapshotRejectCounts() {
            return rejectCounts.isEmpty()
                    ? Collections.<ExtractorRejectReason, Integer>emptyMap()
                    : new EnumMap<>(rejectCounts);
        }

        Map<ExtractorCandidateMetric, Integer> snapshotCandidateCounts() {
            EnumMap<ExtractorCandidateMetric, Integer> snapshot =
                    new EnumMap<>(ExtractorCandidateMetric.class);
            snapshot.putAll(candidateCounts);
            int repeatedSources = 0;
            for (Integer observationCount : upstreamExceptionObservations.values()) {
                if (observationCount != null && observationCount >= MIN_ATTEMPTS_FOR_EXCEPTION_BARRIER) {
                    repeatedSources++;
                }
            }
            if (repeatedSources > 0) {
                snapshot.put(ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES, repeatedSources);
            }
            if (!upstreamBlockedGoalMethods.isEmpty()) {
                snapshot.put(ExtractorCandidateMetric.UPSTREAM_EXCEPTION_BLOCKED_GOAL_METHODS,
                        upstreamBlockedGoalMethods.size());
            }
            return snapshot.isEmpty()
                    ? Collections.<ExtractorCandidateMetric, Integer>emptyMap()
                    : snapshot;
        }
    }

    private static final class AttemptStats {
        int attempts;
        int successes;
        int exceptions;
        int coveredInFailingTests;
        final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        final Map<String, ContextAttemptStats> contextStats = new LinkedHashMap<>();
        final Map<String, UpstreamAttemptStats> upstreamStats = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
            if (label != null && !label.isEmpty()) {
                failingInvocationLabelCounts.put(label,
                        failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
        }

        String dominantFailingInvocationLabel() {
            return dominantLabel(failingInvocationLabelCounts);
        }

        ContextAttemptStats contextStats(InvocationContextClassifier.InvocationContext context) {
            InvocationContextClassifier.InvocationContext effective = context == null
                    ? InvocationContextClassifier.DEFAULT_CONTEXT
                    : context;
            return contextStats.computeIfAbsent(effective.getKey(),
                    ignored -> new ContextAttemptStats(effective.getKey(), effective.getLabel()));
        }

        UpstreamAttemptStats upstreamStats(String blockerExecutionKey, String blockerDisplayLabel) {
            if (blockerExecutionKey == null || blockerExecutionKey.isEmpty()) {
                return new UpstreamAttemptStats("", blockerDisplayLabel);
            }
            return upstreamStats.computeIfAbsent(blockerExecutionKey,
                    ignored -> new UpstreamAttemptStats(blockerExecutionKey, blockerDisplayLabel));
        }
    }

    private static final class ContextAttemptStats {
        final String contextKey;
        final String contextLabel;
        int attempts;
        int successes;
        int exceptions;
        final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        ContextAttemptStats(String contextKey, String contextLabel) {
            this.contextKey = contextKey;
            this.contextLabel = contextLabel;
        }

        void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
            if (label != null && !label.isEmpty()) {
                failingInvocationLabelCounts.put(label,
                        failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
        }

        String dominantFailingInvocationLabel() {
            return dominantLabel(failingInvocationLabelCounts);
        }
    }

    private static final class UpstreamAttemptStats {
        final String blockerExecutionKey;
        String blockerDisplayLabel;
        int attempts;
        int successes;
        int exceptions;
        final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
        final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
        int failingInvocationsWithSuccessfulPrefix;

        UpstreamAttemptStats(String blockerExecutionKey, String blockerDisplayLabel) {
            this.blockerExecutionKey = blockerExecutionKey == null ? "" : blockerExecutionKey;
            this.blockerDisplayLabel = blockerDisplayLabel == null ? "" : blockerDisplayLabel;
        }

        void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
            if (label != null && !label.isEmpty()) {
                failingInvocationLabelCounts.put(label,
                        failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
                if (blockerDisplayLabel == null || blockerDisplayLabel.isEmpty()) {
                    blockerDisplayLabel = label;
                }
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
        }

        String dominantFailingInvocationLabel() {
            return dominantLabel(failingInvocationLabelCounts);
        }
    }

    private static final class ExceptionBarrierContextEvidence {
        private final String contextKey;
        private final String contextLabel;
        private int attempts;
        private int successes;
        private int exceptions;
        private String dominantException;
        private String dominantInvocationLabel;
        private int failingInvocationsWithSuccessfulPrefix;
        private final boolean methodLevel;

        ExceptionBarrierContextEvidence(String contextKey,
                                        String contextLabel,
                                        int attempts,
                                        int successes,
                                        int exceptions,
                                        String dominantException,
                                        String dominantInvocationLabel,
                                        int failingInvocationsWithSuccessfulPrefix,
                                        boolean methodLevel) {
            this.contextKey = contextKey;
            this.contextLabel = contextLabel;
            this.attempts = attempts;
            this.successes = successes;
            this.exceptions = exceptions;
            this.dominantException = dominantException == null ? "" : dominantException;
            this.dominantInvocationLabel = dominantInvocationLabel == null ? "" : dominantInvocationLabel;
            this.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
            this.methodLevel = methodLevel;
        }

        void add(int attempts,
                 int successes,
                 int exceptions,
                 String dominantException,
                 String dominantInvocationLabel,
                 int failingInvocationsWithSuccessfulPrefix) {
            this.attempts += Math.max(0, attempts);
            this.successes += Math.max(0, successes);
            this.exceptions += Math.max(0, exceptions);
            this.failingInvocationsWithSuccessfulPrefix += Math.max(0, failingInvocationsWithSuccessfulPrefix);
            if ((this.dominantException == null || this.dominantException.isEmpty())
                    && dominantException != null && !dominantException.isEmpty()) {
                this.dominantException = dominantException;
            }
            if ((this.dominantInvocationLabel == null || this.dominantInvocationLabel.isEmpty())
                    && dominantInvocationLabel != null && !dominantInvocationLabel.isEmpty()) {
                this.dominantInvocationLabel = dominantInvocationLabel;
            }
        }

        boolean isMethodLevel() {
            return methodLevel;
        }

        String getContextKey() {
            return contextKey;
        }

        String getContextLabel() {
            return contextLabel;
        }

        int getAttempts() {
            return attempts;
        }

        int getSuccesses() {
            return successes;
        }

        int getExceptions() {
            return exceptions;
        }

        double getBlockage() {
            return attempts <= 0 ? 0.0 : ((double) exceptions / (double) attempts);
        }

        String getDominantException() {
            return dominantException;
        }

        String getDominantInvocationLabel() {
            return dominantInvocationLabel;
        }

        int getFailingInvocationsWithSuccessfulPrefix() {
            return failingInvocationsWithSuccessfulPrefix;
        }

        boolean isStrongerThan(ExceptionBarrierContextEvidence other) {
            if (other == null) {
                return true;
            }
            int exceptionCompare = Integer.compare(exceptions, other.exceptions);
            if (exceptionCompare != 0) {
                return exceptionCompare > 0;
            }
            int blockageCompare = Double.compare(getBlockage(), other.getBlockage());
            if (blockageCompare != 0) {
                return blockageCompare > 0;
            }
            int attemptsCompare = Integer.compare(attempts, other.attempts);
            if (attemptsCompare != 0) {
                return attemptsCompare > 0;
            }
            return contextKey.compareTo(other.contextKey) < 0;
        }
    }

    private static final class UpstreamExceptionBarrierEvidence {
        private final String blockerExecutionKey;
        private String blockerDisplayLabel;
        private int attempts;
        private int successes;
        private int exceptions;
        private String dominantException;
        private String dominantInvocationLabel;
        private int failingInvocationsWithSuccessfulPrefix;

        UpstreamExceptionBarrierEvidence(String blockerExecutionKey,
                                         String blockerDisplayLabel,
                                         int attempts,
                                         int successes,
                                         int exceptions,
                                         String dominantException,
                                         String dominantInvocationLabel,
                                         int failingInvocationsWithSuccessfulPrefix) {
            this.blockerExecutionKey = blockerExecutionKey == null ? "" : blockerExecutionKey;
            this.blockerDisplayLabel = blockerDisplayLabel == null ? "" : blockerDisplayLabel;
            this.attempts = attempts;
            this.successes = successes;
            this.exceptions = exceptions;
            this.dominantException = dominantException == null ? "" : dominantException;
            this.dominantInvocationLabel = dominantInvocationLabel == null ? "" : dominantInvocationLabel;
            this.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
        }

        void add(int attempts,
                 int successes,
                 int exceptions,
                 String dominantException,
                 String dominantInvocationLabel,
                 int failingInvocationsWithSuccessfulPrefix,
                 String blockerDisplayLabel) {
            this.attempts += Math.max(0, attempts);
            this.successes += Math.max(0, successes);
            this.exceptions += Math.max(0, exceptions);
            this.failingInvocationsWithSuccessfulPrefix += Math.max(0, failingInvocationsWithSuccessfulPrefix);
            if ((this.dominantException == null || this.dominantException.isEmpty())
                    && dominantException != null && !dominantException.isEmpty()) {
                this.dominantException = dominantException;
            }
            if ((this.dominantInvocationLabel == null || this.dominantInvocationLabel.isEmpty())
                    && dominantInvocationLabel != null && !dominantInvocationLabel.isEmpty()) {
                this.dominantInvocationLabel = dominantInvocationLabel;
            }
            if ((this.blockerDisplayLabel == null || this.blockerDisplayLabel.isEmpty())
                    && blockerDisplayLabel != null && !blockerDisplayLabel.isEmpty()) {
                this.blockerDisplayLabel = blockerDisplayLabel;
            }
        }

        String getBlockerExecutionKey() {
            return blockerExecutionKey;
        }

        String getBlockerDisplayLabel() {
            return blockerDisplayLabel;
        }

        int getAttempts() {
            return attempts;
        }

        int getSuccesses() {
            return successes;
        }

        int getExceptions() {
            return exceptions;
        }

        double getBlockage() {
            return attempts <= 0 ? 0.0 : ((double) exceptions / (double) attempts);
        }

        String getDominantException() {
            return dominantException;
        }

        String getDominantInvocationLabel() {
            return dominantInvocationLabel;
        }

        int getFailingInvocationsWithSuccessfulPrefix() {
            return failingInvocationsWithSuccessfulPrefix;
        }

        boolean isStrongerThan(UpstreamExceptionBarrierEvidence other) {
            if (other == null) {
                return true;
            }
            int exceptionCompare = Integer.compare(exceptions, other.exceptions);
            if (exceptionCompare != 0) {
                return exceptionCompare > 0;
            }
            int blockageCompare = Double.compare(getBlockage(), other.getBlockage());
            if (blockageCompare != 0) {
                return blockageCompare > 0;
            }
            int attemptsCompare = Integer.compare(attempts, other.attempts);
            if (attemptsCompare != 0) {
                return attemptsCompare > 0;
            }
            return blockerExecutionKey.compareTo(other.blockerExecutionKey) < 0;
        }
    }

    private static final class OuterTypeAttemptSignal {
        final String outerTypeName;
        int attempts;
        int successes;
        int exceptions;
        final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
        final Map<String, Integer> successfulEntryPointLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> failingEntryPointLabelCounts = new LinkedHashMap<>();

        OuterTypeAttemptSignal(String outerTypeName) {
            this.outerTypeName = outerTypeName;
        }
    }

    private static final class TypeInvocationSignal {
        final String typeName;
        final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
        final Set<String> methodKeys = new LinkedHashSet<>();
        final Map<String, String> methodLabelsByKey = new LinkedHashMap<>();
        int directAttempts;

        TypeInvocationSignal(String typeName) {
            this.typeName = typeName == null ? "" : typeName;
        }

        void recordMethod(String methodKey,
                          String methodLabel,
                          Collection<TestFitnessFunction> goals,
                          AttemptStats stats) {
            if (methodKey != null && !methodKey.isEmpty()) {
                methodKeys.add(methodKey);
            }
            if (methodKey != null && !methodKey.isEmpty() && methodLabel != null && !methodLabel.isEmpty()) {
                methodLabelsByKey.put(methodKey, methodLabel);
            }
            if (goals != null) {
                for (TestFitnessFunction goal : goals) {
                    if (goal != null && !relatedGoals.contains(goal)) {
                        relatedGoals.add(goal);
                    }
                }
            }
            if (stats != null) {
                directAttempts += Math.max(0, stats.attempts);
            }
        }

        String describeBlockedMethodLabels(int maxLabels) {
            if (maxLabels <= 0 || methodKeys.isEmpty()) {
                return "";
            }
            List<String> labels = new ArrayList<>();
            for (String methodKey : methodKeys) {
                String label = methodLabelsByKey.get(methodKey);
                labels.add(label == null || label.isEmpty() ? methodKey : label);
                if (labels.size() >= maxLabels) {
                    break;
                }
            }
            return String.join(", ", labels);
        }
    }

    private static final class BranchGoalSignal {
        final String methodKey;
        final int branchId;
        final boolean desiredValue;
        final GoalDescriptionMapper.BranchTarget branchTarget;
        final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
        int desiredHits;
        int oppositeHits;
        int predicateExecutions;

        BranchGoalSignal(String methodKey,
                         int branchId,
                         boolean desiredValue,
                         GoalDescriptionMapper.BranchTarget branchTarget,
                         TestFitnessFunction firstGoal) {
            this.methodKey = methodKey;
            this.branchId = branchId;
            this.desiredValue = desiredValue;
            this.branchTarget = branchTarget;
            this.relatedGoals.add(firstGoal);
        }

        String signalKey() {
            return methodKey + "#" + branchId + "#" + desiredValue;
        }
    }

    private static final class StateDiversificationContextEvidence {
        final String contextKey;
        final String contextLabel;
        final int successes;

        StateDiversificationContextEvidence(String contextKey, String contextLabel, int successes) {
            this.contextKey = contextKey == null ? "" : contextKey;
            this.contextLabel = contextLabel == null || contextLabel.isEmpty()
                    ? InvocationContextClassifier.DEFAULT_CONTEXT.getLabel()
                    : contextLabel;
            this.successes = successes;
        }

        double successShare(int totalSuccesses) {
            if (totalSuccesses <= 0) {
                return 0.0;
            }
            return ((double) successes) / (double) totalSuccesses;
        }

        boolean isStrongerThan(StateDiversificationContextEvidence other) {
            if (other == null) {
                return true;
            }
            int successCompare = Integer.compare(successes, other.successes);
            if (successCompare != 0) {
                return successCompare > 0;
            }
            return contextKey.compareTo(other.contextKey) < 0;
        }
    }

    private static final class CdgDependencySignal {
        final String methodKey;
        final int branchId;
        final boolean desiredValue;
        final GoalDescriptionMapper.BranchTarget branchTarget;
        final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
        int desiredHits;
        int oppositeHits;
        int predicateExecutions;

        CdgDependencySignal(String methodKey,
                            int branchId,
                            boolean desiredValue,
                            GoalDescriptionMapper.BranchTarget branchTarget) {
            this.methodKey = methodKey;
            this.branchId = branchId;
            this.desiredValue = desiredValue;
            this.branchTarget = branchTarget;
        }

        String signalKey() {
            return methodKey + "#" + branchId + "#" + desiredValue;
        }
    }

    private static final class TypeBarrierSignal {
        final String typeName;
        final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
        int constructorAttempts;
        int constructorSuccesses;
        int progressedConstructorSuccesses;
        int factoryAttempts;
        int factorySuccesses;
        int progressedFactorySuccesses;
        int setupAttempts;
        final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
        final Map<String, Integer> successfulConstructorLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> successfulFactoryLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> successfulSetupMethodCounts = new HashMap<>();
        final Set<String> failingSetupMethods = new LinkedHashSet<>();
        final Map<String, Integer> failingSetupMethodCounts = new LinkedHashMap<>();
        final Map<String, String> failingSetupLabelsByKey = new LinkedHashMap<>();
        final Map<String, String> successfulMethodLabelsByKey = new LinkedHashMap<>();
        final Map<String, Integer> failingSetupLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> successfulMethodLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> failingConstructorLabelCounts = new LinkedHashMap<>();
        final Map<String, Integer> failingFactoryLabelCounts = new LinkedHashMap<>();

        TypeBarrierSignal(String typeName) {
            this.typeName = typeName;
        }

        void addRelatedGoals(Collection<TestFitnessFunction> goals) {
            if (goals == null || goals.isEmpty()) {
                return;
            }
            for (TestFitnessFunction goal : goals) {
                if (goal != null && !relatedGoals.contains(goal)) {
                    relatedGoals.add(goal);
                }
            }
        }

        void recordSuccessfulConstructor(String label, boolean progressedBeyondCreation) {
            constructorSuccesses++;
            incrementLabel(successfulConstructorLabelCounts, label);
            if (progressedBeyondCreation) {
                progressedConstructorSuccesses++;
            }
        }

        void recordFailingConstructor(String label) {
            constructorAttempts++;
            incrementLabel(failingConstructorLabelCounts, label);
        }

        void recordSuccessfulFactory(String label, boolean progressedBeyondCreation) {
            factorySuccesses++;
            incrementLabel(successfulFactoryLabelCounts, label);
            if (progressedBeyondCreation) {
                progressedFactorySuccesses++;
            }
        }

        void recordFailingFactory(String label) {
            factoryAttempts++;
            incrementLabel(failingFactoryLabelCounts, label);
        }

        void recordSuccessfulSetupMethod(String methodKey, String displayLabel) {
            if (methodKey == null || methodKey.isEmpty()) {
                return;
            }
            successfulSetupMethodCounts.put(methodKey,
                    successfulSetupMethodCounts.getOrDefault(methodKey, 0) + 1);
            if (displayLabel != null && !displayLabel.isEmpty()) {
                successfulMethodLabelsByKey.put(methodKey, displayLabel);
            }
            incrementLabel(successfulMethodLabelCounts, displayLabel);
        }

        void recordFailingSetupMethod(String methodKey, String displayLabel) {
            if (methodKey == null || methodKey.isEmpty()) {
                return;
            }
            setupAttempts++;
            failingSetupMethods.add(methodKey);
            failingSetupMethodCounts.put(methodKey, failingSetupMethodCounts.getOrDefault(methodKey, 0) + 1);
            if (displayLabel != null && !displayLabel.isEmpty()) {
                failingSetupLabelsByKey.put(methodKey, displayLabel);
            }
            incrementLabel(failingSetupLabelCounts, displayLabel);
        }

        int countSuccessfulExecutionsForFailingSetupMethods() {
            int total = 0;
            for (String methodKey : failingSetupMethods) {
                total += successfulSetupMethodCounts.getOrDefault(methodKey, 0);
            }
            return total;
        }

        int totalConstructionAttempts() {
            return constructorAttempts + factoryAttempts;
        }

        int totalConstructionSuccesses() {
            return constructorSuccesses + factorySuccesses;
        }

        int totalConstructionSuccessesWithProgress() {
            return progressedConstructorSuccesses + progressedFactorySuccesses;
        }

        int totalConstructionSuccessesWithoutProgress() {
            return Math.max(0, totalConstructionSuccesses() - totalConstructionSuccessesWithProgress());
        }

        boolean hasAcquisitionProgressBeyondCreation() {
            return totalConstructionSuccessesWithProgress() > 0;
        }

        double acquisitionFailureRate() {
            double total = totalConstructionAttempts() + totalConstructionSuccesses();
            if (total <= 0.0) {
                return 0.0;
            }
            return ((double) totalConstructionAttempts()) / total;
        }

        boolean hasSuccessfulAcquisition() {
            return totalConstructionSuccesses() > 0;
        }

        boolean hasReachabilityEvidence() {
            return hasSuccessfulAcquisition() || !successfulMethodLabelCounts.isEmpty()
                    || totalConstructionAttempts() > 0;
        }

        boolean hasReusableSuccessfulPrefix(String excludedMethodKey) {
            if (!hasAcquisitionProgressBeyondCreation() && successfulMethodLabelCounts.isEmpty()) {
                return false;
            }
            return !describeReusableSuccessfulPrefix(1, excludedMethodKey).isEmpty();
        }

        String dominantFailingAcquisitionLabel() {
            Map<String, Integer> combined = new LinkedHashMap<>();
            mergeLabelCounts(combined, failingConstructorLabelCounts);
            mergeLabelCounts(combined, failingFactoryLabelCounts);
            return dominantLabel(combined);
        }

        String dominantFailingSetupMethodKey() {
            return dominantLabel(failingSetupMethodCounts);
        }

        String dominantFailingSetupLabel() {
            String methodKey = dominantFailingSetupMethodKey();
            if (methodKey != null && !methodKey.isEmpty()) {
                String label = failingSetupLabelsByKey.get(methodKey);
                if (label != null && !label.isEmpty()) {
                    return label;
                }
            }
            return dominantLabel(failingSetupLabelCounts);
        }

        int distinctFailingSetupSteps() {
            return failingSetupMethodCounts.size();
        }

        String describeFailingSetupLabels(int maxLabels) {
            return String.join(", ", topLabels(failingSetupLabelCounts, maxLabels));
        }

        int failingExecutionsForSetupMethod(String methodKey) {
            if (methodKey == null || methodKey.isEmpty()) {
                return 0;
            }
            return failingSetupMethodCounts.getOrDefault(methodKey, 0);
        }

        int successfulExecutionsForSetupMethod(String methodKey) {
            if (methodKey == null || methodKey.isEmpty()) {
                return 0;
            }
            return successfulSetupMethodCounts.getOrDefault(methodKey, 0);
        }

        String describeReusableSuccessfulPrefix(int maxLabels, String excludedMethodKey) {
            if (maxLabels <= 0) {
                return "";
            }
            Map<String, Integer> prefixLabels = new LinkedHashMap<>();
            mergeLabelCounts(prefixLabels, successfulConstructorLabelCounts);
            mergeLabelCounts(prefixLabels, successfulFactoryLabelCounts);
            for (Map.Entry<String, Integer> entry : successfulSetupMethodCounts.entrySet()) {
                if (excludedMethodKey != null && excludedMethodKey.equals(entry.getKey())) {
                    continue;
                }
                String label = successfulMethodLabelsByKey.get(entry.getKey());
                if (label == null || label.isEmpty()) {
                    continue;
                }
                prefixLabels.put(label, prefixLabels.getOrDefault(label, 0) + entry.getValue());
            }
            return String.join(", ", topLabels(prefixLabels, maxLabels));
        }

        String prefixFingerprint(String excludedMethodKey) {
            return describeReusableSuccessfulPrefix(2, excludedMethodKey).replace(' ', '_');
        }

        String describeSuccessfulMethodLabels(int maxLabels, String excludedMethodKey) {
            if (successfulMethodLabelsByKey.isEmpty()) {
                return "";
            }
            List<String> labels = new ArrayList<>();
            for (Map.Entry<String, String> entry : successfulMethodLabelsByKey.entrySet()) {
                if (excludedMethodKey != null && excludedMethodKey.equals(entry.getKey())) {
                    continue;
                }
                String label = entry.getValue();
                if (label == null || label.isEmpty()) {
                    continue;
                }
                labels.add(label);
                if (labels.size() >= maxLabels) {
                    break;
                }
            }
            return String.join(", ", labels);
        }
    }

    private static void incrementLabel(Map<String, Integer> counts, String label) {
        if (counts == null || label == null || label.isEmpty()) {
            return;
        }
        counts.put(label, counts.getOrDefault(label, 0) + 1);
    }

    private static void mergeLabelCounts(Map<String, Integer> destination, Map<String, Integer> source) {
        if (destination == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                continue;
            }
            destination.put(entry.getKey(),
                    destination.getOrDefault(entry.getKey(), 0) + entry.getValue());
        }
    }

    private static String dominantLabel(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        String best = "";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static List<String> topLabels(Map<String, Integer> counts, int maxLabels) {
        if (counts == null || counts.isEmpty() || maxLabels <= 0) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> {
            int countCompare = Integer.compare(
                    right.getValue() == null ? 0 : right.getValue(),
                    left.getValue() == null ? 0 : left.getValue());
            if (countCompare != 0) {
                return countCompare;
            }
            String leftKey = left.getKey() == null ? "" : left.getKey();
            String rightKey = right.getKey() == null ? "" : right.getKey();
            return leftKey.compareTo(rightKey);
        });
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            String label = entry.getKey();
            if (label == null || label.isEmpty()) {
                continue;
            }
            labels.add(label);
            if (labels.size() >= maxLabels) {
                break;
            }
        }
        return labels;
    }

    private static double failureRate(int failures, int successes) {
        int total = failures + successes;
        if (total <= 0) {
            return 0.0;
        }
        return ((double) failures) / (double) total;
    }

    private static final class MethodPromptContext {
        final String executionKey;
        final String displayLabel;
        final String typeName;
        final List<String> overloadLabels;

        MethodPromptContext(String executionKey, String displayLabel, String typeName, List<String> overloadLabels) {
            this.executionKey = executionKey;
            this.displayLabel = displayLabel == null || displayLabel.isEmpty() ? executionKey : displayLabel;
            this.typeName = typeName == null ? "" : typeName;
            this.overloadLabels = overloadLabels == null ? Collections.<String>emptyList() : overloadLabels;
        }
    }
}
