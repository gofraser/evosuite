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
import org.evosuite.llm.search.DiagnosticCardSelector;
import org.evosuite.llm.search.ExtractorCandidateMetric;
import org.evosuite.llm.search.ExtractorRejectReason;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong diagnosticCardsExtracted = new AtomicLong();
    private static final AtomicLong diagnosticCardsSelected = new AtomicLong();
    private static final AtomicLong diagnosticCardsDiscarded = new AtomicLong();
    private static final AtomicLong diagnosticCardsDeduplicated = new AtomicLong();
    private static final AtomicLong diagnosticCoverageGains = new AtomicLong();
    private static final AtomicLong diagnosticCandidatesPublished = new AtomicLong();
    private static final AtomicLong diagnosticCandidatesAdmitted = new AtomicLong();
    private static final AtomicLong diagnosticCandidatesSurvived = new AtomicLong();
    private static final AtomicLong diagnosticCoverageGainAttributionAmbiguous = new AtomicLong();
    private static final AtomicLong diagnosticCoverageGainAttributionAmbiguousGoals = new AtomicLong();
    private static final Map<ExtractorCandidateMetric, AtomicLong> diagnosticExtractorCandidates =
            new EnumMap<>(ExtractorCandidateMetric.class);
    private static final Map<ExtractorRejectReason, AtomicLong> diagnosticExtractorRejects =
            new EnumMap<>(ExtractorRejectReason.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCardsExtractedByType =
            new EnumMap<>(ProblemCardType.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCardsByType =
            new EnumMap<>(ProblemCardType.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCoverageGainsByType =
            new EnumMap<>(ProblemCardType.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCandidatesPublishedByType =
            new EnumMap<>(ProblemCardType.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCandidatesAdmittedByType =
            new EnumMap<>(ProblemCardType.class);
    private static final Map<ProblemCardType, AtomicLong> diagnosticCandidatesSurvivedByType =
            new EnumMap<>(ProblemCardType.class);

    static {
        for (ProblemCardType type : ProblemCardType.values()) {
            diagnosticCardsExtractedByType.put(type, new AtomicLong());
            diagnosticCardsByType.put(type, new AtomicLong());
            diagnosticCoverageGainsByType.put(type, new AtomicLong());
            diagnosticCandidatesPublishedByType.put(type, new AtomicLong());
            diagnosticCandidatesAdmittedByType.put(type, new AtomicLong());
            diagnosticCandidatesSurvivedByType.put(type, new AtomicLong());
        }
        for (ExtractorCandidateMetric metric : ExtractorCandidateMetric.values()) {
            diagnosticExtractorCandidates.put(metric, new AtomicLong());
        }
        for (ExtractorRejectReason reason : ExtractorRejectReason.values()) {
            diagnosticExtractorRejects.put(reason, new AtomicLong());
        }
    }

    /** Records the wall-clock time spent blocking on pre-search pool enrichment. */
    public static void recordEnrichmentElapsedMs(long elapsedMs) {
        recordPoolEnrichmentElapsedMs(elapsedMs);
    }

    /** Returns the pool-enrichment blocking time; retained for source compatibility. */
    public static long getEnrichmentElapsedMs() {
        return getPoolEnrichmentElapsedMs();
    }

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
        ClientServices.track(RuntimeVariable.LLM_Cast_Class_Suggestions, getCastClassSuggestions());
        ClientServices.track(RuntimeVariable.LLM_Cast_Class_Accepted, getCastClassesAccepted());
        ClientServices.track(RuntimeVariable.LLM_Constants_Added_SUT, getSutConstantsAdded());
        ClientServices.track(RuntimeVariable.LLM_Constants_Added_NonSUT, getNonSutConstantsAdded());
        ClientServices.track(RuntimeVariable.LLM_Object_Pool_Sequences_Added, getObjectPoolSequencesAdded());
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Validated,
                getInitialPopulationCandidatesValidated());
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Queued,
                getInitialPopulationCandidatesQueued());
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Injected,
                getInitialPopulationCandidatesInjected());
    }

    /** Records all extracted problem cards before selection/truncation. */
    public static void recordDiagnosticCardsExtracted(Collection<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        long added = 0L;
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            added++;
            AtomicLong byType = diagnosticCardsExtractedByType.get(card.getType());
            if (byType != null) {
                ClientServices.track(toExtractedRuntimeVariable(card.getType()), byType.incrementAndGet());
            }
        }
        if (added > 0L) {
            ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Extracted,
                    diagnosticCardsExtracted.addAndGet(added));
        }
    }

    /** Returns the total number of extracted diagnostic cards. */
    public static long getDiagnosticCardsExtracted() {
        return diagnosticCardsExtracted.get();
    }

    /** Returns extracted-card count for a specific problem-card type. */
    public static long getDiagnosticCardsExtracted(ProblemCardType type) {
        AtomicLong counter = diagnosticCardsExtractedByType.get(type);
        return counter == null ? 0L : counter.get();
    }

    /** Records selected problem cards from a diagnostic stagnation prompt. */
    public static void recordDiagnosticCardsSelected(Collection<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        long added = 0L;
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            added++;
            AtomicLong byType = diagnosticCardsByType.get(card.getType());
            if (byType != null) {
                ClientServices.track(toRuntimeVariable(card.getType()), byType.incrementAndGet());
            }
        }
        if (added > 0L) {
            ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Selected,
                    diagnosticCardsSelected.addAndGet(added));
        }
    }

    /** Returns the total number of selected diagnostic cards. */
    public static long getDiagnosticCardsSelected() {
        return diagnosticCardsSelected.get();
    }

    /** Returns selected-card count for a specific problem-card type. */
    public static long getDiagnosticCardsSelected(ProblemCardType type) {
        AtomicLong counter = diagnosticCardsByType.get(type);
        return counter == null ? 0L : counter.get();
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

    public static void recordDiagnosticCandidatesPublished(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(cardTypes, candidateCount,
                diagnosticCandidatesPublished,
                RuntimeVariable.LLM_Diagnostic_Candidates_Published,
                diagnosticCandidatesPublishedByType,
                StageRuntimeVariable.PUBLISHED);
    }

    public static long getDiagnosticCandidatesPublished() {
        return diagnosticCandidatesPublished.get();
    }

    public static long getDiagnosticCandidatesPublished(ProblemCardType type) {
        return getCounterValue(diagnosticCandidatesPublishedByType, type);
    }

    public static void recordDiagnosticCandidatesAdmitted(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(cardTypes, candidateCount,
                diagnosticCandidatesAdmitted,
                RuntimeVariable.LLM_Diagnostic_Candidates_Admitted,
                diagnosticCandidatesAdmittedByType,
                StageRuntimeVariable.ADMITTED);
    }

    public static long getDiagnosticCandidatesAdmitted() {
        return diagnosticCandidatesAdmitted.get();
    }

    public static long getDiagnosticCandidatesAdmitted(ProblemCardType type) {
        return getCounterValue(diagnosticCandidatesAdmittedByType, type);
    }

    public static void recordDiagnosticCandidatesSurvived(Collection<ProblemCardType> cardTypes, int candidateCount) {
        recordDiagnosticCandidateStage(cardTypes, candidateCount,
                diagnosticCandidatesSurvived,
                RuntimeVariable.LLM_Diagnostic_Candidates_Survived,
                diagnosticCandidatesSurvivedByType,
                StageRuntimeVariable.SURVIVED);
    }

    public static long getDiagnosticCandidatesSurvived() {
        return diagnosticCandidatesSurvived.get();
    }

    public static long getDiagnosticCandidatesSurvived(ProblemCardType type) {
        return getCounterValue(diagnosticCandidatesSurvivedByType, type);
    }

    public static void recordDiagnosticExtractorRejects(Map<ExtractorRejectReason, Integer> rejectCounts) {
        if (rejectCounts == null || rejectCounts.isEmpty()) {
            return;
        }
        for (Map.Entry<ExtractorRejectReason, Integer> entry : rejectCounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            AtomicLong counter = diagnosticExtractorRejects.get(entry.getKey());
            if (counter == null) {
                continue;
            }
            ClientServices.track(toExtractorRejectRuntimeVariable(entry.getKey()),
                    counter.addAndGet(entry.getValue()));
        }
    }

    public static void recordDiagnosticExtractorCandidates(Map<ExtractorCandidateMetric, Integer> candidateCounts) {
        if (candidateCounts == null || candidateCounts.isEmpty()) {
            return;
        }
        for (Map.Entry<ExtractorCandidateMetric, Integer> entry : candidateCounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            AtomicLong counter = diagnosticExtractorCandidates.get(entry.getKey());
            if (counter == null) {
                continue;
            }
            ClientServices.track(toExtractorCandidateRuntimeVariable(entry.getKey()),
                    counter.addAndGet(entry.getValue()));
        }
    }

    public static long getDiagnosticExtractorCandidates(ExtractorCandidateMetric metric) {
        AtomicLong counter = diagnosticExtractorCandidates.get(metric);
        return counter == null ? 0L : counter.get();
    }

    public static long getDiagnosticExtractorRejects(ExtractorRejectReason reason) {
        AtomicLong counter = diagnosticExtractorRejects.get(reason);
        return counter == null ? 0L : counter.get();
    }

    /** Resets diagnostic-card counters (test/utility use). */
    public static void resetDiagnosticCardCounters() {
        diagnosticCardsExtracted.set(0L);
        diagnosticCardsSelected.set(0L);
        diagnosticCardsDiscarded.set(0L);
        diagnosticCardsDeduplicated.set(0L);
        diagnosticCoverageGains.set(0L);
        diagnosticCandidatesPublished.set(0L);
        diagnosticCandidatesAdmitted.set(0L);
        diagnosticCandidatesSurvived.set(0L);
        diagnosticCoverageGainAttributionAmbiguous.set(0L);
        diagnosticCoverageGainAttributionAmbiguousGoals.set(0L);
        for (AtomicLong counter : diagnosticCardsExtractedByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticCardsByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticCoverageGainsByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticCandidatesPublishedByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticCandidatesAdmittedByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticCandidatesSurvivedByType.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticExtractorCandidates.values()) {
            counter.set(0L);
        }
        for (AtomicLong counter : diagnosticExtractorRejects.values()) {
            counter.set(0L);
        }
    }

    /**
     * Records uncovered-goal gains attributed to a diagnostic-card type.
     * Gains are ignored when type is null or gain <= 0.
     */
    public static void recordDiagnosticCoverageGain(ProblemCardType type, int gain) {
        if (type == null || gain <= 0) {
            return;
        }
        AtomicLong byType = diagnosticCoverageGainsByType.get(type);
        if (byType == null) {
            return;
        }
        long added = gain;
        ClientServices.track(toCoverageGainRuntimeVariable(type), byType.addAndGet(added));
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gains,
                diagnosticCoverageGains.addAndGet(added));
    }

    /** Returns the total number of uncovered-goal gains attributed to diagnostic cards. */
    public static long getDiagnosticCoverageGains() {
        return diagnosticCoverageGains.get();
    }

    /** Returns attributed uncovered-goal gains for a specific diagnostic-card type. */
    public static long getDiagnosticCoverageGains(ProblemCardType type) {
        AtomicLong counter = diagnosticCoverageGainsByType.get(type);
        return counter == null ? 0L : counter.get();
    }

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
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final Map<LlmFeature, FeatureStats> perFeature = new ConcurrentHashMap<>();

    /** Records a successful LLM call for the given feature, updating token and latency totals. */
    public void recordCall(LlmFeature feature, int inputTokenCount, int outputTokenCount, long latencyMs) {
        totalCalls.incrementAndGet();
        successfulCalls.incrementAndGet();
        inputTokens.addAndGet(Math.max(0, inputTokenCount));
        outputTokens.addAndGet(Math.max(0, outputTokenCount));
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats())
                .recordSuccess(inputTokenCount, outputTokenCount, latencyMs);
    }

    /** Records a failed LLM call for the given feature. */
    public void recordFailure(LlmFeature feature) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats()).recordFailure();
    }

    /** Records an LLM call timeout for the given feature. */
    public void recordTimeout(LlmFeature feature) {
        totalCalls.incrementAndGet();
        timedOutCalls.incrementAndGet();
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats()).recordTimeout();
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

    /** Publishes collected LLM statistics as EvoSuite runtime variables. */
    public void publishRuntimeVariables() {
        // Do not reset counters here: they are accumulated during the run and
        // must be published as-is at shutdown.
        String model = Properties.LLM_PROVIDER != Properties.LlmProvider.NONE
                ? Properties.LLM_MODEL : "";
        ClientServices.track(RuntimeVariable.LLM_Model, model);
        ClientServices.track(RuntimeVariable.LLM_Calls, getTotalCalls());
        ClientServices.track(RuntimeVariable.LLM_Calls_Succeeded, getSuccessfulCalls());
        ClientServices.track(RuntimeVariable.LLM_Calls_Failed, getFailedCalls());
        ClientServices.track(RuntimeVariable.LLM_Calls_TimedOut, getTimedOutCalls());
        ClientServices.track(RuntimeVariable.LLM_Input_Tokens, getInputTokens());
        ClientServices.track(RuntimeVariable.LLM_Output_Tokens, getOutputTokens());
        ClientServices.track(RuntimeVariable.LLM_Latency_Millis, getTotalLatencyMs());
        ClientServices.track(RuntimeVariable.LLM_Enrichment_Elapsed_Millis, getPoolEnrichmentElapsedMs());
        ClientServices.track(RuntimeVariable.LLM_Pool_Enrichment_Elapsed_Millis, getPoolEnrichmentElapsedMs());
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Elapsed_Millis,
                getInitialPopulationElapsedMs());
        ClientServices.track(RuntimeVariable.LLM_Total_Pre_Search_Elapsed_Millis,
                getTotalPreSearchElapsedMs());
        flushSeedingMetrics();
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Extracted, getDiagnosticCardsExtracted());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal,
                getDiagnosticExtractorRejects(ExtractorRejectReason.UPSTREAM_EXCEPTION_WITHOUT_BLOCKED_GOAL));
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_BlockedTypeMappingFailure,
                getDiagnosticExtractorRejects(ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE));
        for (ExtractorCandidateMetric metric : ExtractorCandidateMetric.values()) {
            ClientServices.track(toExtractorCandidateRuntimeVariable(metric), getDiagnosticExtractorCandidates(metric));
        }
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Selected, getDiagnosticCardsSelected());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Discarded, getDiagnosticCardsDiscarded());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Deduplicated,
                getDiagnosticCardsDeduplicated());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Published, getDiagnosticCandidatesPublished());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Admitted, getDiagnosticCandidatesAdmitted());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Survived, getDiagnosticCandidatesSurvived());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gains, getDiagnosticCoverageGains());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous,
                getDiagnosticCoverageGainAttributionAmbiguous());
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous_Goals,
                getDiagnosticCoverageGainAttributionAmbiguousGoals());
        for (ProblemCardType type : ProblemCardType.values()) {
            ClientServices.track(toExtractedRuntimeVariable(type), getDiagnosticCardsExtracted(type));
            ClientServices.track(toRuntimeVariable(type), getDiagnosticCardsSelected(type));
            ClientServices.track(toPublishedCandidateRuntimeVariable(type), getDiagnosticCandidatesPublished(type));
            ClientServices.track(toAdmittedCandidateRuntimeVariable(type), getDiagnosticCandidatesAdmitted(type));
            ClientServices.track(toSurvivedCandidateRuntimeVariable(type), getDiagnosticCandidatesSurvived(type));
            ClientServices.track(toCoverageGainRuntimeVariable(type), getDiagnosticCoverageGains(type));
        }
    }

    /**
     * Initializes all LLM-related runtime variables to their zero/default values.
     * This ensures that requested statistics variables are present in the output
     * even if the corresponding features are disabled or never triggered.
     *
     * <p>Safe to call regardless of whether LLM mode is enabled.
     *
     * <p>Does NOT reset the in-process accumulators (the static {@code AtomicLong}s
     * and per-type maps). Pre-search enrichment populates those before the GA's
     * shutdown hook runs; clearing them here would erase the counts recorded by
     * constant-pool, object-pool, and cast-class enrichers — see the
     * {@code shutdownLlmAssistance()} path in {@code GeneticAlgorithm}.
     */
    public static void initializeRuntimeVariables() {
        ClientServices.track(RuntimeVariable.LLM_Model, "");
        ClientServices.track(RuntimeVariable.LLM_Calls, 0);
        ClientServices.track(RuntimeVariable.LLM_Calls_Succeeded, 0);
        ClientServices.track(RuntimeVariable.LLM_Calls_Failed, 0);
        ClientServices.track(RuntimeVariable.LLM_Calls_TimedOut, 0);
        ClientServices.track(RuntimeVariable.LLM_Input_Tokens, 0);
        ClientServices.track(RuntimeVariable.LLM_Output_Tokens, 0);
        ClientServices.track(RuntimeVariable.LLM_Latency_Millis, 0);
        ClientServices.track(RuntimeVariable.LLM_Enrichment_Elapsed_Millis, 0);
        ClientServices.track(RuntimeVariable.LLM_Pool_Enrichment_Elapsed_Millis, 0);
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Elapsed_Millis, 0);
        ClientServices.track(RuntimeVariable.LLM_Total_Pre_Search_Elapsed_Millis, 0);
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Validated, 0);
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Queued, 0);
        ClientServices.track(RuntimeVariable.LLM_Initial_Population_Candidates_Injected, 0);
        ClientServices.track(RuntimeVariable.LLM_Cast_Class_Suggestions, 0);
        ClientServices.track(RuntimeVariable.LLM_Cast_Class_Accepted, 0);
        ClientServices.track(RuntimeVariable.LLM_Constants_Added_SUT, 0);
        ClientServices.track(RuntimeVariable.LLM_Constants_Added_NonSUT, 0);
        ClientServices.track(RuntimeVariable.LLM_Object_Pool_Sequences_Added, 0);
        ClientServices.track(RuntimeVariable.Object_Pool_Sequence_Used, 0);
        ClientServices.track(RuntimeVariable.LLM_Tests_Renamed, 0);
        ClientServices.track(RuntimeVariable.LLM_Test_Naming_Fallbacks, 0);
        ClientServices.track(RuntimeVariable.LLM_Variables_Renamed, 0);
        ClientServices.track(RuntimeVariable.LLM_Variable_Naming_Fallbacks, 0);
        ClientServices.track(RuntimeVariable.LLM_Assertions_Added, 0);
        ClientServices.track(RuntimeVariable.LLM_Assertion_Fallbacks, 0);
        ClientServices.track(RuntimeVariable.LLM_Literals_Niceified, 0);
        ClientServices.track(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, 0);
        ClientServices.track(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, 0);
        ClientServices.track(RuntimeVariable.LLM_Fallback_Statement_Execution_Failures, 0);
        ClientServices.track(RuntimeVariable.LLM_Fallback_Assertion_Evaluation_Failures, 0);
        ClientServices.track(RuntimeVariable.LLM_All_Candidates_Dropped_Execution, 0);
        ClientServices.track(RuntimeVariable.LLM_Semantic_Mutations, 0);
        ClientServices.track(RuntimeVariable.LLM_Semantic_Mutation_Fallbacks, 0);
        ClientServices.track(RuntimeVariable.LLM_Semantic_Crossovers, 0);
        ClientServices.track(RuntimeVariable.LLM_Semantic_Crossover_Fallbacks, 0);
        ClientServices.track(RuntimeVariable.LLM_Parsed_Statement_Ratio, 0.0);
        ClientServices.track(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline, 0.0);
        ClientServices.track(RuntimeVariable.LLM_StagnationCalls, 0);
        ClientServices.track(RuntimeVariable.LLM_AsyncProducer_DiagnosticCalls, 0);
        ClientServices.track(RuntimeVariable.LLM_AsyncProducer_Cards_Used, 0);
        ClientServices.track(RuntimeVariable.LLM_AsyncProducer_LoopIterations, 0);
        ClientServices.track(RuntimeVariable.LLM_AsyncProducer_Stopped_Reason, "");
        ClientServices.track(RuntimeVariable.LLM_StagnationPromptsSubmitted, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationResponsesReceived, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationTestsPublished, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationLatencyMsTotal, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationBlockedMsTotal, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationInFlightGenerations, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationSkippedBudget, 0);
        ClientServices.track(RuntimeVariable.LLM_StagnationSkippedInFlight, 0);
        ClientServices.track(RuntimeVariable.LLM_Injected_Candidates_OrphanFiltered, 0);
        ClientServices.track(RuntimeVariable.LLM_Injected_Candidates_Deduplicated, 0);
        ClientServices.track(RuntimeVariable.LLM_Injected_Candidates_Admitted, 0);
        ClientServices.track(RuntimeVariable.LLM_Injected_Candidates_Survived, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Extracted, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_BlockedTypeMappingFailure, 0);
        for (ExtractorCandidateMetric metric : ExtractorCandidateMetric.values()) {
            ClientServices.track(toExtractorCandidateRuntimeVariable(metric), 0);
        }
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Selected, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Discarded, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Cards_Deduplicated, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Published, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Admitted, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Candidates_Survived, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gains, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous, 0);
        ClientServices.track(RuntimeVariable.LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous_Goals, 0);
        for (ProblemCardType type : ProblemCardType.values()) {
            ClientServices.track(toExtractedRuntimeVariable(type), 0);
            ClientServices.track(toRuntimeVariable(type), 0);
            ClientServices.track(toPublishedCandidateRuntimeVariable(type), 0);
            ClientServices.track(toAdmittedCandidateRuntimeVariable(type), 0);
            ClientServices.track(toSurvivedCandidateRuntimeVariable(type), 0);
            ClientServices.track(toCoverageGainRuntimeVariable(type), 0);
        }
        ClientServices.track(RuntimeVariable.Species_Largest_Share_Timeline, 0.0);
        ClientServices.track(RuntimeVariable.Species_Count_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Quota_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Newborn_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Incubator_Protected_Timeline, 0);
        ClientServices.track(RuntimeVariable.Species_Sharing_Adjusted_Timeline, 0);
        ClientServices.track(RuntimeVariable.DiversityTimeline, 0.0);
        ClientServices.track(RuntimeVariable.Covered_Goals_Timeline, 0);
        ClientServices.track(RuntimeVariable.Remaining_Goals_Timeline, 0);
        ClientServices.track(RuntimeVariable.Fronts_Count_Timeline, 0);
        ClientServices.track(RuntimeVariable.Disruption_Events_Total, 0);
        ClientServices.track(RuntimeVariable.Disruption_Standard_Mutations, 0);
        ClientServices.track(RuntimeVariable.Disruption_Semantic_Mutations, 0);
        ClientServices.track(RuntimeVariable.Disruption_Standard_Crossovers, 0);
        ClientServices.track(RuntimeVariable.Disruption_Semantic_Crossovers, 0);
        ClientServices.track(RuntimeVariable.Disruption_Semantic_Fallbacks, 0);
    }

    private static RuntimeVariable toExtractedRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Cards_Extracted;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_Extracted_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static RuntimeVariable toExtractorRejectRuntimeVariable(ExtractorRejectReason reason) {
        if (reason == null) {
            return RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal;
        }
        switch (reason) {
            case UPSTREAM_EXCEPTION_WITHOUT_BLOCKED_GOAL:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal;
            case BLOCKED_TYPE_MAPPING_FAILURE:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_BlockedTypeMappingFailure;
            default:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal;
        }
    }

    private static RuntimeVariable toExtractorCandidateRuntimeVariable(ExtractorCandidateMetric metric) {
        if (metric == null) {
            return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionRepeatedSources;
        }
        switch (metric) {
            case UPSTREAM_EXCEPTION_REPEATED_SOURCES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionRepeatedSources;
            case UPSTREAM_EXCEPTION_BLOCKED_GOAL_METHODS:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionBlockedGoalMethods;
            case EXCEPTION_BARRIER_METHOD_CANDIDATES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierMethodCandidates;
            case EXCEPTION_BARRIER_CONTEXT_CANDIDATES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierContextCandidates;
            case EXCEPTION_BARRIER_UPSTREAM_CANDIDATES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierUpstreamCandidates;
            case EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierSuppressedInsufficientAttempts;
            case EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierSuppressedLowFailureRate;
            case MOCK_NEEDED_DEPENDENCY_CANDIDATES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_MockNeededDependencyCandidates;
            case MOCK_NEEDED_DEPENDENCY_SUPPRESSED_MATERIALIZED:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_MockNeededDependencySuppressedMaterialized;
            case ENVIRONMENT_BARRIER_CANDIDATES:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_EnvironmentBarrierCandidates;
            default:
                return RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionRepeatedSources;
        }
    }

    private static RuntimeVariable toRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Cards_Selected;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Cards_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Cards_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Cards_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Cards_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Cards_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Cards_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Cards_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static RuntimeVariable toCoverageGainRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Coverage_Gains;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Coverage_Gains_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static void recordDiagnosticCandidateStage(Collection<ProblemCardType> cardTypes,
                                                       int candidateCount,
                                                       AtomicLong totalCounter,
                                                       RuntimeVariable totalVariable,
                                                       Map<ProblemCardType, AtomicLong> byTypeCounters,
                                                       StageRuntimeVariable stage) {
        if (candidateCount <= 0) {
            return;
        }
        Collection<ProblemCardType> normalizedTypes = normalizeDiagnosticCardTypes(cardTypes);
        if (normalizedTypes.isEmpty()) {
            return;
        }
        ClientServices.track(totalVariable, totalCounter.addAndGet(candidateCount));
        for (ProblemCardType type : normalizedTypes) {
            AtomicLong counter = byTypeCounters.get(type);
            if (counter != null) {
                ClientServices.track(stage.toRuntimeVariable(type), counter.addAndGet(candidateCount));
            }
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

    private static long getCounterValue(Map<ProblemCardType, AtomicLong> counters, ProblemCardType type) {
        AtomicLong counter = counters.get(type);
        return counter == null ? 0L : counter.get();
    }

    private enum StageRuntimeVariable {
        PUBLISHED {
            @Override
            RuntimeVariable toRuntimeVariable(ProblemCardType type) {
                return toPublishedCandidateRuntimeVariable(type);
            }
        },
        ADMITTED {
            @Override
            RuntimeVariable toRuntimeVariable(ProblemCardType type) {
                return toAdmittedCandidateRuntimeVariable(type);
            }
        },
        SURVIVED {
            @Override
            RuntimeVariable toRuntimeVariable(ProblemCardType type) {
                return toSurvivedCandidateRuntimeVariable(type);
            }
        };

        abstract RuntimeVariable toRuntimeVariable(ProblemCardType type);
    }

    private static RuntimeVariable toPublishedCandidateRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Candidates_Published;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Published_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static RuntimeVariable toAdmittedCandidateRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static RuntimeVariable toSurvivedCandidateRuntimeVariable(ProblemCardType type) {
        if (type == null) {
            return RuntimeVariable.LLM_Diagnostic_Candidates_Survived;
        }
        switch (type) {
            case UNREACHED_METHOD:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_UnreachedMethod;
            case BRANCH_POLARITY_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_BranchPolarityGap;
            case STATE_DIVERSIFICATION_GAP:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_StateDiversificationGap;
            case EXCEPTION_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_ExceptionBarrier;
            case CDG_BOTTLENECK:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_CdgBottleneck;
            case INDIRECT_REACHABILITY_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_IndirectReachabilityBarrier;
            case TYPE_NEVER_ATTEMPTED:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_TypeNeverAttempted;
            case MOCK_NEEDED_DEPENDENCY:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_MockNeededDependency;
            case ENVIRONMENT_BARRIER:
                return RuntimeVariable.LLM_Diagnostic_Candidates_Survived_EnvironmentBarrier;
            default:
                throw new IllegalArgumentException("Unhandled diagnostic card type: " + type);
        }
    }

    private static final class FeatureStats {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong timedOut = new AtomicLong();
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong latency = new AtomicLong();

        void recordSuccess(int in, int out, long latencyMs) {
            calls.incrementAndGet();
            input.addAndGet(Math.max(0, in));
            output.addAndGet(Math.max(0, out));
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        void recordFailure() {
            calls.incrementAndGet();
            failures.incrementAndGet();
        }

        void recordTimeout() {
            calls.incrementAndGet();
            timedOut.incrementAndGet();
        }

        /** Returns an immutable snapshot of this feature's statistics. */
        FeatureSnapshot snapshot() {
            return new FeatureSnapshot(calls.get(), failures.get(), timedOut.get(),
                    input.get(), output.get(), latency.get());
        }
    }

    public static final class FeatureSnapshot {
        private final long calls;
        private final long failures;
        private final long timedOut;
        private final long inputTokens;
        private final long outputTokens;
        private final long latencyMs;

        /** Constructs a snapshot with per-feature call, failure, token, and latency counts. */
        public FeatureSnapshot(long calls, long failures, long timedOut, 
                               long inputTokens, long outputTokens, long latencyMs) {
            this.calls = calls;
            this.failures = failures;
            this.timedOut = timedOut;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
        }

        public long getCalls() {
            return calls;
        }

        public long getFailures() {
            return failures;
        }

        public long getTimedOut() {
            return timedOut;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public long getLatencyMs() {
            return latencyMs;
        }
    }
}
