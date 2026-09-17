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
package org.evosuite.llm.search.operator;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.ExemplarAttacher;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardExtractor;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the {@code (parent, goal)} pairs, and optional crossover partners,
 * that LLM variation operators act on. Called on the search thread, never
 * inside the mutation/crossover operators themselves, so one batch of targets
 * can be reused by every operator invocation.
 *
 * <p>A caller passes the search {@code generation} the population snapshot
 * belongs to; see {@link GoalStallTracker#stallCountsForGeneration} for how
 * this lets a caller such as the per-individual seed factory invoke {@code
 * select} several times within one generation without inflating the stall
 * history.
 *
 * <p>{@code allowCrossover} additionally gates whether a crossover partner is
 * ever picked: with {@code false}, every returned target is a {@link
 * OperatorTarget.Kind#MUTATION} target regardless of {@link
 * Properties#LLM_OPERATOR_CROSSOVER}; a caller that only ever mutates a single
 * parent (e.g. a seed prompt) passes {@code false}.
 *
 * <p>Selection is strictly cache-only: it never calls a {@link
 * TestFitnessFunction#getFitness} overload, only reads {@link
 * TestChromosome#getFitnessValues()} and {@link
 * org.evosuite.testcase.TestCase#getCoveredGoals()}. It therefore never
 * executes a test, bumps an evaluation counter, or triggers a DMoN promotion;
 * a goal with no cached fitness value on any individual is not targetable
 * this generation.
 *
 * <p>Both {@link Properties#LLM_OPERATOR_TARGETING} modes share one
 * enrichment pipeline (stall tracking, crossover partner, repeated-injection
 * memory, problem card) and differ only in how the {@code (parent, goal)}
 * pairs are sampled and ordered: {@code DIRECTED} builds one candidate per
 * goal still worth targeting (cached best distance in {@code (0,1)}), parent
 * = the individual closest to that best distance, then ranks by priority
 * (stall-boosted closeness) capped to one target per parent; {@code RANDOM}
 * samples uniformly from the valid {@code (parent, goal)} pairs for those
 * same targetable goals, never reusing a parent, instead of ranking by
 * priority.
 */
public class OperatorTargetSelector {

    private static final Logger logger = LoggerFactory.getLogger(OperatorTargetSelector.class);
    private static final int MAX_CARDS = 10;

    /** Supplies ranked diagnostic problem cards for a generation's snapshot. */
    public interface CardSource {
        List<ProblemCard> cards(Collection<TestFitnessFunction> goals, List<TestChromosome> population, int maxCards);
    }

    /** Picks a crossover partner for a given (population, parent, goal, best) tuple. */
    @FunctionalInterface
    private interface PartnerPicker {
        TestChromosome pick(List<TestChromosome> population, TestChromosome parent, TestFitnessFunction goal, double best);
    }

    private final RepeatedInjectionMemory memory;
    private final CardSource cardSource;
    private final GoalStallTracker stallTracker = new GoalStallTracker();

    public OperatorTargetSelector(RepeatedInjectionMemory memory) {
        this(memory, new ProblemCardExtractor()::extract);
    }

    public OperatorTargetSelector(RepeatedInjectionMemory memory, CardSource cardSource) {
        this.memory = memory;
        this.cardSource = cardSource == null ? new ProblemCardExtractor()::extract : cardSource;
    }

    /**
     * Selects up to {@code k} operator targets for this generation's
     * snapshot. See the class javadoc for how {@code generation} governs
     * stall tracking and {@code allowCrossover} gates crossover partners.
     */
    public List<OperatorTarget> select(List<TestChromosome> population,
                                       Collection<TestFitnessFunction> currentGoals, int k,
                                       long generation, boolean allowCrossover) {
        if (population == null || population.isEmpty()
                || currentGoals == null || currentGoals.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }
        if (memory != null) {
            memory.beginSelectionRound();
        }
        return Properties.LLM_OPERATOR_TARGETING == Properties.LlmOperatorTargeting.RANDOM
                ? selectRandom(population, currentGoals, k, generation, allowCrossover)
                : selectDirected(population, currentGoals, k, generation, allowCrossover);
    }

    private List<OperatorTarget> selectDirected(List<TestChromosome> population,
                                                Collection<TestFitnessFunction> currentGoals, int k,
                                                long generation, boolean allowCrossover) {
        Map<TestFitnessFunction, Double> kept = keptGoalsWithBestDistance(currentGoals, population);
        Map<TestFitnessFunction, Integer> stallCounts = stallTracker.stallCountsForGeneration(generation, kept);

        List<OperatorTarget> candidates = new ArrayList<>();
        for (Map.Entry<TestFitnessFunction, Double> entry : kept.entrySet()) {
            TestFitnessFunction goal = entry.getKey();
            double best = entry.getValue();
            TestChromosome parent = ExemplarAttacher.closestChromosome(goal, population, best);
            if (parent == null) {
                continue;
            }
            boolean stalled = stallCounts.getOrDefault(goal, 0) >= Properties.LLM_OPERATOR_STALL_GENERATIONS;
            OperatorTarget target = buildTarget(population, parent, goal, best, best, stalled,
                    CrossoverPartnerFinder::directed, allowCrossover);
            if (target != null) {
                candidates.add(target);
            }
        }
        return withCards(rankAndTrim(candidates, k), currentGoals, population);
    }

    private List<OperatorTarget> selectRandom(List<TestChromosome> population,
                                              Collection<TestFitnessFunction> currentGoals, int k,
                                              long generation, boolean allowCrossover) {
        Map<TestFitnessFunction, Double> kept = keptGoalsWithBestDistance(currentGoals, population);
        if (kept.isEmpty()) {
            return Collections.emptyList();
        }
        Map<TestFitnessFunction, Integer> stallCounts = stallTracker.stallCountsForGeneration(generation, kept);

        List<RandomPairSampler.Pair> pairs = RandomPairSampler.validPairs(population, kept);
        Randomness.shuffle(pairs);

        List<OperatorTarget> result = new ArrayList<>();
        Set<TestChromosome> usedParents = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RandomPairSampler.Pair pair : pairs) {
            if (result.size() >= k) {
                break;
            }
            if (usedParents.contains(pair.parent)) {
                continue;
            }
            double best = kept.get(pair.goal);
            boolean stalled = stallCounts.getOrDefault(pair.goal, 0) >= Properties.LLM_OPERATOR_STALL_GENERATIONS;
            OperatorTarget target = buildTarget(population, pair.parent, pair.goal, pair.distance, best, stalled,
                    CrossoverPartnerFinder::random, allowCrossover);
            if (target == null) {
                continue;
            }
            usedParents.add(pair.parent);
            result.add(target);
        }
        return withCards(result, currentGoals, population);
    }

    /**
     * Attaches problem cards to the selected targets. Done after ranking and
     * trimming because the card source walks the whole population (traces,
     * exceptions, CDGs), which is only worth paying for targets that survived.
     */
    private List<OperatorTarget> withCards(List<OperatorTarget> selected,
                                           Collection<TestFitnessFunction> currentGoals,
                                           List<TestChromosome> population) {
        if (selected.isEmpty()) {
            return selected;
        }
        Map<TestFitnessFunction, ProblemCard> cardsByGoal =
                ProblemCardGoalIndex.build(cardSource, currentGoals, population, MAX_CARDS, logger);
        if (cardsByGoal.isEmpty()) {
            return selected;
        }
        List<OperatorTarget> result = new ArrayList<>(selected.size());
        for (OperatorTarget target : selected) {
            result.add(target.withCard(cardsByGoal.get(target.getGoal())));
        }
        return result;
    }

    /**
     * True if at least one of {@code currentGoals} is targetable against this
     * population snapshot, i.e. some individual carries a cached distance for it
     * in {@code (0, 1)}. Lets a caller tell an empty {@link #select} result that
     * means "every candidate was suppressed (cooldown, in flight)" apart from one
     * that means "nothing here to target" — e.g. a whole-suite search whose test
     * chromosomes never carry per-goal fitness, or a many-objective search with
     * no near-miss yet. Cache-only, like {@link #select}.
     */
    public static boolean hasTargetableGoal(List<TestChromosome> population,
                                            Collection<TestFitnessFunction> currentGoals) {
        if (population == null || population.isEmpty() || currentGoals == null || currentGoals.isEmpty()) {
            return false;
        }
        return !keptGoalsWithBestDistance(currentGoals, population).isEmpty();
    }

    /**
     * Population-wide best cached fitness per goal, restricted to goals whose
     * best is strictly between 0 and {@link ExemplarAttacher#MAX_EXEMPLAR_DISTANCE} (best 0 means covered, no
     * cached value anywhere or a best of 1.0 means not usefully targetable).
     */
    private static Map<TestFitnessFunction, Double> keptGoalsWithBestDistance(
            Collection<TestFitnessFunction> currentGoals, List<TestChromosome> population) {
        Map<TestFitnessFunction, Double> kept = new LinkedHashMap<>();
        for (TestFitnessFunction goal : currentGoals) {
            double best = Double.POSITIVE_INFINITY;
            for (TestChromosome tc : population) {
                Double cached = tc.getFitnessValues().get(goal);
                if (cached != null && Double.isFinite(cached) && cached < best) {
                    best = cached;
                }
            }
            if (best > 0.0 && best < ExemplarAttacher.MAX_EXEMPLAR_DISTANCE) {
                kept.put(goal, best);
            }
        }
        return kept;
    }

    /** One candidate: crossover partner, stall-boosted priority, repeated-injection adjustment. Null if suppressed. */
    private OperatorTarget buildTarget(List<TestChromosome> population, TestChromosome parent,
                                       TestFitnessFunction goal, double distance, double best, boolean stalled,
                                       PartnerPicker partnerPicker, boolean allowCrossover) {
        TestChromosome partner = allowCrossover && Properties.LLM_OPERATOR_CROSSOVER
                ? partnerPicker.pick(population, parent, goal, best) : null;
        double basePriority = (1.0 - distance) * (stalled ? 1.5 : 1.0);
        OperatorTarget target = OperatorTarget.builder(parent, goal)
                .distance(distance).stalled(stalled).partner(partner).priority(basePriority).build();
        if (memory != null) {
            RepeatedInjectionMemory.SelectionAdjustment adjustment = memory.adjustPriority(
                    target.toRepeatedInjectionTarget(), basePriority, true);
            if (adjustment.isSuppressed()) {
                return null;
            }
            target = target.withPriority(adjustment.getPriority());
        }
        return target;
    }

    /** Sorts by priority desc then distance asc (stable, so encounter order breaks remaining ties), then applies {@link #capOnePerParent}. */
    private List<OperatorTarget> rankAndTrim(List<OperatorTarget> candidates, int k) {
        List<OperatorTarget> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> {
            int byPriority = Double.compare(b.getPriority(), a.getPriority());
            return byPriority != 0 ? byPriority : Double.compare(a.getDistance(), b.getDistance());
        });
        return capOnePerParent(ranked, k);
    }

    /** Keeps at most one target per parent, in iteration order, so a single dominant individual cannot crowd out every other target in the same batch. */
    private static List<OperatorTarget> capOnePerParent(List<OperatorTarget> candidates, int k) {
        List<OperatorTarget> result = new ArrayList<>();
        Set<TestChromosome> usedParents = Collections.newSetFromMap(new IdentityHashMap<>());
        for (OperatorTarget target : candidates) {
            if (!usedParents.add(target.getParent())) {
                continue;
            }
            result.add(target);
            if (result.size() >= k) {
                break;
            }
        }
        return result;
    }
}
