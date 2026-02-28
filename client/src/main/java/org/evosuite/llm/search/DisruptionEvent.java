/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.search;

/**
 * Immutable record of a single operator disruption event for Phase 8b analysis.
 * One event is emitted per operator application attempt (mutation or crossover),
 * including fallback attempts.
 */
public class DisruptionEvent {

    /** Operator kind: MUTATION or CROSSOVER. */
    public enum OperatorKind { MUTATION, CROSSOVER }

    /** Operator source: STANDARD (GA-native) or SEMANTIC (LLM-backed). */
    public enum OperatorSource { STANDARD, SEMANTIC }

    /** Outcome of the operator attempt. */
    public enum OperatorOutcome { APPLIED, FALLBACK, FAILED }

    // ---- Identification ----
    private final int generation;
    private final int eventIndex;
    private final OperatorKind operatorKind;
    private final OperatorSource operatorSource;
    private final OperatorOutcome outcome;

    // ---- Lineage ----
    private final int parent1Hash;
    private final int parent2Hash; // -1 for mutation
    private final int offspringHash;

    // ---- Fitness snapshots ----
    private final double fitnessPreOperator;
    private final double fitnessPostOperator;
    private final double fitnessDelta;

    // ---- Syntactic disruption ----
    private final int statementCountBefore;
    private final int statementCountAfter;
    private final int statementCountDelta;
    private final int editsAdded;
    private final int editsRemoved;

    // ---- Execution-level disruption ----
    private final double branchJaccardDistance;
    private final double lineJaccardDistance;     // NaN if unavailable
    private final double goalJaccardDistance;      // NaN if unavailable
    private final double speciationMetricDistance; // NaN if unavailable

    // ---- Acceptance/survival ----
    private final Boolean acceptedIntoOffspring; // null = unknown
    private final Boolean survivesToNextGeneration; // null = unknown

    // ---- Probe metadata ----
    private final boolean isolatedProbe;
    private final boolean probeFailure;

    // ---- Isolated probe fitness values ----
    private final double isolatedFitnessPostCrossover;  // NaN if unavailable
    private final double isolatedFitnessPostMutation;   // NaN if unavailable
    private final double isolatedMutationDelta;          // NaN if unavailable

    private DisruptionEvent(Builder b) {
        this.generation = b.generation;
        this.eventIndex = b.eventIndex;
        this.operatorKind = b.operatorKind;
        this.operatorSource = b.operatorSource;
        this.outcome = b.outcome;
        this.parent1Hash = b.parent1Hash;
        this.parent2Hash = b.parent2Hash;
        this.offspringHash = b.offspringHash;
        this.fitnessPreOperator = b.fitnessPreOperator;
        this.fitnessPostOperator = b.fitnessPostOperator;
        this.fitnessDelta = b.fitnessDelta;
        this.statementCountBefore = b.statementCountBefore;
        this.statementCountAfter = b.statementCountAfter;
        this.statementCountDelta = b.statementCountDelta;
        this.editsAdded = b.editsAdded;
        this.editsRemoved = b.editsRemoved;
        this.branchJaccardDistance = b.branchJaccardDistance;
        this.lineJaccardDistance = b.lineJaccardDistance;
        this.goalJaccardDistance = b.goalJaccardDistance;
        this.speciationMetricDistance = b.speciationMetricDistance;
        this.acceptedIntoOffspring = b.acceptedIntoOffspring;
        this.survivesToNextGeneration = b.survivesToNextGeneration;
        this.isolatedProbe = b.isolatedProbe;
        this.probeFailure = b.probeFailure;
        this.isolatedFitnessPostCrossover = b.isolatedFitnessPostCrossover;
        this.isolatedFitnessPostMutation = b.isolatedFitnessPostMutation;
        this.isolatedMutationDelta = b.isolatedMutationDelta;
    }

    // ---- CSV header/row ----

    public static final String CSV_HEADER =
            "generation,event_index,operator_kind,operator_source,outcome,"
            + "parent1_hash,parent2_hash,offspring_hash,"
            + "fitness_pre,fitness_post,fitness_delta,"
            + "stmt_count_before,stmt_count_after,stmt_count_delta,edits_added,edits_removed,"
            + "branch_jaccard_distance,line_jaccard_distance,goal_jaccard_distance,speciation_metric_distance,"
            + "accepted_into_offspring,survives_to_next_generation,"
            + "isolated_probe,probe_failure,"
            + "isolated_fitness_post_crossover,isolated_fitness_post_mutation,isolated_mutation_delta";

    public String toCsvRow() {
        return generation + "," + eventIndex + ","
                + operatorKind + "," + operatorSource + "," + outcome + ","
                + parent1Hash + "," + parent2Hash + "," + offspringHash + ","
                + fitnessPreOperator + "," + fitnessPostOperator + "," + fitnessDelta + ","
                + statementCountBefore + "," + statementCountAfter + "," + statementCountDelta
                + "," + editsAdded + "," + editsRemoved + ","
                + formatDouble(branchJaccardDistance) + "," + formatDouble(lineJaccardDistance)
                + "," + formatDouble(goalJaccardDistance) + "," + formatDouble(speciationMetricDistance) + ","
                + (acceptedIntoOffspring == null ? "" : acceptedIntoOffspring) + ","
                + (survivesToNextGeneration == null ? "" : survivesToNextGeneration) + ","
                + isolatedProbe + "," + probeFailure + ","
                + formatDouble(isolatedFitnessPostCrossover) + ","
                + formatDouble(isolatedFitnessPostMutation) + ","
                + formatDouble(isolatedMutationDelta);
    }

