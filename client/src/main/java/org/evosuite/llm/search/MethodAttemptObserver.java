/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MethodAttemptObserver {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final ExtractorTraceSink traceSink;

    MethodAttemptObserver(ExtractorTraceSink traceSink) {
        this.traceSink = traceSink == null ? ExtractorTraceSink.NOOP : traceSink;
    }

    Map<String, AttemptStats> observe(List<TestChromosome> snapshot,
                                      Set<String> goalMethods,
                                      ExtractorTelemetry telemetry) {
        Map<String, AttemptStats> statsByMethod = new HashMap<>();
        for (String key : goalMethods) {
            statsByMethod.put(key, new AttemptStats());
        }
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
            int executedStatements = result.getExecutedStatements();
            int safeSize = ExtractorObservationSupport.safeTestSize(test);
            if (safeSize <= 0) {
                chromosomeIndex++;
                continue;
            }
            int thrownPos = ExtractorObservationSupport.firstThrownPosition(result);
            if (result.hasTestException() && thrownPos >= 0) {
                // ExecutionResult stores the index of the last fully completed statement.
                // Include the throwing statement itself so exception barriers are counted.
                executedStatements = Math.max(executedStatements, thrownPos + 1);
            }
            if (executedStatements <= 0 || executedStatements > safeSize) {
                executedStatements = safeSize;
            }
            String exceptionKey = result.hasTestException()
                    ? ExtractorObservationSupport.exceptionKey(result)
                    : "";
            traceSink.trace("method_stats",
                    "test={} executed_statements={} safe_size={} thrown_pos={} has_exception={} timeout={} exception={}",
                    chromosomeIndex, executedStatements, safeSize, thrownPos, result.hasTestException(),
                    result.hasTimeout(), exceptionKey);
            // Track goal methods directly invoked (success or otherwise) in the prefix of a failing
            // test so we don't double-credit them as "covered in a failing test" — their direct
            // outcome is already counted in successes/exceptions.
            Set<String> directlyInvokedGoalMethods = new LinkedHashSet<>();
            for (int i = 0; i < executedStatements; i++) {
                Statement statement = ExtractorObservationSupport.statementAt(test, i);
                if (!(statement instanceof MethodStatement)) {
                    traceSink.trace("method_stats",
                            "test={} stmt={} action=skip_non_method kind={}",
                            chromosomeIndex, i, ExtractorObservationSupport.statementDebugKind(statement));
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
                    traceSink.trace("method_stats",
                            "test={} stmt={} action=direct execution_key={} signature_key={} label={} "
                                    + "exception_outcome={} context={} timeout={}",
                            chromosomeIndex, i, invocation.getExecutionKey(), invocation.getSignatureKey(),
                            invocation.getDisplayLabel(),
                            exceptionOutcome, context.getKey(), result.hasTimeout());
                    ContextAttemptStats contextStats = stats.contextStats(context);
                    SignatureAttemptStats signatureStats = stats.signatureStats(
                            invocation.getSignatureKey(), invocation.getDisplayLabel());
                    stats.attempts++;
                    contextStats.attempts++;
                    signatureStats.attempts++;
                    directlyInvokedGoalMethods.add(invocation.getExecutionKey());
                    if (exceptionOutcome) {
                        stats.exceptions++;
                        contextStats.exceptions++;
                        signatureStats.exceptions++;
                        stats.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        contextStats.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        signatureStats.recordFailingInvocation(invocation.getDisplayLabel(), i > 0);
                        if (!exceptionKey.isEmpty()) {
                            stats.exceptionTypeCounts.put(exceptionKey,
                                    stats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
                            contextStats.exceptionTypeCounts.put(exceptionKey,
                                    contextStats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
                            signatureStats.exceptionTypeCounts.put(exceptionKey,
                                    signatureStats.exceptionTypeCounts.getOrDefault(exceptionKey, 0) + 1);
                        }
                    } else if (!result.hasTimeout()) {
                        stats.successes++;
                        contextStats.successes++;
                        signatureStats.successes++;
                    }
                    continue;
                }
                if (exceptionOutcome) {
                    telemetry.recordUpstreamExceptionObservation(invocation.getExecutionKey());
                }
                Set<String> downstreamGoalMethods = downstreamGoalMethodsAfter(test, i, goalMethods);
                traceSink.trace("method_stats",
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
            if (result.hasTestException()) {
                ExecutionTrace trace = result.getTrace();
                if (trace != null && trace.getCoveredMethods() != null) {
                    for (String coveredMethod : trace.getCoveredMethods()) {
                        String key = ExtractorObservationSupport.coveredMethodKey(coveredMethod);
                        if (directlyInvokedGoalMethods.contains(key)) {
                            // Direct invocations are already accounted for in attempts/exceptions/successes —
                            // re-crediting them here would inflate "covered in failing tests" with methods
                            // that actually completed without contributing to the failure.
                            continue;
                        }
                        AttemptStats stats = statsByMethod.get(key);
                        if (stats != null) {
                            stats.coveredInFailingTests++;
                        }
                    }
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
        int safeSize = ExtractorObservationSupport.safeTestSize(test);
        if (statementIndex < 0 || statementIndex >= safeSize - 1) {
            return Collections.emptySet();
        }
        Set<String> downstream = new LinkedHashSet<>();
        for (int i = statementIndex + 1; i < safeSize; i++) {
            Statement statement = ExtractorObservationSupport.statementAt(test, i);
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
}
