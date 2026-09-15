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
import org.evosuite.llm.search.StagnationDetector;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the {@code (parent, goal)} pairs, and optional crossover partners,
 * that LLM variation operators act on.
 *
 * <p>Called once per generation on the search thread, never inside the
 * mutation/crossover operators themselves: that keeps the ranking work off
 * the per-individual hot path and lets one batch of targets be reused by
 * every operator invocation in that generation.
 *
 * <p>Selection never executes a test beyond what {@link
 * StagnationDetector#computeBestFitnessPerGoal} already does on already
 * evaluated individuals: it otherwise reads only {@link
 * TestChromosome#getFitnessValues()} caches and {@link
 * org.evosuite.testcase.TestCase#getCoveredGoals()}.
 *
 * <p>Branches on {@link Properties#LLM_OPERATOR_TARGETING}: {@code DIRECTED}
 * ranks candidates by closeness to an uncovered goal (with a stall-priority
 * boost and repeated-injection cooldown); {@code RANDOM} is a control arm
 * that pairs random individuals with random current goals, reusing {@link
 * CrossoverPartnerFinder}'s eligibility rule so the two modes differ only in
 * how the (parent, goal) pair itself is chosen.
 */
public class OperatorTargetSelector {

    private static final Logger logger = LoggerFactory.getLogger(OperatorTargetSelector.class);
    private static final int MAX_CARDS = 10;

    /** Supplies ranked diagnostic problem cards for a generation's snapshot. */
    public interface CardSource {
        List<ProblemCard> cards(Collection<TestFitnessFunction> goals, List<TestChromosome> population, int maxCards);
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

    /** Selects up to {@code k} operator targets for this generation's snapshot. */
    public List<OperatorTarget> select(List<TestChromosome> population,
                                       Collection<TestFitnessFunction> currentGoals, int k) {
        if (population == null || population.isEmpty()
                || currentGoals == null || currentGoals.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }
        return Properties.LLM_OPERATOR_TARGETING == Properties.LlmOperatorTargeting.RANDOM
                ? selectRandom(population, currentGoals, k)
                : selectDirected(population, currentGoals, k);
    }

    private List<OperatorTarget> selectDirected(List<TestChromosome> population,
                                                Collection<TestFitnessFunction> currentGoals, int k) {
        Map<TestFitnessFunction, Double> kept = keptGoalsWithBestDistance(currentGoals, population);

        stallTracker.forgetGoalsNotIn(currentGoals);
        Map<TestFitnessFunction, Integer> stallCounts = new HashMap<>();
        for (Map.Entry<TestFitnessFunction, Double> entry : kept.entrySet()) {
            stallCounts.put(entry.getKey(), stallTracker.record(entry.getKey(), entry.getValue()));
        }

        Map<TestFitnessFunction, ProblemCard> cardsByGoal =
                ProblemCardGoalIndex.build(cardSource, currentGoals, population, MAX_CARDS, logger);

        List<OperatorTarget> candidates = new ArrayList<>();
        for (Map.Entry<TestFitnessFunction, Double> entry : kept.entrySet()) {
            OperatorTarget target = buildDirectedTarget(population, entry.getKey(), entry.getValue(),
                    stallCounts.getOrDefault(entry.getKey(), 0) >= Properties.LLM_OPERATOR_STALL_GENERATIONS,
                    cardsByGoal);
            if (target != null) {
                candidates.add(target);
            }
        }
        return rankAndTrim(candidates, k);
    }

    private static Map<TestFitnessFunction, Double> keptGoalsWithBestDistance(
            Collection<TestFitnessFunction> currentGoals, List<TestChromosome> population) {
        Map<TestFitnessFunction, Double> best =
                StagnationDetector.computeBestFitnessPerGoal(currentGoals, population);
        Map<TestFitnessFunction, Double> kept = new LinkedHashMap<>();
        for (Map.Entry<TestFitnessFunction, Double> entry : best.entrySet()) {
            double distance = entry.getValue();
            if (Double.isFinite(distance) && distance > 0.0 && distance < 1.0) {
                kept.put(entry.getKey(), distance);
            }
        }
        return kept;
    }

    /** Builds one candidate target, applying the repeated-injection cooldown; null if the goal has no closest parent or is suppressed. */
    private OperatorTarget buildDirectedTarget(List<TestChromosome> population, TestFitnessFunction goal,
                                               double distance, boolean stalled,
                                               Map<TestFitnessFunction, ProblemCard> cardsByGoal) {
        TestChromosome parent = ExemplarAttacher.closestChromosome(goal, population, distance);
        if (parent == null) {
            return null;
        }
        TestChromosome partner = Properties.LLM_OPERATOR_CROSSOVER
                ? CrossoverPartnerFinder.directed(population, parent, goal, distance) : null;
        double basePriority = (1.0 - distance) * (stalled ? 1.5 : 1.0);

        OperatorTarget.Builder builder = OperatorTarget.builder(parent, goal)
                .distance(distance).stalled(stalled).partner(partner).priority(basePriority);

        double finalPriority = basePriority;
        if (memory != null) {
            RepeatedInjectionMemory.SelectionAdjustment adjustment = memory.adjustPriority(
                    builder.build().toRepeatedInjectionTarget(), basePriority, true);
            if (adjustment.isSuppressed()) {
                return null;
            }
            finalPriority = adjustment.getPriority();
        }
        return builder.priority(finalPriority).card(cardsByGoal.get(goal)).build();
    }

    /**
     * Sorts by priority desc, then distance asc, keeping per-goal encounter
     * order as the final tiebreak via a stable sort, then keeps at most one
     * target per parent so a single dominant individual cannot crowd out every
     * other target in the same batch.
     */
    private List<OperatorTarget> rankAndTrim(List<OperatorTarget> candidates, int k) {
        List<OperatorTarget> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> {
            int byPriority = Double.compare(b.getPriority(), a.getPriority());
            return byPriority != 0 ? byPriority : Double.compare(a.getDistance(), b.getDistance());
        });
        List<OperatorTarget> result = new ArrayList<>();
        Set<TestChromosome> usedParents = Collections.newSetFromMap(new IdentityHashMap<>());
        for (OperatorTarget target : ranked) {
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

    private List<OperatorTarget> selectRandom(List<TestChromosome> population,
                                              Collection<TestFitnessFunction> currentGoals, int k) {
        List<TestChromosome> shuffled = new ArrayList<>(population);
        Randomness.shuffle(shuffled);
        List<TestFitnessFunction> goals = new ArrayList<>(currentGoals);
        int count = Math.min(k, shuffled.size());
        List<OperatorTarget> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TestChromosome parent = shuffled.get(i);
            TestFitnessFunction goal = Randomness.choice(goals);
            Double cached = parent.getFitnessValues().get(goal);
            double distance = (cached != null && Double.isFinite(cached)) ? cached : Double.NaN;
            OperatorTarget.Builder builder = OperatorTarget.builder(parent, goal).distance(distance).priority(1.0);
            if (Properties.LLM_OPERATOR_CROSSOVER) {
                builder.partner(CrossoverPartnerFinder.random(population, parent));
            }
            targets.add(builder.build());
        }
        return targets;
    }
}
