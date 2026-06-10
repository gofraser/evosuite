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
        Map<String, ExceptionBarrierTracker.AggregatedStats> persistent =
                context.historicalExceptionStats == null
                        ? Collections.<String, ExceptionBarrierTracker.AggregatedStats>emptyMap()
                        : context.historicalExceptionStats;
        for (Map.Entry<String, AttemptStats> entry : context.methodStats.entrySet()) {
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
            recordExceptionBarrierMethodTelemetry(totalAttempts, totalExceptions, blockage, context);
            recordExceptionBarrierContextTelemetry(stats, historical, context);
            recordExceptionBarrierUpstreamTelemetry(stats, historical, context);
            boolean methodLevelBarrier = totalAttempts >= context.thresholds.minAttemptsForExceptionBarrier()
                    && totalExceptions >= context.thresholds.minAttemptsForExceptionBarrier()
                    && blockage >= context.thresholds.minFailureRateForExceptionBarrier();
            ExceptionBarrierContextEvidence contextEvidence = selectExceptionBarrierContext(stats, historical, context);
            boolean contextBarrier = contextEvidence != null
                    && !contextEvidence.isMethodLevel()
                    && !InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(contextEvidence.getContextKey())
                    && contextEvidence.getAttempts() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && contextEvidence.getExceptions() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && contextEvidence.getBlockage() >= context.thresholds.minFailureRateForExceptionBarrier();
            ExceptionBarrierSignatureEvidence signatureEvidence =
                    stats != null && stats.signatureStats.size() >= 2
                            ? selectExceptionBarrierSignature(stats, context)
                            : null;
            boolean signatureBarrier = signatureEvidence != null
                    && signatureEvidence.getAttempts() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && signatureEvidence.getExceptions() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && signatureEvidence.getBlockage() >= context.thresholds.minFailureRateForExceptionBarrier();
            UpstreamExceptionBarrierEvidence upstreamEvidence = selectUpstreamExceptionBarrier(stats, historical, context);
            boolean upstreamBarrier = !methodLevelBarrier && !contextBarrier && !signatureBarrier
                    && upstreamEvidence != null
                    && upstreamEvidence.getAttempts() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && upstreamEvidence.getExceptions() >= context.thresholds.minAttemptsForExceptionBarrier()
                    && upstreamEvidence.getBlockage() >= context.thresholds.minFailureRateForExceptionBarrier();
            if (!methodLevelBarrier && !contextBarrier && !signatureBarrier && !upstreamBarrier) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = context.goalsByMethod.getOrDefault(method, Collections.emptyList());
            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            boolean useSignatureAxis = signatureBarrier && signatureEvidence != null
                    && !(upstreamBarrier && upstreamEvidence != null);
            boolean useContextAxis = !useSignatureAxis
                    && !(upstreamBarrier && upstreamEvidence != null)
                    && contextEvidence != null && !contextEvidence.isMethodLevel();
            int evidenceAttempts = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getAttempts()
                    : useSignatureAxis ? signatureEvidence.getAttempts() : totalAttempts;
            int evidenceExceptions = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getExceptions()
                    : useSignatureAxis ? signatureEvidence.getExceptions() : totalExceptions;
            int evidenceSuccesses = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getSuccesses()
                    : useSignatureAxis ? signatureEvidence.getSuccesses() : totalSuccesses;
            double confidence = CardBuildSupport.barrierConfidence(evidenceAttempts);
            List<String> evidence = new ArrayList<>();
            MethodPromptContext methodContext = CardBuildSupport.describeMethodContext(method, relatedGoals);
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
            } else if (useSignatureAxis) {
                evidence.add("Method-wide direct invocations: " + totalAttempts + ".");
                evidence.add("Method-wide non-exception completions: " + totalSuccesses + ".");
                evidence.add("Method-wide exception outcomes: " + totalExceptions + ".");
                evidence.add("Blocked overload: " + signatureEvidence.getDisplayLabel() + ".");
                evidence.add("Overload-local direct invocations: " + signatureEvidence.getAttempts() + ".");
                evidence.add("Overload-local non-exception completions: " + signatureEvidence.getSuccesses() + ".");
                evidence.add("Overload-local exception outcomes: " + signatureEvidence.getExceptions() + ".");
                evidence.add("Overload-local exception dominance: "
                        + signatureEvidence.getExceptions() + "/" + signatureEvidence.getAttempts() + ".");
            } else if (useContextAxis) {
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
                    : useSignatureAxis
                    ? signatureEvidence.getDominantException()
                    : contextEvidence != null
                    ? contextEvidence.getDominantException()
                    : (stats == null ? "" : CardBuildSupport.dominantException(stats.exceptionTypeCounts));
            if (dominant.isEmpty() && historical != null) {
                dominant = historical.getDominantException();
            }
            if (!dominant.isEmpty()) {
                evidence.add("Dominant exception: " + dominant + ".");
            }
            String dominantInvocation = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getDominantInvocationLabel()
                    : useSignatureAxis
                    ? signatureEvidence.getDominantInvocationLabel()
                    : contextEvidence != null
                    ? contextEvidence.getDominantInvocationLabel()
                    : (stats == null ? "" : stats.dominantFailingInvocationLabel());
            if (!dominantInvocation.isEmpty()) {
                evidence.add("Dominant failing invocation: " + dominantInvocation + ".");
            }
            int failingInvocations = upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.getExceptions()
                    : useSignatureAxis ? signatureEvidence.getExceptions()
                    : contextEvidence != null ? contextEvidence.getExceptions()
                    : (stats == null ? 0 : stats.exceptions);
            int failingPrefixes = upstreamBarrier && upstreamEvidence != null
                    ? upstreamEvidence.getFailingInvocationsWithSuccessfulPrefix()
                    : useSignatureAxis ? signatureEvidence.getFailingInvocationsWithSuccessfulPrefix()
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
            CardBuildSupport.addOverloadEvidence(evidence, methodContext);
            out.add(ProblemCard.builder(ProblemCardType.EXCEPTION_BARRIER)
                    .title(upstreamBarrier && upstreamEvidence != null
                            ? "Upstream invocation is exception-dominated before target call: "
                            + methodContext.displayLabel
                            : useSignatureAxis
                            ? "Overload is exception-dominated: " + signatureEvidence.getDisplayLabel()
                            : useContextAxis
                            ? "Direct invocations are exception-dominated in context: "
                            + methodContext.displayLabel + " [" + contextEvidence.getContextLabel() + "]"
                            : "Direct invocations are exception-dominated: " + methodContext.displayLabel)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(upstreamBarrier && upstreamEvidence != null ? upstreamEvidence.getBlockage()
                            : useSignatureAxis ? signatureEvidence.getBlockage()
                            : contextEvidence != null ? contextEvidence.getBlockage() : blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.EXECUTION)
                    .rootCauseKey(upstreamBarrier && upstreamEvidence != null
                            ? upstreamEvidence.getBlockerExecutionKey() : method)
                    .scopeKey(method)
                    .selectionFingerprint("exception:" + method
                            + "|attempts=" + evidenceAttempts
                            + "|exceptions=" + evidenceExceptions
                            + "|successes=" + evidenceSuccesses
                            + "|dominance=" + evidenceExceptions + "/" + evidenceAttempts
                            + (upstreamBarrier && upstreamEvidence != null
                            ? "|upstream=" + upstreamEvidence.getBlockerExecutionKey()
                            : "")
                            + (useSignatureAxis
                            ? "|signature=" + signatureEvidence.getSignatureKey()
                            + "|signatureDominance=" + signatureEvidence.getExceptions()
                            + "/" + signatureEvidence.getAttempts()
                            : "")
                            + (useContextAxis
                            ? "|context=" + contextEvidence.getContextKey()
                            + "|contextDominance=" + contextEvidence.getExceptions()
                            + "/" + contextEvidence.getAttempts()
                            : "")
                            + "|dominant=" + dominant)
                    .build());
        }
    }

    private void recordExceptionBarrierMethodTelemetry(int totalAttempts,
                                                       int totalExceptions,
                                                       double blockage,
                                                       CardBuildContext context) {
        if (context.telemetry == null || totalAttempts <= 0 || totalExceptions <= 0) {
            return;
        }
        boolean belowAttempts = totalAttempts < context.thresholds.minAttemptsForExceptionBarrier()
                || totalExceptions < context.thresholds.minAttemptsForExceptionBarrier();
        boolean belowFailureRate = blockage < context.thresholds.minFailureRateForExceptionBarrier();
        if (belowAttempts) {
            context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
        }
        if (belowFailureRate) {
            context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
        }
        if (!belowAttempts && !belowFailureRate) {
            context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES);
        }
    }

    private void recordExceptionBarrierContextTelemetry(AttemptStats current,
                                                        ExceptionBarrierTracker.AggregatedStats historical,
                                                        CardBuildContext context) {
        if (context.telemetry == null) {
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
            boolean belowAttempts = candidate.getAttempts() < context.thresholds.minAttemptsForExceptionBarrier()
                    || candidate.getExceptions() < context.thresholds.minAttemptsForExceptionBarrier();
            boolean belowFailureRate =
                    candidate.getBlockage() < context.thresholds.minFailureRateForExceptionBarrier();
            if (belowAttempts) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            }
            if (belowFailureRate) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
            }
            if (!belowAttempts && !belowFailureRate) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_CONTEXT_CANDIDATES);
            }
        }
    }

    private void recordExceptionBarrierUpstreamTelemetry(AttemptStats current,
                                                         ExceptionBarrierTracker.AggregatedStats historical,
                                                         CardBuildContext context) {
        if (context.telemetry == null) {
            return;
        }
        for (UpstreamExceptionBarrierEvidence candidate : mergedUpstreamExceptionBarriers(current, historical).values()) {
            if (candidate == null || candidate.getAttempts() <= 0 || candidate.getExceptions() <= 0) {
                continue;
            }
            boolean belowAttempts = candidate.getAttempts() < context.thresholds.minAttemptsForExceptionBarrier()
                    || candidate.getExceptions() < context.thresholds.minAttemptsForExceptionBarrier();
            boolean belowFailureRate =
                    candidate.getBlockage() < context.thresholds.minFailureRateForExceptionBarrier();
            if (belowAttempts) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS);
            }
            if (belowFailureRate) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE);
            }
            if (!belowAttempts && !belowFailureRate) {
                context.telemetry.increment(ExtractorCandidateMetric.EXCEPTION_BARRIER_UPSTREAM_CANDIDATES);
            }
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
            ExceptionBarrierTracker.AggregatedStats historical,
            CardBuildContext context) {
        Map<String, ExceptionBarrierContextEvidence> mergedContexts = mergedExceptionBarrierContexts(current, historical);

        ExceptionBarrierContextEvidence best = null;
        for (ExceptionBarrierContextEvidence candidate : mergedContexts.values()) {
            if (candidate == null
                    || candidate.isMethodLevel()
                    || InvocationContextClassifier.DEFAULT_CONTEXT.getKey().equals(candidate.getContextKey())) {
                continue;
            }
            if (candidate.getAttempts() < context.thresholds.minAttemptsForExceptionBarrier()
                    || candidate.getExceptions() < context.thresholds.minAttemptsForExceptionBarrier()) {
                continue;
            }
            if (candidate.getBlockage() < context.thresholds.minFailureRateForExceptionBarrier()) {
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
        String dominantException = current == null ? "" : CardBuildSupport.dominantException(current.exceptionTypeCounts);
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

    private ExceptionBarrierSignatureEvidence selectExceptionBarrierSignature(
            AttemptStats current,
            CardBuildContext context) {
        if (current == null || current.signatureStats.isEmpty()) {
            return null;
        }
        ExceptionBarrierSignatureEvidence best = null;
        for (SignatureAttemptStats signatureStats : current.signatureStats.values()) {
            if (signatureStats == null || signatureStats.attempts <= 0 || signatureStats.exceptions <= 0) {
                continue;
            }
            double blockage = signatureStats.attempts <= 0
                    ? 0.0
                    : ((double) signatureStats.exceptions) / (double) signatureStats.attempts;
            if (signatureStats.attempts < context.thresholds.minAttemptsForExceptionBarrier()
                    || signatureStats.exceptions < context.thresholds.minAttemptsForExceptionBarrier()
                    || blockage < context.thresholds.minFailureRateForExceptionBarrier()) {
                continue;
            }
            ExceptionBarrierSignatureEvidence candidate = new ExceptionBarrierSignatureEvidence(
                    signatureStats.signatureKey,
                    signatureStats.displayLabel,
                    signatureStats.attempts,
                    signatureStats.successes,
                    signatureStats.exceptions,
                    CardBuildSupport.dominantException(signatureStats.exceptionTypeCounts),
                    signatureStats.dominantFailingInvocationLabel(),
                    signatureStats.failingInvocationsWithSuccessfulPrefix);
            if (best == null || candidate.isStrongerThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private UpstreamExceptionBarrierEvidence selectUpstreamExceptionBarrier(
            AttemptStats current,
            ExceptionBarrierTracker.AggregatedStats historical,
            CardBuildContext context) {
        Map<String, UpstreamExceptionBarrierEvidence> merged = mergedUpstreamExceptionBarriers(current, historical);
        UpstreamExceptionBarrierEvidence best = null;
        for (UpstreamExceptionBarrierEvidence candidate : merged.values()) {
            if (candidate == null) {
                continue;
            }
            if (candidate.getAttempts() < context.thresholds.minAttemptsForExceptionBarrier()
                    || candidate.getExceptions() < context.thresholds.minAttemptsForExceptionBarrier()
                    || candidate.getBlockage() < context.thresholds.minFailureRateForExceptionBarrier()) {
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
                CardBuildSupport.dominantException(source.exceptionTypeCounts),
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
                CardBuildSupport.dominantException(source.exceptionTypeCounts),
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
}
