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

import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExceptionBarrierCardBuilder implements CardBuilder {

    // GUARD (Phase 8/9, exception coverage): the EXCEPTION_BARRIER action hint
    // ("avoid previously failing call patterns / try guarded preconditions") is
    // written for goals blocked *by* exceptions on the path to other coverage.
    // It is WRONG for goals that *require* triggering an exception — those want
    // the failing call pattern reproduced, not avoided. When exception-coverage
    // criteria are enabled, revisit this builder: gate it off (or emit an
    // inverted hint) for goals whose target is the thrown exception itself.
    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.methodStats == null || context.methodStats.isEmpty()) {
            return;
        }
        Map<String, AttemptStats> persistent =
                context.historicalExceptionStats == null
                        ? Collections.<String, AttemptStats>emptyMap()
                        : context.historicalExceptionStats;
        for (Map.Entry<String, AttemptStats> entry : context.methodStats.entrySet()) {
            String method = entry.getKey();
            AttemptStats stats = entry.getValue();
            AttemptStats historical = persistent.get(method);
            int totalAttempts = stats == null ? 0 : stats.attempts;
            int totalSuccesses = stats == null ? 0 : stats.successes;
            int totalExceptions = stats == null ? 0 : stats.exceptions;
            int coveredInFailingTests = stats == null ? 0 : stats.coveredInFailingTests;
            if (historical != null) {
                totalAttempts += historical.attempts;
                totalSuccesses += historical.successes;
                totalExceptions += historical.exceptions;
                coveredInFailingTests += historical.coveredInFailingTests;
            }
            double blockage = totalAttempts == 0 ? 0.0 : ((double) totalExceptions / (double) totalAttempts);
            recordCandidateTelemetry(totalAttempts, totalExceptions, blockage,
                    ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES, context);
            boolean methodLevelBarrier = meetsThresholds(totalAttempts, totalExceptions, blockage, context);
            BarrierCounts contextEvidence = selectExceptionBarrierContext(stats, historical, context);
            boolean contextBarrier = contextEvidence != null
                    && !isMethodLevel(contextEvidence)
                    && meetsThresholds(contextEvidence, context);
            BarrierCounts signatureEvidence =
                    stats != null && stats.signatureStats.size() >= 2
                            ? selectExceptionBarrierSignature(stats, context)
                            : null;
            boolean signatureBarrier = signatureEvidence != null && meetsThresholds(signatureEvidence, context);
            BarrierCounts upstreamEvidence = selectUpstreamExceptionBarrier(stats, historical, context);
            boolean upstreamBarrier = !methodLevelBarrier && !contextBarrier && !signatureBarrier
                    && upstreamEvidence != null && meetsThresholds(upstreamEvidence, context);
            if (!methodLevelBarrier && !contextBarrier && !signatureBarrier && !upstreamBarrier) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = context.goalsByMethod.getOrDefault(method,
                    Collections.emptyList());
            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            boolean useSignatureAxis = signatureBarrier && signatureEvidence != null
                    && !(upstreamBarrier && upstreamEvidence != null);
            boolean useContextAxis = !useSignatureAxis
                    && !(upstreamBarrier && upstreamEvidence != null)
                    && contextEvidence != null && !isMethodLevel(contextEvidence);
            int evidenceAttempts = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.attempts
                    : useSignatureAxis ? signatureEvidence.attempts : totalAttempts;
            int evidenceExceptions = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.exceptions
                    : useSignatureAxis ? signatureEvidence.exceptions : totalExceptions;
            int evidenceSuccesses = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.successes
                    : useSignatureAxis ? signatureEvidence.successes : totalSuccesses;
            double confidence = CardBuildSupport.barrierConfidence(evidenceAttempts);
            List<String> evidence = new ArrayList<>();
            MethodPromptContext methodContext = CardBuildSupport.describeMethodContext(method, relatedGoals);
            if (upstreamBarrier && upstreamEvidence != null) {
                evidence.add("Observed direct target invocations: " + totalAttempts + ".");
                evidence.add("Direct non-exception completions: " + totalSuccesses + ".");
                evidence.add("Direct exception outcomes: " + totalExceptions + ".");
                evidence.add("Repeated upstream invocation blocked the target before it was reached: "
                        + upstreamEvidence.label + ".");
                evidence.add("Upstream invocations before the target call: " + upstreamEvidence.attempts + ".");
                evidence.add("Upstream non-exception completions before the target call: "
                        + upstreamEvidence.successes + ".");
                evidence.add("Upstream exception outcomes before the target call: "
                        + upstreamEvidence.exceptions + ".");
                evidence.add("Upstream exception dominance: "
                        + upstreamEvidence.exceptions + "/" + upstreamEvidence.attempts + ".");
            } else if (useSignatureAxis) {
                evidence.add("Method-wide direct invocations: " + totalAttempts + ".");
                evidence.add("Method-wide non-exception completions: " + totalSuccesses + ".");
                evidence.add("Method-wide exception outcomes: " + totalExceptions + ".");
                evidence.add("Blocked overload: " + signatureEvidence.label + ".");
                evidence.add("Overload-local direct invocations: " + signatureEvidence.attempts + ".");
                evidence.add("Overload-local non-exception completions: " + signatureEvidence.successes + ".");
                evidence.add("Overload-local exception outcomes: " + signatureEvidence.exceptions + ".");
                evidence.add("Overload-local exception dominance: "
                        + signatureEvidence.exceptions + "/" + signatureEvidence.attempts + ".");
            } else if (useContextAxis) {
                evidence.add("Method-wide direct invocations: " + totalAttempts + ".");
                evidence.add("Method-wide non-exception completions: " + totalSuccesses + ".");
                evidence.add("Method-wide exception outcomes: " + totalExceptions + ".");
                evidence.add("Blocked invocation context: " + contextEvidence.label + ".");
                evidence.add("Context-local direct invocations: " + contextEvidence.attempts + ".");
                evidence.add("Context-local non-exception completions: " + contextEvidence.successes + ".");
                evidence.add("Context-local exception outcomes: " + contextEvidence.exceptions + ".");
                evidence.add("Context-local exception dominance: "
                        + contextEvidence.exceptions + "/" + contextEvidence.attempts + ".");
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
                    ? upstreamEvidence.dominantException()
                    : useSignatureAxis
                    ? signatureEvidence.dominantException()
                    : contextEvidence != null
                    ? contextEvidence.dominantException()
                    : (stats == null ? "" : CardBuildSupport.dominantException(stats.exceptionTypeCounts));
            if (dominant.isEmpty() && historical != null) {
                dominant = historical.dominantException();
            }
            if (!dominant.isEmpty()) {
                evidence.add("Dominant exception: " + dominant + ".");
            }
            String dominantInvocation = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.dominantFailingInvocationLabel()
                    : useSignatureAxis
                    ? signatureEvidence.dominantFailingInvocationLabel()
                    : contextEvidence != null
                    ? contextEvidence.dominantFailingInvocationLabel()
                    : (stats == null ? "" : stats.dominantFailingInvocationLabel());
            if (!dominantInvocation.isEmpty()) {
                evidence.add("Dominant failing invocation: " + dominantInvocation + ".");
            }
            int failingInvocations = upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.exceptions
                    : useSignatureAxis ? signatureEvidence.exceptions
                    : contextEvidence != null ? contextEvidence.exceptions
                    : (stats == null ? 0 : stats.exceptions);
            int failingPrefixes = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.failingInvocationsWithSuccessfulPrefix
                    : useSignatureAxis ? signatureEvidence.failingInvocationsWithSuccessfulPrefix
                    : contextEvidence != null ? contextEvidence.failingInvocationsWithSuccessfulPrefix
                    : (stats == null ? 0 : stats.failingInvocationsWithSuccessfulPrefix);
            if (failingInvocations > 0) {
                if (failingPrefixes > 0) {
                    evidence.add("Earlier successful statements preceded the throw in "
                            + failingPrefixes + "/" + failingInvocations
                            + " directly observed failing executions.");
                } else {
                    evidence.add("Direct throws occurred without earlier successful statements "
                            + "in the same failing tests.");
                }
            }
            CardBuildSupport.addOverloadEvidence(evidence, methodContext);
            out.add(ProblemCard.builder(ProblemCardType.EXCEPTION_BARRIER)
                    .title(upstreamBarrier && upstreamEvidence != null
                            ? "Upstream invocation is exception-dominated before target call: "
                            + methodContext.displayLabel
                            : useSignatureAxis
                            ? "Overload is exception-dominated: " + signatureEvidence.label
                            : useContextAxis
                            ? "Direct invocations are exception-dominated in context: "
                            + methodContext.displayLabel + " [" + contextEvidence.label + "]"
                            : "Direct invocations are exception-dominated: " + methodContext.displayLabel)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.blockage()
                            : useSignatureAxis ? signatureEvidence.blockage()
                            : contextEvidence != null ? contextEvidence.blockage() : blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.EXECUTION)
                    .rootCauseKey(upstreamBarrier && upstreamEvidence != null
                            ? upstreamEvidence.key : method)
                    .scopeKey(method)
                    .selectionFingerprint("exception:" + method
                            + "|attempts=" + evidenceAttempts
                            + "|exceptions=" + evidenceExceptions
                            + "|successes=" + evidenceSuccesses
                            + "|dominance=" + evidenceExceptions + "/" + evidenceAttempts
                            + (upstreamBarrier && upstreamEvidence != null
                            ? "|upstream=" + upstreamEvidence.key
                            : "")
                            + (useSignatureAxis
                            ? "|signature=" + signatureEvidence.key
                            + "|signatureDominance=" + signatureEvidence.exceptions
                            + "/" + signatureEvidence.attempts
                            : "")
                            + (useContextAxis
                            ? "|context=" + contextEvidence.key
                            + "|contextDominance=" + contextEvidence.exceptions
                            + "/" + contextEvidence.attempts
                            : "")
                            + "|dominant=" + dominant)
                    .build());
        }
    }

    private void recordCandidateTelemetry(int attempts,
                                          int exceptions,
                                          double blockage,
                                          ExtractorCandidateMetric candidateMetric,
                                          CardBuildContext context) {
        if (context.telemetry == null || attempts <= 0 || exceptions <= 0) {
            return;
        }
        boolean belowAttempts = attempts < context.thresholds.minAttemptsForExceptionBarrier()
                || exceptions < context.thresholds.minAttemptsForExceptionBarrier();
        boolean belowFailureRate = blockage < context.thresholds.minFailureRateForExceptionBarrier();
        if (belowAttempts) {
            context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
        }
        if (belowFailureRate) {
            context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
        }
        if (!belowAttempts && !belowFailureRate) {
            context.telemetry.increment(candidateMetric);
        }
    }

    private static boolean meetsThresholds(int attempts, int exceptions, double blockage, CardBuildContext context) {
        return attempts >= context.thresholds.minAttemptsForExceptionBarrier()
                && exceptions >= context.thresholds.minAttemptsForExceptionBarrier()
                && blockage >= context.thresholds.minFailureRateForExceptionBarrier();
    }

    private static boolean meetsThresholds(BarrierCounts counts, CardBuildContext context) {
        return meetsThresholds(counts.attempts, counts.exceptions, counts.blockage(), context);
    }

    private static boolean isMethodLevel(BarrierCounts contextCounts) {
        return InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(contextCounts.key);
    }

    /**
     * Merges the buckets of the current population with the historical ones. The
     * dominant exception and invocation label come from the current population
     * unless it has none.
     */
    private static Map<String, BarrierCounts> mergedBuckets(Map<String, BarrierCounts> current,
                                                            Map<String, BarrierCounts> historical) {
        Map<String, BarrierCounts> merged = new LinkedHashMap<>();
        for (Map<String, BarrierCounts> source : Arrays.asList(current, historical)) {
            for (BarrierCounts bucket : source.values()) {
                merged.computeIfAbsent(bucket.key, ignored -> new BarrierCounts(bucket.key, bucket.label))
                        .combine(bucket);
            }
        }
        return merged;
    }

    /**
     * Counts the candidates in the telemetry and returns the strongest one that
     * meets the thresholds.
     */
    private BarrierCounts strongestCandidate(Iterable<BarrierCounts> candidates,
                                             ExtractorCandidateMetric candidateMetric,
                                             CardBuildContext context) {
        BarrierCounts best = null;
        for (BarrierCounts candidate : candidates) {
            recordCandidateTelemetry(candidate.attempts, candidate.exceptions, candidate.blockage(),
                    candidateMetric, context);
            if (meetsThresholds(candidate, context) && candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private BarrierCounts selectExceptionBarrierContext(
            AttemptStats current,
            AttemptStats historical,
            CardBuildContext context) {
        Map<String, BarrierCounts> merged = mergedBuckets(
                current == null ? Collections.emptyMap() : current.contextStats,
                historical == null ? Collections.emptyMap() : historical.contextStats);
        merged.remove(InvocationContextClassifier.DEFAULT_CONTEXT.getKey());
        BarrierCounts best = strongestCandidate(merged.values(),
                ExtractorCandidateMetric.EXCEPTION_BARRIER_CONTEXT_CANDIDATES, context);
        if (best != null || (current == null && historical == null)) {
            return best;
        }
        BarrierCounts methodLevel = new BarrierCounts(InvocationContextClassifier.DEFAULT_CONTEXT.getKey(),
                InvocationContextClassifier.DEFAULT_CONTEXT.getLabel());
        if (current != null) {
            methodLevel.combine(current);
        }
        if (historical != null) {
            methodLevel.combine(historical);
        }
        return methodLevel;
    }

    private BarrierCounts selectExceptionBarrierSignature(AttemptStats current, CardBuildContext context) {
        BarrierCounts best = null;
        for (BarrierCounts candidate : current.signatureStats.values()) {
            if (candidate.attempts > 0 && candidate.exceptions > 0
                    && meetsThresholds(candidate, context) && candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private BarrierCounts selectUpstreamExceptionBarrier(
            AttemptStats current,
            AttemptStats historical,
            CardBuildContext context) {
        Map<String, BarrierCounts> merged = mergedBuckets(
                current == null ? Collections.emptyMap() : current.upstreamStats,
                historical == null ? Collections.emptyMap() : historical.upstreamStats);
        return strongestCandidate(merged.values(),
                ExtractorCandidateMetric.EXCEPTION_BARRIER_UPSTREAM_CANDIDATES, context);
    }
}
