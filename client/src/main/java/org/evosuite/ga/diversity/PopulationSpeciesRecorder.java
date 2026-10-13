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
package org.evosuite.ga.diversity;

import org.evosuite.testcase.InjectionSource;
import org.evosuite.utils.SidecarCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Records per-generation, per-individual species and rank assignments to a
 * sidecar CSV under {@link org.evosuite.Properties#REPORT_DIR}. Mirrors the buffer-then-flush
 * pattern used by the disruption recorder.
 *
 * <p>Output schema (one row per generation):
 * <pre>
 *   gen,elapsed_ms,pop_size,n_species,n_injected,n_injected_incubator,
 *   n_injected_post_incubator,injection_source,
 *   n_injected_attempts,injection_attempt_source,
 *   n_injected_attempts_llm_stagnation,n_injected_attempts_llm_async,
 *   n_injected_attempts_island_immigrant,n_injected_attempts_local_search,
 *   covered_goals,remaining_goals,n_fronts,best_fitness,mean_fitness,
 *   diversity,largest_species_share,n_quota_protected,n_newborn_protected,
 *   n_incubator_protected,n_sharing_adjusted,species_per_slot,rank_per_slot,
 *   n_offspring,n_offspring_better,n_offspring_neutral,n_offspring_worse,
 *   n_offspring_unchanged,n_offspring_discarded,n_offspring_random,
 *   n_offspring_survived,n_pop_new,age_per_slot,problem_cards,
 *   blend_channel_admissions
 * </pre>
 * Per-slot columns are semicolon-separated integers in {@code this.population} order.
 * {@code problem_cards} holds the most recent problem-card extraction encoded as
 * alphabetically sorted {@code TYPE:count} pairs joined with {@code '|'} (empty
 * until the first extraction). {@code blend_channel_admissions} encodes this
 * generation's per-channel union admissions as {@code raw:N|mut:N|xg:N|xt:N}
 * (empty when blending is disabled).
 */
public final class PopulationSpeciesRecorder {

    private static final Logger logger = LoggerFactory.getLogger(PopulationSpeciesRecorder.class);

    public static final String FILENAME = "population_species_timeline.csv";

    private static final String HEADER = "gen,elapsed_ms,pop_size,n_species,n_injected,"
            + "n_injected_incubator,n_injected_post_incubator,"
            + "injection_source,n_injected_attempts,injection_attempt_source,"
            + "n_injected_attempts_llm_stagnation,n_injected_attempts_llm_async,"
            + "n_injected_attempts_island_immigrant,n_injected_attempts_local_search,"
            + "covered_goals,remaining_goals,n_fronts,best_fitness,mean_fitness,"
            + "diversity,largest_species_share,n_quota_protected,n_newborn_protected,"
            + "n_incubator_protected,n_sharing_adjusted,species_per_slot,rank_per_slot,"
            + "n_offspring,n_offspring_better,n_offspring_neutral,n_offspring_worse,"
            + "n_offspring_unchanged,n_offspring_discarded,n_offspring_random,"
            + "n_offspring_survived,n_pop_new,age_per_slot,problem_cards,"
            + "blend_channel_admissions";

    private final List<GenerationSnapshot> snapshots = new ArrayList<>();
    private final String sidecarPath;

    public PopulationSpeciesRecorder() {
        this(defaultPath());
    }

    public PopulationSpeciesRecorder(String sidecarPath) {
        this.sidecarPath = sidecarPath;
    }

    public synchronized void record(GenerationSnapshot snap) {
        snapshots.add(snap);
    }

    public synchronized int size() {
        return snapshots.size();
    }

    public String getSidecarPath() {
        return sidecarPath;
    }

    /** Write all buffered snapshots to disk. Safe to call multiple times. */
    public synchronized void flush() {
        List<String> lines = new ArrayList<>(snapshots.size());
        for (GenerationSnapshot snap : snapshots) {
            lines.add(snap.toCsvRow());
        }
        if (SidecarCsv.write(sidecarPath, HEADER, lines, logger, "population species timeline")) {
            logger.info("Population species timeline: wrote {} rows to {}",
                    snapshots.size(), sidecarPath);
        }
    }

    private static String defaultPath() {
        return SidecarCsv.path("population_species_timeline");
    }

    /**
     * Per-generation breeding/turnover observability: offspring fate counts
     * (vs. their parent, see AbstractMOSA's fate classification), survival
     * into the next population, population turnover, and per-slot ages
     * (generations since each individual was last changed).
     */
    public static final class BreedingStats {
        public final int numOffspring;
        public final int numBetter;
        public final int numNeutral;
        public final int numWorse;
        public final int numUnchanged;
        public final int numDiscarded;
        public final int numRandom;
        public final int numSurvived;
        public final int numPopNew;
        public final int[] agePerSlot;

        /**
         * Creates the breeding statistics of one generation.
         *
         * @param numOffspring the number of offspring produced
         * @param numBetter    the number of offspring fitter than their parent
         * @param numNeutral   the number of offspring as fit as their parent
         * @param numWorse     the number of offspring less fit than their parent
         * @param numUnchanged the number of offspring identical to their parent
         * @param numDiscarded the number of offspring discarded
         * @param numRandom    the number of randomly generated individuals
         * @param numSurvived  the number of offspring that survived into the next population
         * @param numPopNew    the number of individuals in the next population that are new
         * @param agePerSlot   generations since each population slot was last changed, null for none
         */
        public BreedingStats(int numOffspring, int numBetter, int numNeutral, int numWorse,
                             int numUnchanged, int numDiscarded, int numRandom,
                             int numSurvived, int numPopNew, int[] agePerSlot) {
            this.numOffspring = numOffspring;
            this.numBetter = numBetter;
            this.numNeutral = numNeutral;
            this.numWorse = numWorse;
            this.numUnchanged = numUnchanged;
            this.numDiscarded = numDiscarded;
            this.numRandom = numRandom;
            this.numSurvived = numSurvived;
            this.numPopNew = numPopNew;
            this.agePerSlot = (agePerSlot != null) ? agePerSlot : new int[0];
        }

        /** All-zero stats with an empty age array, for callers that don't track breeding. */
        public static BreedingStats empty() {
            return new BreedingStats(0, 0, 0, 0, 0, 0, 0, 0, 0, new int[0]);
        }
    }

    /**
     * One row of the timeline. Constructed by the caller (AbstractMOSA) with
     * all fields ready; the recorder only buffers and serializes.
     */
    public static final class GenerationSnapshot {
        public final int gen;
        public final long elapsedMs;
        public final int popSize;
        public final int numSpecies;
        public final int numInjected;
        public final int numInjectedIncubator;
        public final int numInjectedPostIncubator;
        public final InjectionSource dominantInjectionSource;
        public final int numInjectedAttempts;
        public final InjectionSource dominantAttemptSource;
        public final int numInjectedAttemptsLlmStagnation;
        public final int numInjectedAttemptsLlmAsync;
        public final int numInjectedAttemptsIslandImmigrant;
        public final int numInjectedAttemptsLocalSearch;
        public final int coveredGoals;
        public final int remainingGoals;
        public final int numFronts;
        public final double bestFitness;
        public final double meanFitness;
        public final double diversity;
        public final double largestSpeciesShare;
        public final int numQuotaProtected;
        public final int numNewbornProtected;
        public final int numIncubatorProtected;
        public final int numSharingAdjusted;
        public final int[] speciesPerSlot;
        public final int[] rankPerSlot;
        public final BreedingStats breeding;
        public final String problemCards;
        public final String blendChannelAdmissions;

        /**
         * Creates a snapshot with empty blend channel admissions. The parameters are those of the full
         * constructor without its last one.
         */
        public GenerationSnapshot(int gen,
                                  long elapsedMs,
                                  int popSize,
                                  int numSpecies,
                                  int numInjected,
                                  int numInjectedIncubator,
                                  int numInjectedPostIncubator,
                                  InjectionSource dominantInjectionSource,
                                  int numInjectedAttempts,
                                  InjectionSource dominantAttemptSource,
                                  int numInjectedAttemptsLlmStagnation,
                                  int numInjectedAttemptsLlmAsync,
                                  int numInjectedAttemptsIslandImmigrant,
                                  int numInjectedAttemptsLocalSearch,
                                  int coveredGoals,
                                  int remainingGoals,
                                  int numFronts,
                                  double bestFitness,
                                  double meanFitness,
                                  double diversity,
                                  double largestSpeciesShare,
                                  int numQuotaProtected,
                                  int numNewbornProtected,
                                  int numIncubatorProtected,
                                  int numSharingAdjusted,
                                  int[] speciesPerSlot,
                                  int[] rankPerSlot,
                                  BreedingStats breeding,
                                  String problemCards) {
            this(gen, elapsedMs, popSize, numSpecies, numInjected, numInjectedIncubator,
                    numInjectedPostIncubator, dominantInjectionSource, numInjectedAttempts,
                    dominantAttemptSource, numInjectedAttemptsLlmStagnation,
                    numInjectedAttemptsLlmAsync, numInjectedAttemptsIslandImmigrant,
                    numInjectedAttemptsLocalSearch, coveredGoals, remainingGoals, numFronts,
                    bestFitness, meanFitness, diversity, largestSpeciesShare,
                    numQuotaProtected, numNewbornProtected, numIncubatorProtected,
                    numSharingAdjusted, speciesPerSlot, rankPerSlot, breeding,
                    problemCards, "");
        }

        /**
         * Creates a snapshot of one generation; all values are recorded as given.
         */
        public GenerationSnapshot(int gen,
                                  long elapsedMs,
                                  int popSize,
                                  int numSpecies,
                                  int numInjected,
                                  int numInjectedIncubator,
                                  int numInjectedPostIncubator,
                                  InjectionSource dominantInjectionSource,
                                  int numInjectedAttempts,
                                  InjectionSource dominantAttemptSource,
                                  int numInjectedAttemptsLlmStagnation,
                                  int numInjectedAttemptsLlmAsync,
                                  int numInjectedAttemptsIslandImmigrant,
                                  int numInjectedAttemptsLocalSearch,
                                  int coveredGoals,
                                  int remainingGoals,
                                  int numFronts,
                                  double bestFitness,
                                  double meanFitness,
                                  double diversity,
                                  double largestSpeciesShare,
                                  int numQuotaProtected,
                                  int numNewbornProtected,
                                  int numIncubatorProtected,
                                  int numSharingAdjusted,
                                  int[] speciesPerSlot,
                                  int[] rankPerSlot,
                                  BreedingStats breeding,
                                  String problemCards,
                                  String blendChannelAdmissions) {
            this.gen = gen;
            this.elapsedMs = elapsedMs;
            this.popSize = popSize;
            this.numSpecies = numSpecies;
            this.numInjected = numInjected;
            this.numInjectedIncubator = numInjectedIncubator;
            this.numInjectedPostIncubator = numInjectedPostIncubator;
            this.dominantInjectionSource = dominantInjectionSource;
            this.numInjectedAttempts = numInjectedAttempts;
            this.dominantAttemptSource = dominantAttemptSource;
            this.numInjectedAttemptsLlmStagnation = numInjectedAttemptsLlmStagnation;
            this.numInjectedAttemptsLlmAsync = numInjectedAttemptsLlmAsync;
            this.numInjectedAttemptsIslandImmigrant = numInjectedAttemptsIslandImmigrant;
            this.numInjectedAttemptsLocalSearch = numInjectedAttemptsLocalSearch;
            this.coveredGoals = coveredGoals;
            this.remainingGoals = remainingGoals;
            this.numFronts = numFronts;
            this.bestFitness = bestFitness;
            this.meanFitness = meanFitness;
            this.diversity = diversity;
            this.largestSpeciesShare = largestSpeciesShare;
            this.numQuotaProtected = numQuotaProtected;
            this.numNewbornProtected = numNewbornProtected;
            this.numIncubatorProtected = numIncubatorProtected;
            this.numSharingAdjusted = numSharingAdjusted;
            this.speciesPerSlot = speciesPerSlot;
            this.rankPerSlot = rankPerSlot;
            this.breeding = (breeding != null) ? breeding : BreedingStats.empty();
            this.problemCards = (problemCards != null) ? problemCards : "";
            this.blendChannelAdmissions =
                    (blendChannelAdmissions != null) ? blendChannelAdmissions : "";
        }

        String toCsvRow() {
            StringBuilder sb = new StringBuilder(64 + speciesPerSlot.length * 4);
            sb.append(gen).append(',')
              .append(elapsedMs).append(',')
              .append(popSize).append(',')
              .append(numSpecies).append(',')
              .append(numInjected).append(',')
              .append(numInjectedIncubator).append(',')
              .append(numInjectedPostIncubator).append(',')
              .append(dominantInjectionSource == null ? "" : dominantInjectionSource.name()).append(',')
              .append(numInjectedAttempts).append(',')
              .append(dominantAttemptSource == null ? "" : dominantAttemptSource.name()).append(',')
              .append(numInjectedAttemptsLlmStagnation).append(',')
              .append(numInjectedAttemptsLlmAsync).append(',')
              .append(numInjectedAttemptsIslandImmigrant).append(',')
              .append(numInjectedAttemptsLocalSearch).append(',')
              .append(coveredGoals).append(',')
              .append(remainingGoals).append(',')
              .append(numFronts).append(',')
              .append(formatDouble(bestFitness)).append(',')
              .append(formatDouble(meanFitness)).append(',')
              .append(formatDouble(diversity)).append(',')
              .append(formatDouble(largestSpeciesShare)).append(',')
              .append(numQuotaProtected).append(',')
              .append(numNewbornProtected).append(',')
              .append(numIncubatorProtected).append(',')
              .append(numSharingAdjusted).append(',');
            appendIntArray(sb, speciesPerSlot);
            sb.append(',');
            appendIntArray(sb, rankPerSlot);
            sb.append(',').append(breeding.numOffspring)
              .append(',').append(breeding.numBetter)
              .append(',').append(breeding.numNeutral)
              .append(',').append(breeding.numWorse)
              .append(',').append(breeding.numUnchanged)
              .append(',').append(breeding.numDiscarded)
              .append(',').append(breeding.numRandom)
              .append(',').append(breeding.numSurvived)
              .append(',').append(breeding.numPopNew)
              .append(',');
            appendIntArray(sb, breeding.agePerSlot);
            sb.append(',').append(problemCards);
            sb.append(',').append(blendChannelAdmissions);
            return sb.toString();
        }

        private static void appendIntArray(StringBuilder sb, int[] arr) {
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(arr[i]);
            }
        }

        private static String formatDouble(double v) {
            if (Double.isNaN(v)) {
                return "";
            }
            return Double.toString(v);
        }
    }
}
