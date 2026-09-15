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

import org.evosuite.llm.prompt.ExemplarAttacher;
import org.evosuite.llm.prompt.TargetSection;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * One selected (parent, goal) pairing for an LLM variation operator call, with
 * an optional crossover partner and diagnostic problem card. Immutable value
 * object produced by {@link OperatorTargetSelector}.
 */
public final class OperatorTarget {

    /** Whether this target feeds a mutation or a crossover operator call. */
    public enum Kind { MUTATION, CROSSOVER }

    private final TestChromosome parent;
    private final TestFitnessFunction goal;
    private final double distance;
    private final double priority;
    private final Kind kind;
    private final boolean stalled;
    private final ProblemCard card;
    private final TestChromosome partner;

    private OperatorTarget(Builder builder) {
        this.parent = Objects.requireNonNull(builder.parent, "parent");
        this.goal = Objects.requireNonNull(builder.goal, "goal");
        this.distance = builder.distance;
        this.priority = builder.priority;
        this.stalled = builder.stalled;
        this.card = builder.card;
        this.partner = builder.partner;
        this.kind = builder.partner != null ? Kind.CROSSOVER : Kind.MUTATION;
    }

    public static Builder builder(TestChromosome parent, TestFitnessFunction goal) {
        return new Builder(parent, goal);
    }

    /**
     * Entry point for a caller that already holds an individual and just needs a
     * goal to target it with (the breeding-loop hooks {@code mutateSemantically}
     * and {@code crossoverSemantically}); population-wide selection across many
     * individuals is {@link OperatorTargetSelector}. Picks the goal with the
     * smallest cached fitness in {@code (0, ExemplarAttacher.MAX_EXEMPLAR_DISTANCE)}
     * on {@code parent} — the same "worth targeting" range the selector uses
     * (cached only: {@link TestChromosome#getFitnessValues()}, never
     * {@link TestFitnessFunction#getFitness}, so this never re-executes the
     * test); when no goal has such a cached value, falls back to a random goal
     * with distance {@link Double#NaN}. Returns null when {@code parent} is
     * null or {@code goals} is null or empty.
     */
    public static OperatorTarget closestGoal(TestChromosome parent, TestChromosome partner,
                                             Collection<TestFitnessFunction> goals) {
        if (parent == null || goals == null || goals.isEmpty()) {
            return null;
        }
        TestFitnessFunction bestGoal = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (TestFitnessFunction goal : goals) {
            Double value = parent.getFitnessValues().get(goal);
            if (value != null && value > 0.0 && value < ExemplarAttacher.MAX_EXEMPLAR_DISTANCE
                    && value < bestDistance) {
                bestDistance = value;
                bestGoal = goal;
            }
        }
        if (bestGoal == null) {
            bestGoal = Randomness.choice(goals);
            bestDistance = Double.NaN;
        }
        Builder builder = builder(parent, bestGoal).distance(bestDistance);
        if (partner != null) {
            builder.partner(partner);
        }
        return builder.build();
    }

    public TestChromosome getParent() { return parent; }
    public TestFitnessFunction getGoal() { return goal; }
    public double getDistance() { return distance; }
    public double getPriority() { return priority; }
    public Kind getKind() { return kind; }
    public boolean isStalled() { return stalled; }
    public Optional<ProblemCard> getCard() { return Optional.ofNullable(card); }
    public Optional<TestChromosome> getPartner() { return Optional.ofNullable(partner); }

    /**
     * Repeated-injection identity for this target: the key identifies the goal
     * being targeted; the fingerprint also captures which concrete parent (and,
     * for crossover, partner) test was used, so a retry with a materially
     * different test is not suppressed as a repeat of the same attempt.
     */
    public RepeatedInjectionTarget toRepeatedInjectionTarget() {
        String key = "operator|" + goal.toString();
        StringBuilder fingerprint = new StringBuilder(key).append("|parent=")
                .append(TargetSection.canonicalCode(parent.getTestCase()));
        if (partner != null) {
            fingerprint.append("|partner=").append(TargetSection.canonicalCode(partner.getTestCase()));
        }
        return new RepeatedInjectionTarget(key, fingerprint.toString());
    }

    @Override
    public String toString() {
        return "OperatorTarget{kind=" + kind + ", goal=" + goal + ", distance=" + distance
                + ", priority=" + priority + ", stalled=" + stalled
                + ", partner=" + (partner != null) + ", card=" + (card != null) + '}';
    }

    /** Builder for {@link OperatorTarget}; every field but parent/goal is optional. */
    public static final class Builder {
        private final TestChromosome parent;
        private final TestFitnessFunction goal;
        private double distance = Double.NaN;
        private double priority;
        private boolean stalled;
        private ProblemCard card;
        private TestChromosome partner;

        private Builder(TestChromosome parent, TestFitnessFunction goal) {
            this.parent = parent;
            this.goal = goal;
        }

        public Builder distance(double distance) { this.distance = distance; return this; }
        public Builder priority(double priority) { this.priority = priority; return this; }
        public Builder stalled(boolean stalled) { this.stalled = stalled; return this; }
        public Builder card(ProblemCard card) { this.card = card; return this; }
        public Builder partner(TestChromosome partner) { this.partner = partner; return this; }

        public OperatorTarget build() {
            return new OperatorTarget(this);
        }
    }
}
