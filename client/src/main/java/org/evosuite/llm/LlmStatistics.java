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
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.llm.postprocess.LlmPostProcessor;
import org.evosuite.llm.search.DiagnosticCardSelector;
import org.evosuite.llm.search.ExtractorCandidateMetric;
import org.evosuite.llm.search.ExtractorRejectReason;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.rmi.ClientServices;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Aggregated runtime metrics for LLM usage.
 */
public class LlmStatistics {

    /**
     * Process-wide wall-clock time spent blocking on pre-search LLM pool
     * enrichment (cast classes, constants, objects). Read by time-based
     * stopping conditions when {@code LLM_FAIR_BUDGET_ACCOUNTING} is enabled so
     * that the elapsed enrichment time is deducted from the search budget.
     */
    private static final AtomicLong poolEnrichmentElapsedMs = new AtomicLong();
    private static final AtomicLong initialPopulationElapsedMs = new AtomicLong();
    private static final AtomicLong initialPopulationCandidatesValidated = new AtomicLong();
    private static final AtomicLong initialPopulationCandidatesQueued = new AtomicLong();
    private static final AtomicLong initialPopulationCandidatesInjected = new AtomicLong();
    private static final AtomicLong sutConstantsAdded = new AtomicLong();
    private static final AtomicLong nonSutConstantsAdded = new AtomicLong();
    private static final AtomicLong objectPoolSequencesAdded = new AtomicLong();
    private static final AtomicLong castClassSuggestions = new AtomicLong();
    private static final AtomicLong castClassesAccepted = new AtomicLong();
    private static final AtomicLong diagnosticCardsDiscarded = new AtomicLong();
    private static final AtomicLong diagnosticCardsDeduplicated = new AtomicLong();
    private static final AtomicLong diagnosticCoverageGainAttributionAmbiguous = new AtomicLong();
    private static final AtomicLong diagnosticCoverageGainAttributionAmbiguousGoals = new AtomicLong();
    private static final ColumnCounters<ExtractorCandidateMetric> diagnosticExtractorCandidates =
            new ColumnCounters<>(ExtractorCandidateMetric.class, null, "LLM_Diagnostic_Extractor_Candidates_");
    private static final ColumnCounters<ExtractorRejectReason> diagnosticExtractorRejects =
            new ColumnCounters<>(ExtractorRejectReason.class, null, "LLM_Diagnostic_Extractor_Rejects_");
    private static final Map<LlmFeature, RuntimeVariable> cachedInputTokensVariables =
            ColumnCounters.columns(LlmFeature.class, "LLM_", "_Cached_Input_Tokens");

    /** Resets all pre-search timing counters (test/utility use). */
    public static void resetEnrichmentElapsedMs() {
        poolEnrichmentElapsedMs.set(0L);
        initialPopulationElapsedMs.set(0L);
    }

    public static void recordPoolEnrichmentElapsedMs(long elapsedMs) {
        poolEnrichmentElapsedMs.set(Math.max(0L, elapsedMs));
    }

    public static long getPoolEnrichmentElapsedMs() {
        return poolEnrichmentElapsedMs.get();
    }

    public static void recordInitialPopulationElapsedMs(long elapsedMs) {
        initialPopulationElapsedMs.set(Math.max(0L, elapsedMs));
    }

    public static long getInitialPopulationElapsedMs() {
        return initialPopulationElapsedMs.get();
    }

    public static long getTotalPreSearchElapsedMs() {
        return getPoolEnrichmentElapsedMs() + getInitialPopulationElapsedMs();
    }

    public static void recordInitialPopulationCandidatesValidated(long count) {
        addPositive(initialPopulationCandidatesValidated, count);
    }

    public static void recordInitialPopulationCandidatesQueued(long count) {
        addPositive(initialPopulationCandidatesQueued, count);
    }

    public static void recordInitialPopulationCandidatesInjected(long count) {
        addPositive(initialPopulationCandidatesInjected, count);
    }

    public static long getInitialPopulationCandidatesValidated() {
        return initialPopulationCandidatesValidated.get();
    }

    public static long getInitialPopulationCandidatesQueued() {
        return initialPopulationCandidatesQueued.get();
    }

    public static long getInitialPopulationCandidatesInjected() {
        return initialPopulationCandidatesInjected.get();
    }

    /** Records accepted constants added to the SUT constant pool. */
    public static void recordSutConstantsAdded(long count) {
        addPositive(sutConstantsAdded, count);
    }

    /** Records accepted constants added to the non-SUT constant pool. */
    public static void recordNonSutConstantsAdded(long count) {
        addPositive(nonSutConstantsAdded, count);
    }

    /** Records accepted object-pool construction sequences. */
    public static void recordObjectPoolSequencesAdded(long count) {
        addPositive(objectPoolSequencesAdded, count);
    }

