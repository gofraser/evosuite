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

import org.evosuite.Properties;
import org.evosuite.testcase.InjectionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records per-generation, per-individual species and rank assignments to a
 * sidecar CSV under {@link Properties#REPORT_DIR}. Mirrors the buffer-then-flush
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
        File file = new File(sidecarPath);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, FILENAME);
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(HEADER);
            writer.newLine();
            for (GenerationSnapshot snap : snapshots) {
                writer.write(snap.toCsvRow());
                writer.newLine();
            }
            logger.info("Population species timeline: wrote {} rows to {}",
                    snapshots.size(), sidecarPath);
        } catch (IOException e) {
            logger.warn("Failed to write population species timeline: {}", e.getMessage());
        }
    }

    private static String defaultPath() {
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return Properties.REPORT_DIR + File.separator
                + "population_species_timeline" + suffix + ".csv";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    /**
     * Per-generation breeding/turnover observability: offspring fate counts
     * (vs. their parent, see AbstractMOSA's fate classification), survival
     * into the next population, population turnover, and per-slot ages
     * (generations since each individual was last changed).
     */
    public static final class BreedingStats {
        public final int nOffspring;
        public final int nBetter;
        public final int nNeutral;
        public final int nWorse;
        public final int nUnchanged;
        public final int nDiscarded;
        public final int nRandom;
        public final int nSurvived;
        public final int nPopNew;
        public final int[] agePerSlot;

        public BreedingStats(int nOffspring, int nBetter, int nNeutral, int nWorse,
                             int nUnchanged, int nDiscarded, int nRandom,
                             int nSurvived, int nPopNew, int[] agePerSlot) {
            this.nOffspring = nOffspring;
            this.nBetter = nBetter;
            this.nNeutral = nNeutral;
            this.nWorse = nWorse;
            this.nUnchanged = nUnchanged;
            this.nDiscarded = nDiscarded;
            this.nRandom = nRandom;
            this.nSurvived = nSurvived;
            this.nPopNew = nPopNew;
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
        public final int nSpecies;
        public final int nInjected;
        public final int nInjectedIncubator;
        public final int nInjectedPostIncubator;
        public final InjectionSource dominantInjectionSource;
        public final int nInjectedAttempts;
        public final InjectionSource dominantAttemptSource;
        public final int nInjectedAttemptsLlmStagnation;
        public final int nInjectedAttemptsLlmAsync;
        public final int nInjectedAttemptsIslandImmigrant;
        public final int nInjectedAttemptsLocalSearch;
        public final int coveredGoals;
        public final int remainingGoals;
        public final int nFronts;
        public final double bestFitness;
        public final double meanFitness;
        public final double diversity;
        public final double largestSpeciesShare;
        public final int nQuotaProtected;
        public final int nNewbornProtected;
        public final int nIncubatorProtected;
        public final int nSharingAdjusted;
        public final int[] speciesPerSlot;
        public final int[] rankPerSlot;
        public final BreedingStats breeding;
        public final String problemCards;
        public final String blendChannelAdmissions;

        public GenerationSnapshot(int gen,
                                  long elapsedMs,
                                  int popSize,
                                  int nSpecies,
                                  int nInjected,
                                  int nInjectedIncubator,
                                  int nInjectedPostIncubator,
                                  InjectionSource dominantInjectionSource,
                                  int nInjectedAttempts,
                                  InjectionSource dominantAttemptSource,
                                  int nInjectedAttemptsLlmStagnation,
                                  int nInjectedAttemptsLlmAsync,
                                  int nInjectedAttemptsIslandImmigrant,
                                  int nInjectedAttemptsLocalSearch,
                                  int coveredGoals,
                                  int remainingGoals,
                                  int nFronts,
                                  double bestFitness,
                                  double meanFitness,
                                  double diversity,
                                  double largestSpeciesShare,
                                  int nQuotaProtected,
                                  int nNewbornProtected,
                                  int nIncubatorProtected,
                                  int nSharingAdjusted,
                                  int[] speciesPerSlot,
                                  int[] rankPerSlot,
                                  BreedingStats breeding,
                                  String problemCards) {
            this(gen, elapsedMs, popSize, nSpecies, nInjected, nInjectedIncubator,
                    nInjectedPostIncubator, dominantInjectionSource, nInjectedAttempts,
                    dominantAttemptSource, nInjectedAttemptsLlmStagnation,
                    nInjectedAttemptsLlmAsync, nInjectedAttemptsIslandImmigrant,
                    nInjectedAttemptsLocalSearch, coveredGoals, remainingGoals, nFronts,
                    bestFitness, meanFitness, diversity, largestSpeciesShare,
                    nQuotaProtected, nNewbornProtected, nIncubatorProtected,
                    nSharingAdjusted, speciesPerSlot, rankPerSlot, breeding,
                    problemCards, "");
        }

        public GenerationSnapshot(int gen,
                                  long elapsedMs,
                                  int popSize,
                                  int nSpecies,
                                  int nInjected,
                                  int nInjectedIncubator,
                                  int nInjectedPostIncubator,
                                  InjectionSource dominantInjectionSource,
                                  int nInjectedAttempts,
                                  InjectionSource dominantAttemptSource,
                                  int nInjectedAttemptsLlmStagnation,
                                  int nInjectedAttemptsLlmAsync,
                                  int nInjectedAttemptsIslandImmigrant,
                                  int nInjectedAttemptsLocalSearch,
                                  int coveredGoals,
                                  int remainingGoals,
                                  int nFronts,
                                  double bestFitness,
                                  double meanFitness,
                                  double diversity,
                                  double largestSpeciesShare,
                                  int nQuotaProtected,
                                  int nNewbornProtected,
                                  int nIncubatorProtected,
                                  int nSharingAdjusted,
                                  int[] speciesPerSlot,
                                  int[] rankPerSlot,
                                  BreedingStats breeding,
                                  String problemCards,
                                  String blendChannelAdmissions) {
            this.gen = gen;
            this.elapsedMs = elapsedMs;
            this.popSize = popSize;
            this.nSpecies = nSpecies;
            this.nInjected = nInjected;
            this.nInjectedIncubator = nInjectedIncubator;
            this.nInjectedPostIncubator = nInjectedPostIncubator;
            this.dominantInjectionSource = dominantInjectionSource;
            this.nInjectedAttempts = nInjectedAttempts;
            this.dominantAttemptSource = dominantAttemptSource;
            this.nInjectedAttemptsLlmStagnation = nInjectedAttemptsLlmStagnation;
            this.nInjectedAttemptsLlmAsync = nInjectedAttemptsLlmAsync;
            this.nInjectedAttemptsIslandImmigrant = nInjectedAttemptsIslandImmigrant;
            this.nInjectedAttemptsLocalSearch = nInjectedAttemptsLocalSearch;
            this.coveredGoals = coveredGoals;
            this.remainingGoals = remainingGoals;
            this.nFronts = nFronts;
            this.bestFitness = bestFitness;
            this.meanFitness = meanFitness;
            this.diversity = diversity;
            this.largestSpeciesShare = largestSpeciesShare;
            this.nQuotaProtected = nQuotaProtected;
            this.nNewbornProtected = nNewbornProtected;
            this.nIncubatorProtected = nIncubatorProtected;
            this.nSharingAdjusted = nSharingAdjusted;
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
              .append(nSpecies).append(',')
              .append(nInjected).append(',')
              .append(nInjectedIncubator).append(',')
              .append(nInjectedPostIncubator).append(',')
              .append(dominantInjectionSource == null ? "" : dominantInjectionSource.name()).append(',')
              .append(nInjectedAttempts).append(',')
              .append(dominantAttemptSource == null ? "" : dominantAttemptSource.name()).append(',')
              .append(nInjectedAttemptsLlmStagnation).append(',')
              .append(nInjectedAttemptsLlmAsync).append(',')
              .append(nInjectedAttemptsIslandImmigrant).append(',')
              .append(nInjectedAttemptsLocalSearch).append(',')
              .append(coveredGoals).append(',')
              .append(remainingGoals).append(',')
              .append(nFronts).append(',')
              .append(formatDouble(bestFitness)).append(',')
              .append(formatDouble(meanFitness)).append(',')
              .append(formatDouble(diversity)).append(',')
              .append(formatDouble(largestSpeciesShare)).append(',')
              .append(nQuotaProtected).append(',')
              .append(nNewbornProtected).append(',')
              .append(nIncubatorProtected).append(',')
              .append(nSharingAdjusted).append(',');
            appendIntArray(sb, speciesPerSlot);
            sb.append(',');
            appendIntArray(sb, rankPerSlot);
            sb.append(',').append(breeding.nOffspring)
              .append(',').append(breeding.nBetter)
              .append(',').append(breeding.nNeutral)
              .append(',').append(breeding.nWorse)
              .append(',').append(breeding.nUnchanged)
              .append(',').append(breeding.nDiscarded)
              .append(',').append(breeding.nRandom)
              .append(',').append(breeding.nSurvived)
              .append(',').append(breeding.nPopNew)
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
