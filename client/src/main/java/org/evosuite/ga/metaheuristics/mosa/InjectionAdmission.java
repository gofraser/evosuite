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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.search.AttemptTicket;
import org.evosuite.llm.search.AttributedCandidate;
import org.evosuite.llm.search.BlendChannel;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.ProblemCardLogRecorder;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.operator.OperatorAttemptLog;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.InjectionSource;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admission of externally produced candidates into a MOSA generation: draining the registered
 * sources into the union, tagging injected lineages, call-sequence deduplication, staleness and
 * problem-card attribution, the blend brood around admitted LLM candidates, the settlement of
 * the producing attempts, and lineage elitism after survival selection. Keeps the per-generation
 * counters the population timeline reads and the run totals published as runtime variables.
 *
 * <p>Not thread-safe: every call comes from the search thread.
 */
final class InjectionAdmission {

    private static final Logger logger = LoggerFactory.getLogger(InjectionAdmission.class);

    /** The parts of the search the admission reads and drives. */
    interface Host {
        /** The live population; lineage elitism replaces members in place. */
        List<TestChromosome> population();

        /** The current generation. */
        int generation();

        boolean isFinished();

        /** Evaluates a candidate through the search's own fitness path (archive, goal manager). */
        void evaluate(TestChromosome candidate);

        /** The search's crossover operator. */
        void crossOver(TestChromosome first, TestChromosome second) throws ConstructionFailedException;

        /** One draw of the search's selection function over the population. */
        TestChromosome selectFromPopulation();

        void removeUnusedVariables(TestChromosome chromosome);

        /** {@code candidates} without the ones holding orphaned variable references. */
        List<TestChromosome> filterOrphans(List<TestChromosome> candidates, String origin);

        /** A fresh, mutable copy of the uncovered goals. */
        Set<TestFitnessFunction> uncoveredGoals();

        /** Whether {@code chromosome} is within its species-incubation window. */
        boolean isIncubatorActive(TestChromosome chromosome);
    }

    /**
     * Counters of one generation's drain, reset when the next drain starts. The survivor counts
     * are filled when the generation is recorded.
     */
    static final class GenerationCounts {
        /** Candidates drained from all sources, before filtering or ranking. */
        int attempts;
        /** Source with the most drained candidates; null when none was tagged. */
        InjectionSource dominantAttemptSource;
        private Map<InjectionSource, Integer> attemptsBySource = Collections.emptyMap();
        int orphanFiltered;
        int deduplicated;
        int admitted;
        int survived;
        int blendVariantsBred;
        int blendVariantsAdmitted;
        int blendVariantsSurvived;
        int blendMutantAdmitted;
        int blendXoverGoalAdmitted;
        int blendXoverTournamentAdmitted;
        int blendEvalsSpent;

        /** Candidates drained from {@code source} in this generation. */
        int attemptsFrom(InjectionSource source) {
            Integer count = attemptsBySource.get(source);
            return count == null ? 0 : count;
        }
    }

    /** Run totals published as runtime variables; each one is tracked whenever it grows. */
    private static final class RunTotals {
        private final Map<RuntimeVariable, Long> totals = new EnumMap<>(RuntimeVariable.class);

        void add(RuntimeVariable variable, long delta) {
            long total = get(variable) + delta;
            totals.put(variable, total);
            ClientServices.track(variable, total);
        }

        long get(RuntimeVariable variable) {
            Long total = totals.get(variable);
            return total == null ? 0L : total;
        }
    }

    private final Host host;

    private GenerationCounts counts = new GenerationCounts();

    /**
     * Monotonic lineage id counter used to tag freshly injected candidates. Zero means "first
     * injected lineage in this run".
     */
    private long nextInjectionLineageId;

    private int lastRecordedInjectedSurvivorGeneration = Integer.MIN_VALUE;

    private final RunTotals totals = new RunTotals();

    InjectionAdmission(Host host) {
        this.host = host;
    }

    /** The counters of the most recent drain. */
    GenerationCounts lastGeneration() {
        return counts;
    }

    /** LLM_OPERATOR candidates whose target goals were all covered when they arrived, this run. */
    long getOperatorStaleTargetTotal() {
        return totals.get(RuntimeVariable.LLM_Operator_Candidates_StaleTarget);
    }