    /** Records class-name suggestions produced for cast-class enrichment. */
    public static void recordCastClassSuggestions(long count) {
        addPositive(castClassSuggestions, count);
    }

    /** Records cast classes actually accepted into the cast-class manager. */
    public static void recordCastClassesAccepted(long count) {
        addPositive(castClassesAccepted, count);
    }

    private static void addPositive(AtomicLong counter, long count) {
        if (count > 0L) {
            counter.addAndGet(count);
        }
    }

    public static long getSutConstantsAdded() {
        return sutConstantsAdded.get();
    }

    public static long getNonSutConstantsAdded() {
        return nonSutConstantsAdded.get();
    }

    public static long getObjectPoolSequencesAdded() {
        return objectPoolSequencesAdded.get();
    }

    public static long getCastClassSuggestions() {
        return castClassSuggestions.get();
    }

    public static long getCastClassesAccepted() {
        return castClassesAccepted.get();
    }

    /** Resets run-level seeding counters (test/utility use). */
    public static void resetSeedingCounters() {
        sutConstantsAdded.set(0L);
        nonSutConstantsAdded.set(0L);
        objectPoolSequencesAdded.set(0L);
        castClassSuggestions.set(0L);
        castClassesAccepted.set(0L);
        initialPopulationCandidatesValidated.set(0L);
        initialPopulationCandidatesQueued.set(0L);
        initialPopulationCandidatesInjected.set(0L);
    }

    /**
     * Publishes the current monotonic seeding counters. These counters are kept
     * outside the mutable seed managers so cleanup cannot erase accepted counts.
     */
    public static void flushSeedingMetrics() {
        for (Metric metric : METRICS) {
            if (metric.seeding) {
                ClientServices.track(metric.variable, metric.reader.apply(Publication.NONE));
            }
        }
    }

    /** Records all extracted problem cards before selection/truncation. */
    public static void recordDiagnosticCardsExtracted(Collection<ProblemCard> cards) {
        recordDiagnosticCards(DiagnosticStage.EXTRACTED, cards);
    }

    /** Returns the total number of extracted diagnostic cards. */
    public static long getDiagnosticCardsExtracted() {
        return DiagnosticStage.EXTRACTED.counters().total();
    }

    /** Returns extracted-card count for a specific problem-card type. */
    public static long getDiagnosticCardsExtracted(ProblemCardType type) {
        return DiagnosticStage.EXTRACTED.counters().get(type);
    }

    /** Records selected problem cards from a diagnostic stagnation prompt. */
    public static void recordDiagnosticCardsSelected(Collection<ProblemCard> cards) {
        recordDiagnosticCards(DiagnosticStage.SELECTED, cards);
    }

    /** Returns the total number of selected diagnostic cards. */
    public static long getDiagnosticCardsSelected() {
        return DiagnosticStage.SELECTED.counters().total();
    }

    /** Returns selected-card count for a specific problem-card type. */
    public static long getDiagnosticCardsSelected(ProblemCardType type) {
        return DiagnosticStage.SELECTED.counters().get(type);
    }

