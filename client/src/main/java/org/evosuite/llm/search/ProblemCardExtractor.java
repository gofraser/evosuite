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

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final ExtractorTraceSink traceSink;
    private final ProblemCardThresholds thresholds;

    public ProblemCardExtractor() {
        this(new ExtractorTraceSink.DiagnosticLoggerSink(logger), ProblemCardThresholds.defaults());
    }

    /** Test-friendly constructor allowing a custom trace sink (e.g. {@link ExtractorTraceSink#NOOP}). */
    public ProblemCardExtractor(ExtractorTraceSink traceSink) {
        this(traceSink, ProblemCardThresholds.defaults());
    }

    public ProblemCardExtractor(ExtractorTraceSink traceSink, ProblemCardThresholds thresholds) {
        this.traceSink = traceSink == null ? ExtractorTraceSink.NOOP : traceSink;
        this.thresholds = thresholds == null ? ProblemCardThresholds.defaults() : thresholds;
    }

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

        Set<String> coveredMethods = collectCoveredMethods(snapshot);
        Map<String, AttemptStats> methodStats =
                new MethodAttemptObserver(traceSink).observe(snapshot, goalsByMethod.keySet(), telemetry);
        Map<String, BranchGoalSignal> branchSignals = new BranchObserver().observe(uncoveredGoals, snapshot);
        Map<String, CdgDependencySignal> cdgSignals = new CdgObserver().observe(uncoveredGoals, snapshot);
        Map<String, TypeBarrierSignal> typeBarrierSignals =
                new TypeBarrierObserver(traceSink).observe(goalsByMethod, snapshot, telemetry);
        Map<String, OuterTypeAttemptSignal> outerTypeSignals = new OuterTypeObserver().observe(snapshot);
        Set<String> touchedTypes = new TouchedTypesObserver().observe(snapshot, coveredMethods);
        CardBuildContext context = new CardBuildContext(
                uncoveredGoals,
                snapshot,
                goalsByMethod,
                coveredMethods,
                touchedTypes,
                methodStats,
                branchSignals,
                cdgSignals,
                typeBarrierSignals,
                outerTypeSignals,
                persistentExceptionStats,
                thresholds,
                traceSink,
                telemetry,
                snapshot.size());

        List<ProblemCard> cards = new ArrayList<>();
        new TypeNeverAttemptedCardBuilder().emit(cards, context);
        new IndirectReachabilityCardBuilder().emit(cards, context);
        new UnreachedMethodCardBuilder().emit(cards, context);
        new TypeBarrierCardBuilder().emit(cards, context);
        new ExceptionBarrierCardBuilder().emit(cards, context);
        new StateDiversificationCardBuilder().emit(cards, context);
        new BranchPolarityCardBuilder().emit(cards, context);
        new CdgBottleneckCardBuilder().emit(cards, context);

        cards.sort(Comparator.comparingDouble(ProblemCard::getPriority).reversed()
                .thenComparingInt(card -> card.getType() == null
                        ? Integer.MAX_VALUE : card.getType().ordinal())
                .thenComparing(card -> {
                    String scope = card.getScopeKey() == null ? "" : card.getScopeKey();
                    String root = card.getRootCauseKey() == null ? "" : card.getRootCauseKey();
                    return scope + "\0" + root;
                }));
        traceExtractionSummary(goalsByMethod, coveredMethods, methodStats,
                typeBarrierSignals, outerTypeSignals, cards, telemetry);
        if (cards.size() <= maxCards) {
            return new ExtractionResult(cards, telemetry.snapshotRejectCounts(),
                    telemetry.snapshotCandidateCounts(thresholds.minAttemptsForExceptionBarrier()));
        }
        return new ExtractionResult(new ArrayList<>(cards.subList(0, maxCards)),
                telemetry.snapshotRejectCounts(),
                telemetry.snapshotCandidateCounts(thresholds.minAttemptsForExceptionBarrier()));
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

    private String coveredMethodKey(String coveredMethod) {
        return goalDescriptionMapper.describeQualifiedMethodOperation(coveredMethod).getExecutionKey();
    }

    private void traceExtractionSummary(Map<String, List<TestFitnessFunction>> goalsByMethod,
                                        Set<String> coveredMethods,
                                        Map<String, AttemptStats> methodStats,
                                        Map<String, TypeBarrierSignal> typeBarrierSignals,
                                        Map<String, OuterTypeAttemptSignal> outerTypeSignals,
                                        List<ProblemCard> cards,
                                        ExtractorTelemetry telemetry) {
        Map<ExtractorCandidateMetric, Integer> candidateCounts = telemetry == null
                ? Collections.<ExtractorCandidateMetric, Integer>emptyMap()
                : telemetry.snapshotCandidateCounts(thresholds.minAttemptsForExceptionBarrier());
        Map<ExtractorRejectReason, Integer> rejectCounts = telemetry == null
                ? Collections.<ExtractorRejectReason, Integer>emptyMap()
                : telemetry.snapshotRejectCounts();
        traceSink.trace("extraction_summary",
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
                    .append(String.format(Locale.ROOT, "%.3f", card.getPriority()));
        }
        sb.append("]");
        return sb.toString();
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

    // Stats / Signal / Evidence classes moved to sibling files in the same package
    // (AttemptStats, SignatureAttemptStats, ContextAttemptStats, UpstreamAttemptStats,
    //  ExceptionBarrier{Context,Signature}Evidence, UpstreamExceptionBarrierEvidence,
    //  OuterTypeAttemptSignal, TypeInvocationSignal, BranchGoalSignal, CdgDependencySignal,
    //  StateDiversificationContextEvidence, TypeBarrierSignal, MethodPromptContext).


}