    /**
     * Drains all {@code externalCandidateSources}, evaluates fitness for each candidate, and
     * appends them to the given union list.
     *
     * <p>This is the single integration point for all external candidates (island immigrants, LLM
     * async producer, LLM stagnation, etc.). It also drains {@code pendingLsTests}, the
     * LS-improved tests staged by local search, which it empties.
     *
     * @param union the parent+offspring union to extend
     * @param pendingLsTests already evaluated local-search tests to admit as they are
     * @param externalCandidateSources the sources to drain, in order
     */
    void collect(List<TestChromosome> union, List<TestChromosome> pendingLsTests,
                 List<AbstractMOSA.ExternalCandidateSource> externalCandidateSources) {
        int attempts = 0;
        Map<InjectionSource, Integer> attemptCounts = new EnumMap<>(InjectionSource.class);
        Set<TestFitnessFunction> remainingUncoveredBeforeInjection = host.uncoveredGoals();
        // Immutable snapshot for staleness only: remainingUncoveredBeforeInjection is mutated by
        // coveredGoalsAndRemoveList as each candidate is processed, so two drained candidates
        // targeting the same live goal would otherwise see the second as stale just because the
        // first already covered it moments earlier — even though both arrived while it was still
        // uncovered. Taken lazily, before the first candidate of the generation is processed, so
        // the common generation with nothing drained does not copy the uncovered set at all.
        Set<TestFitnessFunction> uncoveredAtDrainStart = null;
        Set<String> seenInjectedCallSignatures = new HashSet<>();
        int deduplicatedInjectedCandidates = 0;
        // Only previously-injected LLM candidates seed the dedup set: a
        // GA-evolved union member that happens to share a call shape with an
        // LLM candidate must not block that candidate from being admitted.
        for (TestChromosome existing : union) {
            if (!isLlmInjectionSource(existing.getInjectionSource())) {
                continue;
            }
            String existingSignature = injectedCallSequenceSignature(existing);
            if (!existingSignature.isEmpty()) {
                seenInjectedCallSignatures.add(existingSignature);
            }
        }
        // Reset per-generation attempt observability for this drain step.
        counts = new GenerationCounts();
        lastRecordedInjectedSurvivorGeneration = Integer.MIN_VALUE;

        // Drain LS-improved tests staged by applyLocalSearch.
        // These have already been evaluated through calculateFitness,
        // so we add them directly to the union without re-evaluation.
        if (!pendingLsTests.isEmpty()) {
            attempts += pendingLsTests.size();
            attemptCounts.merge(InjectionSource.LOCAL_SEARCH, pendingLsTests.size(), Integer::sum);
            for (TestChromosome ls : pendingLsTests) {
                tagInjectedCandidate(ls, InjectionSource.LOCAL_SEARCH);
            }
            union.addAll(pendingLsTests);
            logger.debug("Injected {} LS-improved tests into union", pendingLsTests.size());
            pendingLsTests.clear();
        }

        for (AbstractMOSA.ExternalCandidateSource source : externalCandidateSources) {
            List<AttributedCandidate> drained;
            InjectionSource tag;
            try {
                drained = source.drainAttributed();
                if (drained == null || drained.isEmpty()) {
                    continue;
                }
                tag = source.injectionSource();
            } catch (Exception e) {
                logger.debug("External candidate source failed while draining; skipping", e);
                continue;
            }
            // From here on each drained ticket is owed a settlement, however processing goes, so
            // the closing below must run in a finally.
            List<TestChromosome> candidates = AttributedCandidate.candidatesOf(drained);
            Map<TestChromosome, InjectionAttemptMetadata> attemptMetadataByCandidate = metadataByCandidate(drained);
            attempts += candidates.size();
            if (tag != null) {
                attemptCounts.merge(tag, candidates.size(), Integer::sum);
            }
            Map<AttemptTicket, Integer> gainsByTicket = initializeAttemptOutcomeMap(drained);
            try {
                List<TestChromosome> safe = host.filterOrphans(
                        candidates, "external candidate source " + source.getClass().getSimpleName());
                if (isLlmInjectionSource(tag)) {
                    int orphanFiltered = Math.max(0, candidates.size() - safe.size());
                    if (orphanFiltered > 0) {
                        counts.orphanFiltered += orphanFiltered;
                        totals.add(RuntimeVariable.LLM_Injected_Candidates_OrphanFiltered, orphanFiltered);
                    }
                }
                for (TestChromosome candidate : safe) {
                    try {
                        boolean enableLlmDedup = isLlmInjectionSource(tag);
                        if (enableLlmDedup) {
                            String candidateSignature = injectedCallSequenceSignature(candidate);
                            if (!candidateSignature.isEmpty()
                                    && !seenInjectedCallSignatures.add(candidateSignature)) {
                                deduplicatedInjectedCandidates++;
                                counts.deduplicated++;
                                totals.add(RuntimeVariable.LLM_Injected_Candidates_Deduplicated, 1);
                                continue;
                            }
                        }
                        InjectionAttemptMetadata metadata = attemptMetadataByCandidate.get(candidate);
                        candidate.setDiagnosticCardTypes(metadata == null
                                ? Collections.<ProblemCardType>emptyList()
                                : metadata.getDiagnosticCardTypes());
                        if (tag != null) {
                            tagInjectedCandidate(candidate, tag);
                        }
                        if (uncoveredAtDrainStart == null) {
                            uncoveredAtDrainStart =
                                    Collections.unmodifiableSet(new HashSet<>(remainingUncoveredBeforeInjection));
                        }
                        maybeRecordStaleTarget(tag, metadata, uncoveredAtDrainStart);
                        host.evaluate(candidate);
                        recordTargetDistance(candidate, metadata);
                        creditGain(candidate, tag, metadata, remainingUncoveredBeforeInjection,
                                gainsByTicket);
                        boolean blendingActive = Properties.LLM_BLEND_ENABLED && isLlmInjectionSource(tag);
                        if (blendingActive) {
                            candidate.setBlendChannel(BlendChannel.RAW);
                        }
                        union.add(candidate);
                        if (metadata != null) {
                            metadata.getTicket().admitted(candidate, host.generation());
                        }
                        if (isLlmInjectionSource(tag)) {
                            counts.admitted++;
                            totals.add(RuntimeVariable.LLM_Injected_Candidates_Admitted, 1);
                        }
                        if (isLlmInjectionSource(tag) && metadata != null) {
                            LlmStatistics.recordDiagnosticCandidatesAdmitted(
                                    metadata.getDiagnosticCardTypes(), 1);
                        }
                        if (blendingActive) {
                            try {
                                blendInjectedCandidate(candidate, tag, metadata, union,
                                        remainingUncoveredBeforeInjection, gainsByTicket,
                                        seenInjectedCallSignatures);
                            } catch (Exception e) {
                                logger.debug("Blending failed for injected candidate; "
                                        + "raw admission unaffected", e);
                            }
                        }
                    } catch (Exception e) {
                        // This candidate's attempt keeps the 0 already seeded by
                        // initializeAttemptOutcomeMap; move on so one bad candidate cannot sink
                        // the rest of the batch (or, worse, the closing of the tickets below).
                        logger.debug("Failed to process an injected candidate from {}; skipping",
                                source.getClass().getSimpleName(), e);
                    }
                }
            } catch (Exception | LinkageError e) {
                // LinkageError: a source reflecting on SUT types (eg to build an LLM prompt) can
                // hit a class whose dependencies are not on the classpath. That source's
                // candidates are optional; losing them must not end the search.
                logger.debug("External candidate source failed; skipping", e);
            } finally {
                for (Map.Entry<AttemptTicket, Integer> gain : gainsByTicket.entrySet()) {
                    gain.getKey().close(gain.getValue());
                }
            }
        }
        if (deduplicatedInjectedCandidates > 0) {
            logger.debug("Deduplicated {} injected candidate(s) by call-sequence signature in generation {}",
                    deduplicatedInjectedCandidates, host.generation());
        }

        counts.attempts = attempts;
        counts.dominantAttemptSource = dominantSource(attemptCounts);
        counts.attemptsBySource = attemptCounts;
    }

