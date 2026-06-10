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

import org.evosuite.llm.search.DiagnosticCardSelector;
import org.evosuite.llm.search.ExtractorCandidateMetric;
import org.evosuite.llm.search.ExtractorRejectReason;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmStatisticsDiagnosticAttributionTest {

    @Test
    void recordsAndResetsSeedingCounters() {
        LlmStatistics.resetSeedingCounters();

        LlmStatistics.recordSutConstantsAdded(2);
        LlmStatistics.recordNonSutConstantsAdded(3);
        LlmStatistics.recordObjectPoolSequencesAdded(4);
        LlmStatistics.recordCastClassSuggestions(5);
        LlmStatistics.recordCastClassesAccepted(6);
        LlmStatistics.recordSutConstantsAdded(0);
        LlmStatistics.recordCastClassesAccepted(-1);

        assertEquals(2L, LlmStatistics.getSutConstantsAdded());
        assertEquals(3L, LlmStatistics.getNonSutConstantsAdded());
        assertEquals(4L, LlmStatistics.getObjectPoolSequencesAdded());
        assertEquals(5L, LlmStatistics.getCastClassSuggestions());
        assertEquals(6L, LlmStatistics.getCastClassesAccepted());

        LlmStatistics.resetSeedingCounters();

        assertEquals(0L, LlmStatistics.getSutConstantsAdded());
        assertEquals(0L, LlmStatistics.getNonSutConstantsAdded());
        assertEquals(0L, LlmStatistics.getObjectPoolSequencesAdded());
        assertEquals(0L, LlmStatistics.getCastClassSuggestions());
        assertEquals(0L, LlmStatistics.getCastClassesAccepted());
    }

    @Test
    void recordsCoverageGainsByDiagnosticCardType() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.UNREACHED_METHOD, 2);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.BRANCH_POLARITY_GAP, 1);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.STATE_DIVERSIFICATION_GAP, 4);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.UNINSTANTIABLE_TYPE, 3);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.TYPE_NEVER_ATTEMPTED, 6);
        LlmStatistics.recordDiagnosticCoverageGain(null, 5);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.CDG_BOTTLENECK, 0);

        assertEquals(16L, LlmStatistics.getDiagnosticCoverageGains());
        assertEquals(2L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.UNREACHED_METHOD));
        assertEquals(1L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.BRANCH_POLARITY_GAP));
        assertEquals(4L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertEquals(3L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.UNINSTANTIABLE_TYPE));
        assertEquals(6L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.TYPE_NEVER_ATTEMPTED));
        assertEquals(0L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.CDG_BOTTLENECK));
    }

    @Test
    void recordsDiagnosticCandidateStagesByDiagnosticCardType() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCandidatesPublished(Arrays.asList(
                ProblemCardType.STATE_DIVERSIFICATION_GAP,
                ProblemCardType.EXCEPTION_BARRIER,
                ProblemCardType.STATE_DIVERSIFICATION_GAP), 2);
        LlmStatistics.recordDiagnosticCandidatesPublished(
                Collections.singletonList(ProblemCardType.TYPE_NEVER_ATTEMPTED), 4);
        LlmStatistics.recordDiagnosticCandidatesAdmitted(
                Collections.singletonList(ProblemCardType.STATE_DIVERSIFICATION_GAP), 1);
        LlmStatistics.recordDiagnosticCandidatesAdmitted(
                Collections.singletonList(ProblemCardType.TYPE_NEVER_ATTEMPTED), 3);
        LlmStatistics.recordDiagnosticCandidatesSurvived(
                Collections.singletonList(ProblemCardType.STATE_DIVERSIFICATION_GAP), 1);
        LlmStatistics.recordDiagnosticCandidatesSurvived(
                Collections.singletonList(ProblemCardType.TYPE_NEVER_ATTEMPTED), 2);

        assertEquals(6L, LlmStatistics.getDiagnosticCandidatesPublished());
        assertEquals(2L, LlmStatistics.getDiagnosticCandidatesPublished(
                ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertEquals(2L, LlmStatistics.getDiagnosticCandidatesPublished(
                ProblemCardType.EXCEPTION_BARRIER));
        assertEquals(4L, LlmStatistics.getDiagnosticCandidatesPublished(
                ProblemCardType.TYPE_NEVER_ATTEMPTED));
        assertEquals(4L, LlmStatistics.getDiagnosticCandidatesAdmitted());
        assertEquals(1L, LlmStatistics.getDiagnosticCandidatesAdmitted(
                ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertEquals(3L, LlmStatistics.getDiagnosticCandidatesAdmitted(
                ProblemCardType.TYPE_NEVER_ATTEMPTED));
        assertEquals(3L, LlmStatistics.getDiagnosticCandidatesSurvived());
        assertEquals(1L, LlmStatistics.getDiagnosticCandidatesSurvived(
                ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertEquals(2L, LlmStatistics.getDiagnosticCandidatesSurvived(
                ProblemCardType.TYPE_NEVER_ATTEMPTED));
    }

    @Test
    void recordsAmbiguousDiagnosticCoverageGainAttribution() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCoverageGainAttributionAmbiguous(Arrays.asList(
                ProblemCardType.STATE_DIVERSIFICATION_GAP,
                ProblemCardType.EXCEPTION_BARRIER), 3);
        LlmStatistics.recordDiagnosticCoverageGainAttributionAmbiguous(
                Collections.singletonList(ProblemCardType.STATE_DIVERSIFICATION_GAP), 5);

        assertEquals(1L, LlmStatistics.getDiagnosticCoverageGainAttributionAmbiguous());
        assertEquals(3L, LlmStatistics.getDiagnosticCoverageGainAttributionAmbiguousGoals());
    }

    @Test
    void recordsExtractedCardsByDiagnosticCardType() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCardsExtracted(Arrays.asList(
                problemCard(ProblemCardType.UNREACHED_METHOD),
                problemCard(ProblemCardType.UNREACHED_METHOD),
                problemCard(ProblemCardType.EXCEPTION_BARRIER),
                problemCard(ProblemCardType.STATE_DIVERSIFICATION_GAP),
                problemCard(ProblemCardType.STATE_SETUP_BARRIER),
                problemCard(ProblemCardType.INDIRECT_REACHABILITY_BARRIER),
                problemCard(ProblemCardType.TYPE_NEVER_ATTEMPTED)));
        LlmStatistics.recordDiagnosticCardsExtracted(Collections.singletonList(null));

        assertEquals(7L, LlmStatistics.getDiagnosticCardsExtracted());
        assertEquals(2L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.UNREACHED_METHOD));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.EXCEPTION_BARRIER));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.STATE_DIVERSIFICATION_GAP));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.STATE_SETUP_BARRIER));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.INDIRECT_REACHABILITY_BARRIER));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.TYPE_NEVER_ATTEMPTED));
        assertEquals(0L, LlmStatistics.getDiagnosticCardsExtracted(ProblemCardType.CDG_BOTTLENECK));
    }

    @Test
    void recordsSelectedCardsByDiagnosticCardType() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCardsSelected(Arrays.asList(
                problemCard(ProblemCardType.TYPE_NEVER_ATTEMPTED),
                problemCard(ProblemCardType.BRANCH_POLARITY_GAP)));

        assertEquals(2L, LlmStatistics.getDiagnosticCardsSelected());
        assertEquals(1L, LlmStatistics.getDiagnosticCardsSelected(ProblemCardType.TYPE_NEVER_ATTEMPTED));
        assertEquals(1L, LlmStatistics.getDiagnosticCardsSelected(ProblemCardType.BRANCH_POLARITY_GAP));
    }

    @Test
    void concreteProblemCardTypesDoNotMapToAggregateRuntimeVariables() throws Exception {
        assertPerTypeMapping("toExtractedRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Cards_Extracted,
                RuntimeVariable.LLM_Diagnostic_Cards_Extracted_TypeNeverAttempted);
        assertPerTypeMapping("toRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Cards_Selected,
                RuntimeVariable.LLM_Diagnostic_Cards_TypeNeverAttempted);
        assertPerTypeMapping("toPublishedCandidateRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Candidates_Published,
                RuntimeVariable.LLM_Diagnostic_Candidates_Published_TypeNeverAttempted);
        assertPerTypeMapping("toAdmittedCandidateRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Candidates_Admitted,
                RuntimeVariable.LLM_Diagnostic_Candidates_Admitted_TypeNeverAttempted);
        assertPerTypeMapping("toSurvivedCandidateRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Candidates_Survived,
                RuntimeVariable.LLM_Diagnostic_Candidates_Survived_TypeNeverAttempted);
        assertPerTypeMapping("toCoverageGainRuntimeVariable",
                RuntimeVariable.LLM_Diagnostic_Coverage_Gains,
                RuntimeVariable.LLM_Diagnostic_Coverage_Gains_TypeNeverAttempted);
    }

    @Test
    void recordsDiscardedAndDeduplicatedCards() {
        LlmStatistics.resetDiagnosticCardCounters();

        LlmStatistics.recordDiagnosticCardsDiscarded(Arrays.asList(
                new DiagnosticCardSelector.DiscardedCard(
                        problemCard(ProblemCardType.UNREACHED_METHOD),
                        DiagnosticCardSelector.DiscardReason.ROOT_CAUSE_OVERLAP),
                new DiagnosticCardSelector.DiscardedCard(
                        problemCard(ProblemCardType.EXCEPTION_BARRIER),
                        DiagnosticCardSelector.DiscardReason.FAMILY_DIVERSITY),
                new DiagnosticCardSelector.DiscardedCard(
                        problemCard(ProblemCardType.CDG_BOTTLENECK),
                        DiagnosticCardSelector.DiscardReason.SCORE_CUTOFF)));

        assertEquals(3L, LlmStatistics.getDiagnosticCardsDiscarded());
        assertEquals(1L, LlmStatistics.getDiagnosticCardsDeduplicated());
    }

    @Test
    void recordsExtractorRejectReasons() {
        LlmStatistics.resetDiagnosticCardCounters();

        EnumMap<ExtractorRejectReason, Integer> rejectCounts = new EnumMap<>(ExtractorRejectReason.class);
        rejectCounts.put(ExtractorRejectReason.UPSTREAM_EXCEPTION_WITHOUT_BLOCKED_GOAL, 2);
        rejectCounts.put(ExtractorRejectReason.UNINSTANTIABLE_PROGRESS_BEYOND_CREATION, 1);
        rejectCounts.put(ExtractorRejectReason.STATE_SETUP_DILUTED_SUCCESS, 3);
        rejectCounts.put(ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE, 4);

        LlmStatistics.recordDiagnosticExtractorRejects(rejectCounts);

        assertEquals(2L, LlmStatistics.getDiagnosticExtractorRejects(
                ExtractorRejectReason.UPSTREAM_EXCEPTION_WITHOUT_BLOCKED_GOAL));
        assertEquals(1L, LlmStatistics.getDiagnosticExtractorRejects(
                ExtractorRejectReason.UNINSTANTIABLE_PROGRESS_BEYOND_CREATION));
        assertEquals(3L, LlmStatistics.getDiagnosticExtractorRejects(
                ExtractorRejectReason.STATE_SETUP_DILUTED_SUCCESS));
        assertEquals(4L, LlmStatistics.getDiagnosticExtractorRejects(
                ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE));
    }

    @Test
    void recordsExtractorCandidateMetrics() {
        LlmStatistics.resetDiagnosticCardCounters();

        EnumMap<ExtractorCandidateMetric, Integer> candidateCounts =
                new EnumMap<>(ExtractorCandidateMetric.class);
        candidateCounts.put(ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES, 2);
        candidateCounts.put(ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES, 1);
        candidateCounts.put(ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE, 3);

        LlmStatistics.recordDiagnosticExtractorCandidates(candidateCounts);

        assertEquals(2L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES));
        assertEquals(1L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES));
        assertEquals(3L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE));

        LlmStatistics.resetDiagnosticCardCounters();

        assertEquals(0L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES));
        assertEquals(0L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.EXCEPTION_BARRIER_METHOD_CANDIDATES));
        assertEquals(0L, LlmStatistics.getDiagnosticExtractorCandidates(
                ExtractorCandidateMetric.UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE));
    }

    private static ProblemCard problemCard(ProblemCardType type) {
        return ProblemCard.builder(type)
                .title("title")
                .evidence(Collections.singletonList("e"))
                .relatedGoals(Collections.emptyList())
                .impact(0.7)
                .blockage(0.8)
                .confidence(0.9)
                .build();
    }

    private static void assertPerTypeMapping(String methodName,
                                             RuntimeVariable aggregateVariable,
                                             RuntimeVariable expectedTypeNeverAttemptedVariable) throws Exception {
        Method method = LlmStatistics.class.getDeclaredMethod(methodName, ProblemCardType.class);
        method.setAccessible(true);

        for (ProblemCardType type : ProblemCardType.values()) {
            RuntimeVariable mapped = (RuntimeVariable) method.invoke(null, type);
            if (type == ProblemCardType.TYPE_NEVER_ATTEMPTED) {
                assertEquals(expectedTypeNeverAttemptedVariable, mapped);
            }
            if (type != null) {
                org.junit.jupiter.api.Assertions.assertNotEquals(aggregateVariable, mapped,
                        type + " must have a dedicated per-type runtime variable");
            }
        }
    }
}