    private static void recordDiagnosticCards(DiagnosticStage stage, Collection<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        long added = 0L;
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            added++;
            stage.counters().add(card.getType(), 1L);
        }
        if (added > 0L) {
            stage.counters().addToTotal(added);
        }
    }

    /** Records discarded cards after selector deduplication/diversification. */
    public static void recordDiagnosticCardsDiscarded(
            Collection<DiagnosticCardSelector.DiscardedCard> discardedCards) {
        if (discardedCards == null || discardedCards.isEmpty()) {
            return;
        }
        long discarded = 0L;
        long deduplicated = 0L;
        for (DiagnosticCardSelector.DiscardedCard discardedCard : discardedCards) {
            if (discardedCard == null || discardedCard.getCard() == null) {
                continue;
            }
            discarded++;
            if (discardedCard.getReason() == DiagnosticCardSelector.DiscardReason.ROOT_CAUSE_OVERLAP) {
                deduplicated++;
            }
        }
        if (discarded > 0L) {
            ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Discarded,
                    diagnosticCardsDiscarded.addAndGet(discarded));
        }
        if (deduplicated > 0L) {
            ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Deduplicated,
                    diagnosticCardsDeduplicated.addAndGet(deduplicated));
        }
    }

    public static long getDiagnosticCardsDiscarded() {
        return diagnosticCardsDiscarded.get();
    }

    public static long getDiagnosticCardsDeduplicated() {
        return diagnosticCardsDeduplicated.get();
    }

    /**
     * Counts diagnostic-card candidates that reached the published stage.
     *
     * @param cardTypes the card type of each contributing card
     * @param candidateCount the number of candidates to add
     */
    public static void recordDiagnosticCandidatesPublished(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(DiagnosticStage.PUBLISHED, cardTypes, candidateCount);
    }

    public static long getDiagnosticCandidatesPublished() {
        return DiagnosticStage.PUBLISHED.counters().total();
    }

    public static long getDiagnosticCandidatesPublished(ProblemCardType type) {
        return DiagnosticStage.PUBLISHED.counters().get(type);
    }

    /**
     * Counts diagnostic-card candidates that reached the admitted stage.
     *
     * @param cardTypes the card type of each contributing card
     * @param candidateCount the number of candidates to add
     */
    public static void recordDiagnosticCandidatesAdmitted(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(DiagnosticStage.ADMITTED, cardTypes, candidateCount);
    }

    public static long getDiagnosticCandidatesAdmitted() {
        return DiagnosticStage.ADMITTED.counters().total();
    }

    public static long getDiagnosticCandidatesAdmitted(ProblemCardType type) {
        return DiagnosticStage.ADMITTED.counters().get(type);
    }

    /**
     * Counts diagnostic-card candidates that reached the survived stage.
     *
     * @param cardTypes the card type of each contributing card
     * @param candidateCount the number of candidates to add
     */
    public static void recordDiagnosticCandidatesSurvived(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(DiagnosticStage.SURVIVED, cardTypes, candidateCount);
    }

    public static long getDiagnosticCandidatesSurvived() {
        return DiagnosticStage.SURVIVED.counters().total();
    }

    public static long getDiagnosticCandidatesSurvived(ProblemCardType type) {
        return DiagnosticStage.SURVIVED.counters().get(type);
    }

    /**
     * Counts the candidates the diagnostic extractors rejected, by reject reason.
     *
     * @param rejectCounts the number of rejected candidates per reason; null or empty is ignored
     */
    public static void recordDiagnosticExtractorRejects(Map<ExtractorRejectReason, Integer> rejectCounts) {
        addPositive(diagnosticExtractorRejects, rejectCounts);
    }

    /**
     * Counts the candidates the diagnostic extractors produced, by metric.
     *
     * @param candidateCounts the number of candidates per metric; null or empty is ignored
     */
    public static void recordDiagnosticExtractorCandidates(Map<ExtractorCandidateMetric, Integer> candidateCounts) {
        addPositive(diagnosticExtractorCandidates, candidateCounts);
    }

    private static <E extends Enum<E>> void addPositive(ColumnCounters<E> counters, Map<E, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        for (Map.Entry<E, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            counters.add(entry.getKey(), entry.getValue());
        }
    }

    public static long getDiagnosticExtractorCandidates(ExtractorCandidateMetric metric) {
        return diagnosticExtractorCandidates.get(metric);
    }

    public static long getDiagnosticExtractorRejects(ExtractorRejectReason reason) {
        return diagnosticExtractorRejects.get(reason);
    }

    /** Resets diagnostic-card counters (test/utility use). */
    public static void resetDiagnosticCardCounters() {
        diagnosticCardsDiscarded.set(0L);
        diagnosticCardsDeduplicated.set(0L);
        diagnosticCoverageGainAttributionAmbiguous.set(0L);
        diagnosticCoverageGainAttributionAmbiguousGoals.set(0L);
        for (DiagnosticStage stage : DiagnosticStage.values()) {
            stage.counters().reset();
        }
        diagnosticExtractorCandidates.reset();
        diagnosticExtractorRejects.reset();
    }

    /**
     * Clears process-scoped counters after a completed in-process EvoSuite run.
     * Statistics must be published before this method is called.
     */
    public static void resetRunCounters() {
        resetEnrichmentElapsedMs();
        resetSeedingCounters();
        resetDiagnosticCardCounters();
    }

    /**
     * Records uncovered-goal gains attributed to a diagnostic-card type.
     * Gains are ignored when type is null or gain <= 0.
     */
    public static void recordDiagnosticCoverageGain(ProblemCardType type, int gain) {
        if (type == null || gain <= 0) {
            return;
        }
        ColumnCounters<ProblemCardType> gains = DiagnosticStage.COVERAGE_GAIN.counters();
        gains.add(type, gain);
        gains.addToTotal(gain);
    }

    /** Returns the total number of uncovered-goal gains attributed to diagnostic cards. */
    public static long getDiagnosticCoverageGains() {
        return DiagnosticStage.COVERAGE_GAIN.counters().total();
    }

    /** Returns attributed uncovered-goal gains for a specific diagnostic-card type. */
    public static long getDiagnosticCoverageGains(ProblemCardType type) {
        return DiagnosticStage.COVERAGE_GAIN.counters().get(type);
    }

    /**
     * Counts uncovered-goal gains that cannot be attributed to a single diagnostic-card type because several
     * types were selected; gains with at most one selected type are ignored.
     *
     * @param selectedTypes the card types that were selected when the gain occurred
     * @param gainedGoals the number of goals gained; non-positive values are ignored
     */
    public static void recordDiagnosticCoverageGainAttributionAmbiguous(
            Collection<ProblemCardType> selectedTypes,
            int gainedGoals) {
        if (gainedGoals <= 0) {
            return;
        }
        if (normalizeDiagnosticCardTypes(selectedTypes).size() <= 1) {
            return;
        }
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous,
                diagnosticCoverageGainAttributionAmbiguous.incrementAndGet());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous_Goals,
                diagnosticCoverageGainAttributionAmbiguousGoals.addAndGet(gainedGoals));
    }

    public static long getDiagnosticCoverageGainAttributionAmbiguous() {
        return diagnosticCoverageGainAttributionAmbiguous.get();
    }

    public static long getDiagnosticCoverageGainAttributionAmbiguousGoals() {
        return diagnosticCoverageGainAttributionAmbiguousGoals.get();
    }

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successfulCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong timedOutCalls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final Map<LlmFeature, FeatureStats> perFeature = new ConcurrentHashMap<>();
    private volatile String configuredModel = "";
    private volatile String configuredOperatorModel = "";

    /**
     * Associates this statistics instance with the immutable configuration of
     * the run that owns it.  The service invokes this during construction so
     * publishing never reads mutable global properties from a later run.
     */
    void setConfiguration(LlmConfiguration configuration) {
        if (configuration == null || configuration.getProvider() == Properties.LlmProvider.NONE) {
            configuredModel = "";
            configuredOperatorModel = "";
        } else {
            configuredModel = configuration.getModel();
            configuredOperatorModel = configuration.modelFor(LlmFeature.SEMANTIC_MUTATION);
        }
    }

    /**
     * Records a successful LLM call for the given feature, updating token and
     * latency totals. {@code cachedInputTokenCount} is the portion of
     * {@code inputTokenCount} that the provider served from a prompt cache.
     */
    public void recordCall(LlmFeature feature, int inputTokenCount, int cachedInputTokenCount,
                           int outputTokenCount, long latencyMs) {
        totalCalls.incrementAndGet();
        successfulCalls.incrementAndGet();
        inputTokens.addAndGet(Math.max(0, inputTokenCount));
        cachedInputTokens.addAndGet(Math.max(0, cachedInputTokenCount));
        outputTokens.addAndGet(Math.max(0, outputTokenCount));
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats())
                .recordSuccess(inputTokenCount, cachedInputTokenCount, outputTokenCount, latencyMs);
    }

    /**
     * Records a provider attempt which will be retried.  It contributes to the
     * documented total-attempt and latency metrics, but not to final outcome
     * counters such as {@code LLM_Calls_Failed}.
     */
    public void recordRetryAttempt(LlmFeature feature, long latencyMs) {
        totalCalls.incrementAndGet();
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats())
                .recordRetryAttempt(latencyMs);
    }

    /**
     * Records a provider attempt that was cut short because the caller was
     * interrupted.  It counts as an attempt, but as neither a failure nor a
     * timeout: the provider did not fail, the run was shutting down.
     */
    public void recordInterruptedAttempt(LlmFeature feature, long latencyMs) {
        recordRetryAttempt(feature, latencyMs);
    }

    /**
     * Records the final outcome of a call whose last provider attempt was
     * already counted by {@link #recordRetryAttempt} and which could not be
     * retried further (budget or deadline exhausted).  No new provider attempt
     * took place, so the total and latency are left alone.
     */
    public void recordRetriesAbandoned(LlmFeature feature, boolean timedOut) {
        FeatureStats stats = perFeature.computeIfAbsent(feature, ignored -> new FeatureStats());
        if (timedOut) {
            timedOutCalls.incrementAndGet();
        } else {
            failedCalls.incrementAndGet();
            stats.failures.incrementAndGet();
        }
    }

    /** Records a final failed provider attempt, including its elapsed time. */
    public void recordFailure(LlmFeature feature, long latencyMs) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats()).recordFailure(latencyMs);
    }

    /** Records a final timed-out provider attempt, including its elapsed time. */
    public void recordTimeout(LlmFeature feature, long latencyMs) {
        totalCalls.incrementAndGet();
        timedOutCalls.incrementAndGet();
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats()).recordTimeout(latencyMs);
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }

    public long getSuccessfulCalls() {
        return successfulCalls.get();
    }

    public long getFailedCalls() {
        return failedCalls.get();
    }

    public long getTimedOutCalls() {
        return timedOutCalls.get();
    }

    public long getInputTokens() {
        return inputTokens.get();
    }

    public long getCachedInputTokens() {
        return cachedInputTokens.get();
    }

    public long getOutputTokens() {
        return outputTokens.get();
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs.get();
    }

    /** Returns per-feature statistics snapshots. */
    public Map<LlmFeature, FeatureSnapshot> getFeatureSnapshots() {
        Map<LlmFeature, FeatureSnapshot> snapshots = new EnumMap<>(LlmFeature.class);
        for (Map.Entry<LlmFeature, FeatureStats> entry : perFeature.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return snapshots;
    }

    /** What a metric reads when it is published: one service, its feature snapshots and their operator sums. */
    private static final class Publication {
        static final Publication NONE = new Publication(null, Collections.emptyMap());

        final LlmStatistics service;
        final Map<LlmFeature, FeatureSnapshot> features;
        final OperatorFeatureTotals operator;

        Publication(LlmStatistics service, Map<LlmFeature, FeatureSnapshot> features) {
            this.service = service;
            this.features = features;
            this.operator = OperatorFeatureTotals.of(features);
        }

        long cachedInputTokens(LlmFeature feature) {
            FeatureSnapshot snapshot = features.get(feature);
            return snapshot == null ? 0L : snapshot.getCachedInputTokens();
        }
    }

    /**
     * One runtime variable of the LLM statistics: the default it has before the search and, for the
     * variables this class owns, how to read its final value. A variable without a reader is
     * published by the component that tracks it during the run.
     */
    private static final class Metric {
        final RuntimeVariable variable;
        final Object defaultValue;
        final Function<Publication, Object> reader;
        final boolean seeding;

        Metric(RuntimeVariable variable, Object defaultValue, Function<Publication, Object> reader,
               boolean seeding) {
            this.variable = variable;
            this.defaultValue = defaultValue;
            this.reader = reader;
            this.seeding = seeding;
        }
    }

    /** Collects the metrics and rejects a variable that is listed twice. */
    private static final class MetricList {
        private final List<Metric> metrics = new ArrayList<>();
        private final Set<RuntimeVariable> variables = EnumSet.noneOf(RuntimeVariable.class);

        /** A variable that only has a default. */
        void defaultOnly(RuntimeVariable variable, Object defaultValue) {
            add(new Metric(variable, defaultValue, null, false));
        }

        /** A variable read from the service or the process-wide counters when it is published. */
        void published(RuntimeVariable variable, Object defaultValue, Function<Publication, Object> reader) {
            add(new Metric(variable, defaultValue, reader, false));
        }

        /** A process-wide seeding counter, which {@link #flushSeedingMetrics()} publishes on its own. */
        void seeding(RuntimeVariable variable, Supplier<Long> counter) {
            add(new Metric(variable, 0, publication -> counter.get(), true));
        }

        /** The columns of a table of counters, including its total if it has one. */
        <E extends Enum<E>> void counters(ColumnCounters<E> counters) {
            if (counters.totalColumn() != null) {
                published(counters.totalColumn(), 0, publication -> counters.total());
            }
            for (E key : counters.keys()) {
                published(counters.column(key), 0, publication -> counters.get(key));
            }
        }

        private void add(Metric metric) {
            if (!variables.add(metric.variable)) {
                throw new IllegalStateException("Listed twice: " + metric.variable);
            }
            metrics.add(metric);
        }

        List<Metric> build() {
            return Collections.unmodifiableList(metrics);
        }
    }

    /**
     * Every runtime variable of the LLM statistics, with its default and its reader. The defaults
     * are published before the search in every mode and the readers at the end of a run with a
     * provider, so a variable is present in the output whether or not its feature ran.
     */
    private static final List<Metric> METRICS = metrics();

    private static List<Metric> metrics() {
        MetricList m = new MetricList();
        m.published(RuntimeVariable.LLM_Model, "", p -> p.service.configuredModel);
        m.published(RuntimeVariable.LLM_Operator_Model, "", p -> p.service.configuredOperatorModel);
        m.published(RuntimeVariable.LLM_Calls, 0, p -> p.service.getTotalCalls());
        m.published(RuntimeVariable.LLM_Calls_Succeeded, 0, p -> p.service.getSuccessfulCalls());
        m.published(RuntimeVariable.LLM_Calls_Failed, 0, p -> p.service.getFailedCalls());
        m.published(RuntimeVariable.LLM_Calls_TimedOut, 0, p -> p.service.getTimedOutCalls());
        m.published(RuntimeVariable.LLM_Input_Tokens, 0, p -> p.service.getInputTokens());
        m.published(RuntimeVariable.LLM_Cached_Input_Tokens, 0, p -> p.service.getCachedInputTokens());
        m.published(RuntimeVariable.LLM_Output_Tokens, 0, p -> p.service.getOutputTokens());
        m.published(RuntimeVariable.LLM_Latency_Millis, 0, p -> p.service.getTotalLatencyMs());
        for (Map.Entry<LlmFeature, RuntimeVariable> column : cachedInputTokensVariables.entrySet()) {
            LlmFeature feature = column.getKey();
            m.published(column.getValue(), 0, p -> p.cachedInputTokens(feature));
        }
        m.published(RuntimeVariable.LLM_Enrichment_Elapsed_Millis, 0, p -> getPoolEnrichmentElapsedMs());
        m.published(RuntimeVariable.LLM_Pool_Enrichment_Elapsed_Millis, 0, p -> getPoolEnrichmentElapsedMs());
        m.published(RuntimeVariable.LLM_Initial_Population_Elapsed_Millis, 0,
                p -> getInitialPopulationElapsedMs());
        m.published(RuntimeVariable.LLM_Total_Pre_Search_Elapsed_Millis, 0, p -> getTotalPreSearchElapsedMs());
        m.seeding(RuntimeVariable.LLM_Cast_Class_Suggestions, LlmStatistics::getCastClassSuggestions);
        m.seeding(RuntimeVariable.LLM_Cast_Class_Accepted, LlmStatistics::getCastClassesAccepted);
        m.seeding(RuntimeVariable.LLM_Constants_Added_SUT, LlmStatistics::getSutConstantsAdded);
        m.seeding(RuntimeVariable.LLM_Constants_Added_NonSUT, LlmStatistics::getNonSutConstantsAdded);
        m.seeding(RuntimeVariable.LLM_Object_Pool_Sequences_Added, LlmStatistics::getObjectPoolSequencesAdded);
        m.seeding(RuntimeVariable.LLM_Initial_Population_Candidates_Validated,
                LlmStatistics::getInitialPopulationCandidatesValidated);
        m.seeding(RuntimeVariable.LLM_Initial_Population_Candidates_Queued,
                LlmStatistics::getInitialPopulationCandidatesQueued);
        m.seeding(RuntimeVariable.LLM_Initial_Population_Candidates_Injected,
                LlmStatistics::getInitialPopulationCandidatesInjected);

        m.published(RuntimeVariable.LLM_Diagnostic_Cards_Discarded, 0, p -> getDiagnosticCardsDiscarded());
        m.published(RuntimeVariable.LLM_Diagnostic_Cards_Deduplicated, 0, p -> getDiagnosticCardsDeduplicated());
        m.published(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous, 0,
                p -> getDiagnosticCoverageGainAttributionAmbiguous());
        m.published(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous_Goals, 0,
                p -> getDiagnosticCoverageGainAttributionAmbiguousGoals());
        for (DiagnosticStage stage : DiagnosticStage.values()) {
            m.counters(stage.counters());
        }
        m.counters(diagnosticExtractorCandidates);
        m.counters(diagnosticExtractorRejects);

        m.published(RuntimeVariable.LLM_Operator_Calls, 0, p -> p.operator.calls);
        m.published(RuntimeVariable.LLM_Operator_Input_Tokens, 0, p -> p.operator.inputTokens);
        m.published(RuntimeVariable.LLM_Operator_Cached_Input_Tokens, 0, p -> p.operator.cachedInputTokens);
        m.published(RuntimeVariable.LLM_Operator_Output_Tokens, 0, p -> p.operator.outputTokens);
        m.published(RuntimeVariable.LLM_Operator_Latency_Millis, 0, p -> p.operator.latencyMs);

        // Tracked by other components during the run; these are only the defaults.
        m.defaultOnly(RuntimeVariable.LLM_All_Candidates_Dropped_Execution, 0);
        m.defaultOnly(RuntimeVariable.LLM_Semantic_Mutations, 0);
        m.defaultOnly(RuntimeVariable.LLM_Semantic_Mutation_Fallbacks, 0);
        m.defaultOnly(RuntimeVariable.LLM_Semantic_Crossovers, 0);
        m.defaultOnly(RuntimeVariable.LLM_Semantic_Crossover_Fallbacks, 0);
        m.defaultOnly(RuntimeVariable.LLM_Parsed_Statement_Ratio, 0.0);
        m.defaultOnly(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline, 0.0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Iterations, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Rounds, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Exit_Reason, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Parse_Failures, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_New_Goals_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Coverage_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Tests_Parsed, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Tests_Unique, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Duplicates, 0);
        m.defaultOnly(RuntimeVariable.LLM_Strategy_Assertions_Retained, 0);
        m.defaultOnly(RuntimeVariable.LLM_Strategy_Tests_With_Assertions, 0);
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Round_Elapsed_Millis_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Query_Latency_Millis_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Suite_Size_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Suite_Length_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Tests_Parsed_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Tests_Unique_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_Iterative_Target_Goals_Timeline, "");
        m.defaultOnly(RuntimeVariable.LLM_StagnationCalls, 0);
        m.defaultOnly(RuntimeVariable.LLM_AsyncProducer_DiagnosticCalls, 0);
        m.defaultOnly(RuntimeVariable.LLM_AsyncProducer_Cards_Used, 0);
        m.defaultOnly(RuntimeVariable.LLM_AsyncProducer_LoopIterations, 0);
        m.defaultOnly(RuntimeVariable.LLM_AsyncProducer_Stopped_Reason, "");
        m.defaultOnly(RuntimeVariable.LLM_StagnationPromptsSubmitted, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationResponsesReceived, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationTestsPublished, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationLatencyMsTotal, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationBlockedMsTotal, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationInFlightGenerations, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationSkippedBudget, 0);
        m.defaultOnly(RuntimeVariable.LLM_StagnationSkippedInFlight, 0);
        m.defaultOnly(RuntimeVariable.LLM_Injected_Candidates_OrphanFiltered, 0);
        m.defaultOnly(RuntimeVariable.LLM_Injected_Candidates_Deduplicated, 0);
        m.defaultOnly(RuntimeVariable.LLM_Injected_Candidates_Admitted, 0);
        m.defaultOnly(RuntimeVariable.LLM_Injected_Candidates_Survived, 0);
        m.defaultOnly(RuntimeVariable.Species_Largest_Share_Timeline, 0.0);
        m.defaultOnly(RuntimeVariable.Species_Count_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Species_Quota_Protected_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Species_Newborn_Protected_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Species_Incubator_Protected_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Species_Sharing_Adjusted_Timeline, 0);
        m.defaultOnly(RuntimeVariable.DiversityTimeline, 0.0);
        m.defaultOnly(RuntimeVariable.Covered_Goals_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Remaining_Goals_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Fronts_Count_Timeline, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Events_Total, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Standard_Mutations, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Semantic_Mutations, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Standard_Crossovers, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Semantic_Crossovers, 0);
        m.defaultOnly(RuntimeVariable.Disruption_Semantic_Fallbacks, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Targets_Selected, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Targets_Mutation, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Targets_Crossover, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Calls_Dropped_Budget, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Calls_Dropped_InFlight, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Calls_Dropped_Time, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Calls_Failed, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Offspring_Parsed, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Offspring_Admitted, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Offspring_Survived, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Target_Hits, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Target_Improved, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_InFlight_Generations, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Candidates_StaleTarget, 0);
        m.defaultOnly(RuntimeVariable.LLM_Operator_Sidecar_Path, "");
        return m.build();
    }

    /** Publishes collected LLM statistics as EvoSuite runtime variables. */
    public void publishRuntimeVariables() {
        // Do not reset counters here: they are accumulated during the run and
        // must be published as-is at shutdown.
        Publication publication = new Publication(this, getFeatureSnapshots());
        for (Metric metric : METRICS) {
            if (metric.reader != null) {
                ClientServices.track(metric.variable, metric.reader.apply(publication));
            }
        }
    }

    /**
     * Sums the {@code SEMANTIC_MUTATION} and {@code SEMANTIC_CROSSOVER} feature
     * snapshots into the combined {@code LLM_Operator_*} call/token/latency
     * totals. The remaining {@code LLM_Operator_*} runtime variables (targets
     * selected, offspring outcomes, async drop counters, and the sidecar path)
     * are per-target counts published separately by the search loop.
     */
    static final class OperatorFeatureTotals {
        final long calls;
        final long inputTokens;
        final long cachedInputTokens;
        final long outputTokens;
        final long latencyMs;

        private OperatorFeatureTotals(long calls, long inputTokens, long cachedInputTokens,
                                      long outputTokens, long latencyMs) {
            this.calls = calls;
            this.inputTokens = inputTokens;
            this.cachedInputTokens = cachedInputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
        }

        static OperatorFeatureTotals of(Map<LlmFeature, FeatureSnapshot> snapshots) {
            long calls = 0L;
            long input = 0L;
            long cachedInput = 0L;
            long output = 0L;
            long latency = 0L;
            for (LlmFeature feature : LlmFeature.values()) {
                FeatureSnapshot snapshot = feature.isOperator() ? snapshots.get(feature) : null;
                if (snapshot == null) {
                    continue;
                }
                calls += snapshot.getCalls();
                input += snapshot.getInputTokens();
                cachedInput += snapshot.getCachedInputTokens();
                output += snapshot.getOutputTokens();
                latency += snapshot.getLatencyMs();
            }
            return new OperatorFeatureTotals(calls, input, cachedInput, output, latency);
        }
    }

    /**
     * Publishes the totals of metrics that other components track during the run, such as how often
     * an object-pool sequence was used. The master keeps the last value it receives for a variable,
     * so the end of a run without an LLM provider publishes these totals instead of the zero
     * defaults, and nothing else: the defaults would overwrite values the search emitted.
     */
    public static void publishRunTotals() {
        ClientServices.track(RuntimeVariable.Object_Pool_Sequence_Used,
                ObjectPoolManager.getSequenceUsageCount());
        ExecutableSnippetEngine snippets = ExecutableSnippetEngine.INSTANCE;
        ClientServices.track(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, snippets.getCompileFailures());
        ClientServices.track(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, snippets.getRuntimeFailures());
        ClientServices.track(RuntimeVariable.LLM_Fallback_Statement_Execution_Failures,
                snippets.getStatementExecutionFailures());
        ClientServices.track(RuntimeVariable.LLM_Fallback_Assertion_Evaluation_Failures,
                snippets.getAssertionEvaluationFailures());
    }

    /**
     * Initializes all LLM-related runtime variables to their zero/default values.
     * This ensures that requested statistics variables are present in the output
     * even if the corresponding features are disabled or never triggered.
     *
     * <p>Call it before the search, in every mode: the master keeps the last value it
     * receives, so values tracked later replace these defaults, whereas calling it after
     * the search would overwrite the timelines and counters the search emitted.
     *
     * <p>Does NOT reset the in-process accumulators (the static {@code AtomicLong}s
     * and per-type maps). Pre-search enrichment populates those before the GA's
     * shutdown hook runs; clearing them here would erase the counts recorded by
     * constant-pool, object-pool, and cast-class enrichers — see the
     * {@code shutdownLlmAssistance()} path in {@code GeneticAlgorithm}.
     */
    public static void initializeRuntimeVariables() {
        for (Metric metric : METRICS) {
            ClientServices.track(metric.variable, metric.defaultValue);
        }
        publishRunTotals();
        LlmPostProcessor.initializeRuntimeVariables();
    }

    private static void recordDiagnosticCandidateStage(DiagnosticStage stage,
                                                       Collection<ProblemCardType> cardTypes,
                                                       int candidateCount) {
        if (candidateCount <= 0) {
            return;
        }
        Collection<ProblemCardType> normalizedTypes = normalizeDiagnosticCardTypes(cardTypes);
        if (normalizedTypes.isEmpty()) {
            return;
        }
        stage.counters().addToTotal(candidateCount);
        for (ProblemCardType type : normalizedTypes) {
            stage.counters().add(type, candidateCount);
        }
    }

    private static Collection<ProblemCardType> normalizeDiagnosticCardTypes(Collection<ProblemCardType> cardTypes) {
        if (cardTypes == null || cardTypes.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        LinkedHashSet<ProblemCardType> uniqueTypes = new LinkedHashSet<>();
        for (ProblemCardType type : cardTypes) {
            if (type != null) {
                uniqueTypes.add(type);
            }
        }
        return uniqueTypes;
    }

    private static final class FeatureStats {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong cachedInput = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong latency = new AtomicLong();

        void recordSuccess(int in, int cachedIn, int out, long latencyMs) {
            calls.incrementAndGet();
            input.addAndGet(Math.max(0, in));
            cachedInput.addAndGet(Math.max(0, cachedIn));
            output.addAndGet(Math.max(0, out));
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        void recordRetryAttempt(long latencyMs) {
            calls.incrementAndGet();
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        void recordFailure(long latencyMs) {
            calls.incrementAndGet();
            failures.incrementAndGet();
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        void recordTimeout(long latencyMs) {
            calls.incrementAndGet();
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        /** Returns an immutable snapshot of this feature's statistics. */
        FeatureSnapshot snapshot() {
            return new FeatureSnapshot(calls.get(), failures.get(),
                    input.get(), cachedInput.get(), output.get(), latency.get());
        }
    }

    public static final class FeatureSnapshot {
        private final long calls;
        private final long failures;
        private final long inputTokens;
        private final long cachedInputTokens;
        private final long outputTokens;
        private final long latencyMs;

        /** Constructs a snapshot with per-feature call, failure, token, and latency counts. */
        public FeatureSnapshot(long calls, long failures,
                               long inputTokens, long cachedInputTokens, long outputTokens, long latencyMs) {
            this.calls = calls;
            this.failures = failures;
            this.inputTokens = inputTokens;
            this.cachedInputTokens = cachedInputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
        }

        public long getCalls() {
            return calls;
        }

        public long getFailures() {
            return failures;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public long getCachedInputTokens() {
            return cachedInputTokens;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public long getLatencyMs() {
            return latencyMs;
        }
    }
}