    /** The source with the most candidates in {@code countsBySource}; the first one wins a tie, null when empty. */
    static InjectionSource dominantSource(Map<InjectionSource, Integer> countsBySource) {
        InjectionSource dominant = null;
        int dominantCount = 0;
        for (Map.Entry<InjectionSource, Integer> e : countsBySource.entrySet()) {
            if (e.getValue() > dominantCount) {
                dominantCount = e.getValue();
                dominant = e.getKey();
            }
        }
        return dominant;
    }

    private String injectedCallSequenceSignature(TestChromosome candidate) {
        if (candidate == null || candidate.getTestCase() == null) {
            return "";
        }
        TestCase testCase = candidate.getTestCase();
        StringBuilder signature = new StringBuilder();
        int callCount = 0;
        for (int i = 0; i < testCase.size(); i++) {
            Statement statement = testCase.getStatement(i);
            if (statement instanceof ConstructorStatement) {
                ConstructorStatement constructorStatement = (ConstructorStatement) statement;
                if (constructorStatement.getConstructor() == null
                        || constructorStatement.getConstructor().getDeclaringClass() == null) {
                    continue;
                }
                signature.append("NEW:")
                        .append(constructorStatement.getConstructor().getDeclaringClass().getName())
                        .append('#')
                        .append(constructorStatement.getNumParameters())
                        .append(literalArgumentsSignature(testCase, constructorStatement))
                        .append(';');
                callCount++;
                continue;
            }
            if (statement instanceof MethodStatement) {
                MethodStatement methodStatement = (MethodStatement) statement;
                if (methodStatement.getMethod() == null || methodStatement.getMethod().getMethod() == null) {
                    continue;
                }
                java.lang.reflect.Method method = methodStatement.getMethod().getMethod();
                signature.append("CALL:")
                        .append(method.getDeclaringClass().getName())
                        .append('.')
                        .append(method.getName())
                        .append('#')
                        .append(method.getParameterTypes().length)
                        .append(literalArgumentsSignature(testCase, methodStatement))
                        .append(';');
                callCount++;
            }
        }
        if (callCount == 0) {
            try {
                return "CODE:" + testCase.toCode().replaceAll("\\s+", " ").trim();
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return signature.toString();
    }

    /**
     * Renders the primitive/String literal values feeding the parameters of {@code statement}, so
     * that two calls with the same target but different argument literals (e.g. the single-axis
     * variants requested by diagnostic prompts) produce distinct dedup signatures. Parameters that
     * are themselves the result of another statement (constructor/method return values) are not
     * rendered here; their shape is already captured by that statement's own signature segment.
     */
    private String literalArgumentsSignature(
            TestCase testCase, EntityWithParametersStatement statement) {
        StringBuilder literals = new StringBuilder();
        for (VariableReference parameter : statement.getParameterReferences()) {
            int position = parameter.getStPosition();
            if (position < 0 || position >= testCase.size()) {
                continue;
            }
            Statement source = testCase.getStatement(position);
            if (source instanceof PrimitiveStatement) {
                literals.append('|').append(String.valueOf(((PrimitiveStatement<?>) source).getValue()));
            }
        }
        return literals.length() == 0 ? "" : "(" + literals + ")";
    }

    private void tagInjectedCandidate(TestChromosome candidate, InjectionSource source) {
        if (candidate == null || source == null) {
            return;
        }
        candidate.setInjectionSource(source);
        candidate.setInjectionLineageId(nextInjectionLineageId++);
        candidate.setInjectionGeneration(host.generation());
        boolean eligible = !Properties.SPECIES_INCUBATOR_ONLY_LLM_STAGNATION
                || source == InjectionSource.LLM_STAGNATION;
        candidate.setIncubatorEligible(Properties.SPECIES_INCUBATOR_ENABLED && eligible);
    }

    /**
     * Credits what an evaluated candidate covered: removes the newly covered goals from {@code
     * remainingUncovered}, attributes the gain to the problem-card types of its attempt, logs the
     * covered goals, and adds the gain to the attempt's ticket.
     *
     * @return the number of newly covered goals
     */
    private int creditGain(TestChromosome candidate, InjectionSource tag, InjectionAttemptMetadata metadata,
                           Set<TestFitnessFunction> remainingUncovered,
                           Map<AttemptTicket, Integer> gainsByTicket) {
        List<TestFitnessFunction> coveredNow = coveredGoalsAndRemoveList(candidate, remainingUncovered);
        int gained = coveredNow.size();
        maybeRecordDiagnosticAttribution(tag, metadata, gained);
        if (gained > 0 && shouldAttributeDiagnostics(tag, metadata)
                && ProblemCardLogRecorder.isEnabled()) {
            try {
                ProblemCardLogRecorder.getInstance().recordCoveredByInjection(
                        metadata.getAttemptId(), tag, host.generation(), coveredNow);
            } catch (Exception e) {
                logger.debug("Failed to log injection coverage for attempt {}",
                        metadata.getAttemptId(), e);
            }
        }
        if (metadata != null && metadata.getTicket().isTracked()) {
            gainsByTicket.merge(metadata.getTicket(), gained, Integer::sum);
        }
        return gained;
    }

    /**
     * Removes from {@code remainingUncovered} the goals newly covered by the candidate and returns
     * them — the goal identities feed the problem-card log's COVERED_BY_INJECTION rows.
     */
    private List<TestFitnessFunction> coveredGoalsAndRemoveList(
            TestChromosome candidate, Set<TestFitnessFunction> remainingUncovered) {
        if (candidate == null || remainingUncovered == null || remainingUncovered.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestFitnessFunction> covered = new ArrayList<>();
        Iterator<TestFitnessFunction> it = remainingUncovered.iterator();
        while (it.hasNext()) {
            TestFitnessFunction goal = it.next();
            if (goal != null && goal.isCovered(candidate)) {
                covered.add(goal);
                it.remove();
            }
        }
        return covered;
    }

    /**
     * True when an injection's coverage gains should be attributed to problem-card types: the
     * candidate came from a card-informed LLM call (SYNC stagnation or ASYNC producer) and its
     * metadata names the cards. Package-visible for unit testing.
     */
    static boolean shouldAttributeDiagnostics(
            InjectionSource tag, InjectionAttemptMetadata metadata) {
        return isLlmInjectionSource(tag)
                && metadata != null
                && metadata.getDiagnosticCardTypes() != null
                && !metadata.getDiagnosticCardTypes().isEmpty();
    }

    private void maybeRecordDiagnosticAttribution(InjectionSource tag,
                                                  InjectionAttemptMetadata metadata,
                                                  int gained) {
        if (!shouldAttributeDiagnostics(tag, metadata)) {
            return;
        }
        List<ProblemCardType> selectedTypes = metadata.getDiagnosticCardTypes();
        if (gained <= 0) {
            return;
        }
        if (selectedTypes.size() != 1) {
            LlmStatistics.recordDiagnosticCoverageGainAttributionAmbiguous(selectedTypes, gained);
            // Preserve per-card signal by attributing ambiguous multi-card gains
            // to the primary (highest-ranked) selected card type.
            LlmStatistics.recordDiagnosticCoverageGain(selectedTypes.get(0), gained);
            logger.debug("Ambiguous diagnostic attribution for attempt {}; "
                            + "selected card types={}, primary_type={}, gained_goals={}",
                    metadata.getAttemptId(), selectedTypes, selectedTypes.get(0), gained);
            return;
        }
        LlmStatistics.recordDiagnosticCoverageGain(selectedTypes.get(0), gained);
    }

    void recordFreshInjectedSurvivorMetrics() {
        if (lastRecordedInjectedSurvivorGeneration == host.generation()) {
            return;
        }
        lastRecordedInjectedSurvivorGeneration = host.generation();
        if (Properties.LLM_OPERATOR_MODE != Properties.LlmOperatorMode.OFF) {
            OperatorAttemptLog.getInstance().recordSurvivors(host.population(), host.generation());
        }
        int llmSurvivors = 0;
        int blendSurvivors = 0;
        int mutantSurvivors = 0;
        int xoverGoalSurvivors = 0;
        int xoverTournamentSurvivors = 0;
        for (TestChromosome candidate : host.population()) {
            if (candidate == null || candidate.getInjectionGeneration() != host.generation()) {
                continue;
            }
            InjectionSource source = candidate.getInjectionSource();
            if (!isLlmInjectionSource(source)) {
                continue;
            }
            BlendChannel channel = candidate.getBlendChannel();
            if (channel != null && channel != BlendChannel.RAW) {
                // Brood variants are counted separately so the raw survived
                // counter keeps its pre-blending meaning.
                blendSurvivors++;
                if (channel == BlendChannel.MUTANT) {
                    mutantSurvivors++;
                } else if (channel == BlendChannel.XOVER_GOAL) {
                    xoverGoalSurvivors++;
                } else {
                    xoverTournamentSurvivors++;
                }
                continue;
            }
            llmSurvivors++;
            LlmStatistics.recordDiagnosticCandidatesSurvived(
                    candidate.getDiagnosticCardTypes(), 1);
        }
        counts.survived = llmSurvivors;
        if (llmSurvivors > 0) {
            totals.add(RuntimeVariable.LLM_Injected_Candidates_Survived, llmSurvivors);
        }
        counts.blendVariantsSurvived = blendSurvivors;
        if (blendSurvivors > 0) {
            totals.add(RuntimeVariable.LLM_Blend_Variants_Survived, blendSurvivors);
        }
        if (mutantSurvivors > 0) {
            totals.add(RuntimeVariable.LLM_Blend_Mutant_Survived, mutantSurvivors);
        }
        if (xoverGoalSurvivors > 0) {
            totals.add(RuntimeVariable.LLM_Blend_XoverGoal_Survived, xoverGoalSurvivors);
        }
        if (xoverTournamentSurvivors > 0) {
            totals.add(RuntimeVariable.LLM_Blend_XoverTournament_Survived, xoverTournamentSurvivors);
        }
    }

    private static boolean isLlmInjectionSource(InjectionSource source) {
        return source == InjectionSource.LLM_STAGNATION || source == InjectionSource.LLM_ASYNC
                || source == InjectionSource.LLM_OPERATOR;
    }

    /**
     * Encodes this generation's per-channel union admissions for the species timeline sidecar, e.g.
     * {@code raw:2|mut:1|xg:1|xt:0}. Empty when blending is disabled, so pre-blending rows stay
     * unchanged.
     */
    String encodeBlendChannelAdmissions() {
        if (!Properties.LLM_BLEND_ENABLED) {
            return "";
        }
        return "raw:" + counts.admitted
                + "|mut:" + counts.blendMutantAdmitted
                + "|xg:" + counts.blendXoverGoalAdmitted
                + "|xt:" + counts.blendXoverTournamentAdmitted;
    }

    /**
     * Counts a drained LLM_ASYNC or LLM_OPERATOR candidate whose prompt-time target goals were
     * all covered by the time it arrived, into that source's own total. Supports the staleness
     * analysis of asynchronous injection; sync candidates are exempt because their results land
     * in the same generation that prompted them.
     */
    private void maybeRecordStaleTarget(
            InjectionSource tag,
            InjectionAttemptMetadata metadata,
            Set<TestFitnessFunction> remainingUncovered) {
        if ((tag != InjectionSource.LLM_ASYNC && tag != InjectionSource.LLM_OPERATOR) || metadata == null
                || metadata.getTargetGoals().isEmpty() || remainingUncovered == null) {
            return;
        }
        for (TestFitnessFunction goal : metadata.getTargetGoals()) {
            if (goal != null && remainingUncovered.contains(goal)) {
                return;
            }
        }
        if (tag == InjectionSource.LLM_ASYNC) {
            totals.add(RuntimeVariable.LLM_Async_Candidates_StaleTarget, 1);
        } else {
            totals.add(RuntimeVariable.LLM_Operator_Candidates_StaleTarget, 1);
        }
    }

    /**
     * Breeds a brood of variants around a freshly admitted LLM candidate and admits the best few to
     * the union ("evaluate broadly, admit narrowly").
     *
     * <p>All variants are evaluated through {@link Host#evaluate}, so any goal a variant covers
     * is banked in the archive at evaluation time — discarding a variant afterwards loses nothing.
     * Variants share the raw candidate's injection lineage (no fresh lineage ids), so incubation,
     * lineage elitism and attempt attribution treat raw + blends as one lineage.
     */
    private void blendInjectedCandidate(
            TestChromosome rawCandidate,
            InjectionSource tag,
            InjectionAttemptMetadata metadata,
            List<TestChromosome> union,
            Set<TestFitnessFunction> remainingUncovered,
            Map<AttemptTicket, Integer> gainsByTicket,
            Set<String> seenInjectedCallSignatures) {
        if (counts.blendEvalsSpent >= Properties.LLM_BLEND_MAX_EVALS_PER_GEN || host.isFinished()) {
            return;
        }

        TestFitnessFunction targetGoal = resolveBlendTargetGoal(rawCandidate, metadata,
                remainingUncovered);
        List<TestChromosome> brood = new ArrayList<>();

        // Mutation burst: a one-shot (1+lambda) sample around the injection point.
        for (int i = 0; i < Properties.LLM_BLEND_MUTANTS; i++) {
            try {
                TestChromosome mutant = rawCandidate.clone();
                mutant.mutate();
                if (!mutant.isChanged()) {
                    continue;
                }
                brood.add(prepareBlendVariant(mutant, rawCandidate, null, BlendChannel.MUTANT));
            } catch (Exception e) {
                logger.debug("Blend mutant construction failed", e);
            }
        }

        // Goal-directed crossover: partner is the population's best individual
        // for the target goal. Falls back to the tournament channel when no
        // goal or partner resolves.
        int goalSlots = Properties.LLM_BLEND_GOAL_CROSSOVERS;
        int tournamentSlots = Properties.LLM_BLEND_TOURNAMENT_CROSSOVERS;
        if (goalSlots > 0) {
            TestChromosome goalPartner = targetGoal == null ? null : resolveGoalPartner(targetGoal);
            if (goalPartner == null) {
                totals.add(RuntimeVariable.LLM_Blend_GoalPartner_FallbackTournament, 1);
                tournamentSlots += goalSlots;
            } else {
                totals.add(RuntimeVariable.LLM_Blend_GoalPartner_Resolved, 1);
                // One crossover call yields both directions (candidate-prefix
                // and partner-prefix).
                int produced = 0;
                int attempts = (goalSlots + 1) / 2;
                for (int a = 0; a < attempts && produced < goalSlots; a++) {
                    TestChromosome o1 = rawCandidate.clone();
                    TestChromosome o2 = goalPartner.clone();
                    try {
                        host.crossOver(o1, o2);
                    } catch (ConstructionFailedException | RuntimeException e) {
                        totals.add(RuntimeVariable.LLM_Blend_Crossover_Failed, 1);
                        continue;
                    }
                    brood.add(prepareBlendVariant(o1, rawCandidate, goalPartner,
                            BlendChannel.XOVER_GOAL));
                    produced++;
                    if (produced < goalSlots) {
                        brood.add(prepareBlendVariant(o2, rawCandidate, goalPartner,
                                BlendChannel.XOVER_GOAL));
                        produced++;
                    }
                }
            }
        }

        // Tournament crossover: diversity channel against the local-optimum
        // failure mode of always pairing with the same champion.
        for (int i = 0; i < tournamentSlots; i++) {
            TestChromosome partner = resolveTournamentPartner();
            if (partner == null) {
                break;
            }
            TestChromosome o1 = rawCandidate.clone();
            TestChromosome o2 = partner.clone();
            try {
                host.crossOver(o1, o2);
            } catch (ConstructionFailedException | RuntimeException e) {
                totals.add(RuntimeVariable.LLM_Blend_Crossover_Failed, 1);
                continue;
            }
            // Keep only the candidate-prefix direction for this channel.
            brood.add(prepareBlendVariant(o1, rawCandidate, partner,
                    BlendChannel.XOVER_TOURNAMENT));
        }

        if (brood.isEmpty()) {
            return;
        }
        counts.blendVariantsBred += brood.size();
        totals.add(RuntimeVariable.LLM_Blend_Variants_Bred, brood.size());

        // Evaluate within budget; archive capture happens here for every
        // variant, before the admission decision below.
        List<TestChromosome> safeBrood = host.filterOrphans(brood, "LLM blend brood");
        // Identity map: TestChromosome.equals is structural, and distinct
        // variants can share identical test content (e.g. a no-op crossover
        // direction); they must not collapse into one entry.
        Map<TestChromosome, Integer> gainedByVariant = new IdentityHashMap<>();
        List<TestChromosome> evaluatedOrder = new ArrayList<>();
        for (TestChromosome variant : safeBrood) {
            if (host.isFinished()
                    || counts.blendEvalsSpent >= Properties.LLM_BLEND_MAX_EVALS_PER_GEN) {
                break;
            }
            try {
                host.evaluate(variant);
            } catch (Exception e) {
                logger.debug("Blend variant evaluation failed", e);
                continue;
            }
            counts.blendEvalsSpent++;
            totals.add(RuntimeVariable.LLM_Blend_Evals_Spent, 1);
            int gained = creditGain(variant, tag, metadata, remainingUncovered, gainsByTicket);
            gainedByVariant.put(variant, gained);
            evaluatedOrder.add(variant);
        }
        if (gainedByVariant.isEmpty()) {
            return;
        }

        // Brood admission: new coverage first, then strict improvement on the
        // target goal; everything else is discarded (already archived).
        final double rawTargetFitness = targetGoal == null
                ? Double.MAX_VALUE : rawCandidate.getFitness(targetGoal);
        final TestFitnessFunction orderGoal = targetGoal;
        List<TestChromosome> ordered = new ArrayList<>(evaluatedOrder);
        ordered.sort((a, b) -> {
            int byGain = Integer.compare(gainedByVariant.get(b), gainedByVariant.get(a));
            if (byGain != 0 || orderGoal == null) {
                return byGain;
            }
            return Double.compare(a.getFitness(orderGoal), b.getFitness(orderGoal));
        });
        int admitted = 0;
        for (TestChromosome variant : ordered) {
            if (admitted >= Properties.LLM_BLEND_MAX_ADMITTED_VARIANTS) {
                break;
            }
            boolean qualifies = gainedByVariant.get(variant) > 0
                    || (targetGoal != null && variant.getFitness(targetGoal) < rawTargetFitness);
            if (!qualifies) {
                continue;
            }
            admitBlendVariant(variant, union, seenInjectedCallSignatures);
            admitted++;
        }
    }

    /**
     * Re-tags a brood variant after clone() cleared all injection fields: same source and lineage
     * as the raw candidate (no fresh lineage id), current generation, inherited card types, plus
     * channel and descent marks.
     */
    private TestChromosome prepareBlendVariant(
            TestChromosome variant,
            TestChromosome rawCandidate,
            TestChromosome partner,
            BlendChannel channel) {
        host.removeUnusedVariables(variant);
        variant.setInjectionSource(rawCandidate.getInjectionSource());
        variant.setInjectionLineageId(rawCandidate.getInjectionLineageId());
        variant.setInjectionGeneration(host.generation());
        variant.setIncubatorEligible(rawCandidate.isIncubatorEligible());
        variant.setDiagnosticCardTypes(rawCandidate.getDiagnosticCardTypes());
        variant.setBlendChannel(channel);
        variant.addDescentLineages(rawCandidate.effectiveLineages());
        if (partner != null) {
            variant.addDescentLineages(partner.effectiveLineages());
        }
        return variant;
    }

    private void admitBlendVariant(TestChromosome variant,
                                   List<TestChromosome> union,
                                   Set<String> seenInjectedCallSignatures) {
        union.add(variant);
        String signature = injectedCallSequenceSignature(variant);
        if (!signature.isEmpty()) {
            seenInjectedCallSignatures.add(signature);
        }
        counts.blendVariantsAdmitted++;
        totals.add(RuntimeVariable.LLM_Blend_Variants_Admitted, 1);
        BlendChannel channel = variant.getBlendChannel();
        if (channel == BlendChannel.MUTANT) {
            counts.blendMutantAdmitted++;
            totals.add(RuntimeVariable.LLM_Blend_Mutant_Admitted, 1);
        } else if (channel == BlendChannel.XOVER_GOAL) {
            counts.blendXoverGoalAdmitted++;
            totals.add(RuntimeVariable.LLM_Blend_XoverGoal_Admitted, 1);
        } else if (channel == BlendChannel.XOVER_TOURNAMENT) {
            counts.blendXoverTournamentAdmitted++;
            totals.add(RuntimeVariable.LLM_Blend_XoverTournament_Admitted, 1);
        }
    }

    /**
     * Resolves the goal a blend should optimize for: the candidate's closest-miss goal among the
     * metadata target goals still uncovered, falling back to its closest miss over all remaining
     * uncovered goals. Null when the candidate is equally far from everything (e.g. crashed).
     */
    private TestFitnessFunction resolveBlendTargetGoal(
            TestChromosome rawCandidate,
            InjectionAttemptMetadata metadata,
            Set<TestFitnessFunction> remainingUncovered) {
        TestFitnessFunction best = null;
        double bestFitness = Double.MAX_VALUE;
        if (metadata != null) {
            for (TestFitnessFunction goal : metadata.getTargetGoals()) {
                if (goal == null || remainingUncovered == null
                        || !remainingUncovered.contains(goal)) {
                    continue;
                }
                double fitness = safeGoalFitness(rawCandidate, goal);
                if (fitness > 0.0 && fitness < bestFitness) {
                    bestFitness = fitness;
                    best = goal;
                }
            }
        }
        if (best != null || remainingUncovered == null) {
            return best;
        }
        for (TestFitnessFunction goal : remainingUncovered) {
            if (goal == null) {
                continue;
            }
            double fitness = safeGoalFitness(rawCandidate, goal);
            if (fitness > 0.0 && fitness < bestFitness) {
                bestFitness = fitness;
                best = goal;
            }
        }
        return best;
    }

    /**
     * Population member with the lowest fitness on the target goal — the GA's best partial progress
     * toward it. Excludes members that are themselves unproven injected material (LLM-tagged,
     * incubating, or under lineage elitism). Ties broken by smaller test size.
     */
    private TestChromosome resolveGoalPartner(TestFitnessFunction goal) {
        TestChromosome best = null;
        double bestFitness = Double.MAX_VALUE;
        int bestSize = Integer.MAX_VALUE;
        for (TestChromosome tc : host.population()) {
            if (tc == null || isLlmInjectionSource(tc.getInjectionSource())
                    || host.isIncubatorActive(tc) || isLineageElitismActive(tc)) {
                continue;
            }
            double fitness = safeGoalFitness(tc, goal);
            if (fitness >= Double.MAX_VALUE) {
                continue;
            }
            int size = tc.size();
            if (fitness < bestFitness || (fitness == bestFitness && size < bestSize)) {
                bestFitness = fitness;
                bestSize = size;
                best = tc;
            }
        }
        return best;
    }

    private TestChromosome resolveTournamentPartner() {
        if (host.population().isEmpty()) {
            return null;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            TestChromosome partner;
            try {
                partner = host.selectFromPopulation();
            } catch (Exception e) {
                logger.debug("Tournament partner selection failed", e);
                return null;
            }
            if (partner != null && !isLlmInjectionSource(partner.getInjectionSource())
                    && !host.isIncubatorActive(partner) && !isLineageElitismActive(partner)) {
                return partner;
            }
        }
        return null;
    }

    private double safeGoalFitness(TestChromosome tc, TestFitnessFunction goal) {
        try {
            return tc.getFitness(goal);
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * True while an injected lineage member is within its lineage-elitism protection window (the
     * speciation-free incubation analogue of {@link Host#isIncubatorActive}).
     */
    private boolean isLineageElitismActive(TestChromosome tc) {
        if (tc == null || Properties.LLM_LINEAGE_ELITISM_GENERATIONS <= 0) {
            return false;
        }
        if (tc.getInjectionLineageId() < 0L) {
            return false;
        }
        int injectedAt = tc.getInjectionGeneration();
        if (injectedAt < 0) {
            return false;
        }
        int age = Math.max(0, host.generation() - injectedAt);
        return age <= Properties.LLM_LINEAGE_ELITISM_GENERATIONS;
    }

    /**
     * Seeds a zero gain for every tracked ticket among {@code drained}, in drain order, so each
     * one is closed even when its candidate never gets evaluated.
     */
    private static Map<AttemptTicket, Integer> initializeAttemptOutcomeMap(List<AttributedCandidate> drained) {
        Map<AttemptTicket, Integer> gainsByTicket = new LinkedHashMap<>();
        for (AttributedCandidate entry : drained) {
            InjectionAttemptMetadata metadata = entry.getMetadata();
            if (metadata != null && metadata.getTicket().isTracked()) {
                gainsByTicket.putIfAbsent(metadata.getTicket(), 0);
            }
        }
        return gainsByTicket;
    }

    /**
     * Records the evaluated distance to the prompted goal on the attempt-log row of {@code
     * candidate}'s ticket. Only operator attempts own such a row.
     */
    private static void recordTargetDistance(TestChromosome candidate, InjectionAttemptMetadata metadata) {
        if (metadata == null || !metadata.getTicket().hasLogRow()) {
            return;
        }
        List<TestFitnessFunction> targetGoals = metadata.getTargetGoals();
        metadata.getTicket().evaluated(targetGoals.isEmpty() ? Double.NaN : candidate.getFitness(targetGoals.get(0)));
    }

    /** Indexes the attribution of {@code drained} by candidate identity, skipping entries without one. */
    private static Map<TestChromosome, InjectionAttemptMetadata> metadataByCandidate(
            List<AttributedCandidate> drained) {
        Map<TestChromosome, InjectionAttemptMetadata> byCandidate = new IdentityHashMap<>();
        for (AttributedCandidate entry : drained) {
            if (entry.getMetadata() != null) {
                byCandidate.put(entry.getCandidate(), entry.getMetadata());
            }
        }
        return byCandidate;
    }

    /**
     * Speciation-free incubation: for {@code LLM_LINEAGE_ELITISM_GENERATIONS} generations after
     * injection, every injected lineage keeps its best member in the population. When survival
     * selection dropped a whole active lineage, its best union member (raw candidate or brood
     * variant — they share the lineage id) is re-inserted in place of the worst-ranked unprotected
     * non-front-0 member. Total protected members never exceed {@code
     * LLM_LINEAGE_ELITISM_MAX_FRACTION} of the population; youngest lineages are re-inserted first,
     * so the oldest lose protection first at the cap. Independent of the speciation flags. On any
     * failure the population is left exactly as survival selection produced it.
     *
     * @param union the ranked parent+offspring+injected union of this generation
     */
    void applyLineageElitism(List<TestChromosome> union) {
        if (Properties.LLM_LINEAGE_ELITISM_GENERATIONS <= 0
                || union == null || union.isEmpty() || host.population().isEmpty()) {
            return;
        }
        try {
            Map<Long, TestChromosome> bestByLineage = new LinkedHashMap<>();
            for (TestChromosome tc : union) {
                if (!isLineageElitismActive(tc)) {
                    continue;
                }
                long lineage = tc.getInjectionLineageId();
                TestChromosome current = bestByLineage.get(lineage);
                if (current == null || isBetterRanked(tc, current)) {
                    bestByLineage.put(lineage, tc);
                }
            }
            if (bestByLineage.isEmpty()) {
                return;
            }

            Set<Long> representedLineages = new HashSet<>();
            Set<TestChromosome> populationIdentity =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            int protectedCount = 0;
            for (TestChromosome tc : host.population()) {
                populationIdentity.add(tc);
                if (isLineageElitismActive(tc)) {
                    representedLineages.add(tc.getInjectionLineageId());
                    protectedCount++;
                }
            }
            int maxProtected = (int) Math.floor(
                    Properties.LLM_LINEAGE_ELITISM_MAX_FRACTION * host.population().size());

            List<Map.Entry<Long, TestChromosome>> missingLineages = new ArrayList<>();
            for (Map.Entry<Long, TestChromosome> entry : bestByLineage.entrySet()) {
                if (!representedLineages.contains(entry.getKey())
                        && !populationIdentity.contains(entry.getValue())) {
                    missingLineages.add(entry);
                }
            }
            missingLineages.sort((a, b) -> Integer.compare(
                    b.getValue().getInjectionGeneration(),
                    a.getValue().getInjectionGeneration()));

            int reinserted = 0;
            for (Map.Entry<Long, TestChromosome> entry : missingLineages) {
                if (protectedCount >= maxProtected) {
                    break;
                }
                int evictIndex = findLineageElitismEvictionIndex();
                if (evictIndex < 0) {
                    break;
                }
                host.population().set(evictIndex, entry.getValue());
                populationIdentity.add(entry.getValue());
                protectedCount++;
                reinserted++;
            }
            if (reinserted > 0) {
                totals.add(RuntimeVariable.LLM_Lineage_Elitism_Reinserted, reinserted);
                logger.debug("Lineage elitism re-inserted {} lineage member(s) in generation {}",
                        reinserted, host.generation());
            }
        } catch (Exception e) {
            logger.debug("Lineage elitism failed; population left as selected", e);
        }
    }

    /** Lower rank wins; within a rank, larger crowding distance wins. */
    private static boolean isBetterRanked(TestChromosome a, TestChromosome b) {
        if (a.getRank() != b.getRank()) {
            return a.getRank() < b.getRank();
        }
        return a.getDistance() > b.getDistance();
    }

    /**
     * Index of the worst-ranked population member that is neither in front 0 (rank 0 — never
     * evicted) nor itself lineage-protected; -1 when nothing is evictable. Worst = highest rank,
     * ties broken by smallest crowding distance.
     */
    private int findLineageElitismEvictionIndex() {
        int worstIndex = -1;
        int worstRank = 0;
        double worstDistance = Double.MAX_VALUE;
        for (int i = 0; i < host.population().size(); i++) {
            TestChromosome tc = host.population().get(i);
            if (tc == null || tc.getRank() <= 0 || isLineageElitismActive(tc)) {
                continue;
            }
            if (tc.getRank() > worstRank
                    || (tc.getRank() == worstRank && tc.getDistance() < worstDistance)) {
                worstRank = tc.getRank();
                worstDistance = tc.getDistance();
                worstIndex = i;
            }
        }
        return worstIndex;
    }
}