    private static String formatDouble(double v) {
        return Double.isNaN(v) ? "" : String.valueOf(v);
    }

    // ---- Getters ----

    public int getGeneration() { return generation; }
    public int getEventIndex() { return eventIndex; }
    public OperatorKind getOperatorKind() { return operatorKind; }
    public OperatorSource getOperatorSource() { return operatorSource; }
    public OperatorOutcome getOutcome() { return outcome; }
    public int getParent1Hash() { return parent1Hash; }
    public int getParent2Hash() { return parent2Hash; }
    public int getOffspringHash() { return offspringHash; }
    public double getFitnessPreOperator() { return fitnessPreOperator; }
    public double getFitnessPostOperator() { return fitnessPostOperator; }
    public double getFitnessDelta() { return fitnessDelta; }
    public int getStatementCountBefore() { return statementCountBefore; }
    public int getStatementCountAfter() { return statementCountAfter; }
    public int getStatementCountDelta() { return statementCountDelta; }
    public int getEditsAdded() { return editsAdded; }
    public int getEditsRemoved() { return editsRemoved; }
    public double getBranchJaccardDistance() { return branchJaccardDistance; }
    public double getLineJaccardDistance() { return lineJaccardDistance; }
    public double getGoalJaccardDistance() { return goalJaccardDistance; }
    public double getSpeciationMetricDistance() { return speciationMetricDistance; }
    public Boolean getAcceptedIntoOffspring() { return acceptedIntoOffspring; }
    public Boolean getSurvivesToNextGeneration() { return survivesToNextGeneration; }
    public boolean isIsolatedProbe() { return isolatedProbe; }
    public boolean isProbeFailure() { return probeFailure; }
    public double getIsolatedFitnessPostCrossover() { return isolatedFitnessPostCrossover; }
    public double getIsolatedFitnessPostMutation() { return isolatedFitnessPostMutation; }
    public double getIsolatedMutationDelta() { return isolatedMutationDelta; }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int generation;
        private int eventIndex;
        private OperatorKind operatorKind;
        private OperatorSource operatorSource;
        private OperatorOutcome outcome = OperatorOutcome.APPLIED;
        private int parent1Hash;
        private int parent2Hash = -1;
        private int offspringHash;
        private double fitnessPreOperator = Double.NaN;
        private double fitnessPostOperator = Double.NaN;
        private double fitnessDelta = Double.NaN;
        private int statementCountBefore;
        private int statementCountAfter;
        private int statementCountDelta;
        private int editsAdded;
        private int editsRemoved;
        private double branchJaccardDistance = Double.NaN;
        private double lineJaccardDistance = Double.NaN;
        private double goalJaccardDistance = Double.NaN;
        private double speciationMetricDistance = Double.NaN;
        private Boolean acceptedIntoOffspring;
        private Boolean survivesToNextGeneration;
        private boolean isolatedProbe;
        private boolean probeFailure;
        private double isolatedFitnessPostCrossover = Double.NaN;
        private double isolatedFitnessPostMutation = Double.NaN;
        private double isolatedMutationDelta = Double.NaN;

        public Builder generation(int v) { generation = v; return this; }
        public Builder eventIndex(int v) { eventIndex = v; return this; }
        public Builder operatorKind(OperatorKind v) { operatorKind = v; return this; }
        public Builder operatorSource(OperatorSource v) { operatorSource = v; return this; }
        public Builder outcome(OperatorOutcome v) { outcome = v; return this; }
        public Builder parent1Hash(int v) { parent1Hash = v; return this; }
        public Builder parent2Hash(int v) { parent2Hash = v; return this; }
        public Builder offspringHash(int v) { offspringHash = v; return this; }
        public Builder fitnessPreOperator(double v) { fitnessPreOperator = v; return this; }
        public Builder fitnessPostOperator(double v) { fitnessPostOperator = v; return this; }
        public Builder fitnessDelta(double v) { fitnessDelta = v; return this; }
        public Builder statementCountBefore(int v) { statementCountBefore = v; return this; }
        public Builder statementCountAfter(int v) { statementCountAfter = v; return this; }
        public Builder statementCountDelta(int v) { statementCountDelta = v; return this; }
        public Builder editsAdded(int v) { editsAdded = v; return this; }
        public Builder editsRemoved(int v) { editsRemoved = v; return this; }
        public Builder branchJaccardDistance(double v) { branchJaccardDistance = v; return this; }
        public Builder lineJaccardDistance(double v) { lineJaccardDistance = v; return this; }
        public Builder goalJaccardDistance(double v) { goalJaccardDistance = v; return this; }
        public Builder speciationMetricDistance(double v) { speciationMetricDistance = v; return this; }
        public Builder acceptedIntoOffspring(Boolean v) { acceptedIntoOffspring = v; return this; }
        public Builder survivesToNextGeneration(Boolean v) { survivesToNextGeneration = v; return this; }
        public Builder isolatedProbe(boolean v) { isolatedProbe = v; return this; }
        public Builder probeFailure(boolean v) { probeFailure = v; return this; }
        public Builder isolatedFitnessPostCrossover(double v) { isolatedFitnessPostCrossover = v; return this; }
        public Builder isolatedFitnessPostMutation(double v) { isolatedFitnessPostMutation = v; return this; }
        public Builder isolatedMutationDelta(double v) { isolatedMutationDelta = v; return this; }

        public DisruptionEvent build() {
            return new DisruptionEvent(this);
        }
    }
}
