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
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leader-carry-over species assigner producing IDs that persist across generations.
 *
 * <p>Each species ID is minted once via a monotonic counter and retained in an internal
 * registry. Subsequent {@link #groupBySpecies} calls match individuals to existing
 * leaders (live or dormant) before creating new species, so a cluster keeps its ID
 * over its lifetime.
 *
 * <p>Dormancy: a species whose membership drops to zero is retained for up to
 * {@link Properties#SPECIES_DORMANT_GENERATIONS} generations so a re-emerging
 * cluster keeps its original ID. The dormancy counter advances only on
 * {@link #advanceGeneration()} (not on every {@code groupBySpecies} call), so it
 * is safe to call {@code groupBySpecies} multiple times per generation.
 *
 * <p>Leader refresh: on {@link #advanceGeneration()} each live leader is replaced
 * by the most central member of its species (minimum mean intra-species distance),
 * preventing the leader from drifting out of the cluster as it evolves.
 */
public class StableSpeciesAssigner implements SpeciesAssigner {

    private static final Logger logger = LoggerFactory.getLogger(StableSpeciesAssigner.class);

    private final SpeciesDistance distanceMetric;
    private final double threshold;
    private final int dormantWindow;

    /** Species registry. Insertion order matches mint order (monotonic IDs). */
    private final Map<Integer, SpeciesEntry> registry = new LinkedHashMap<>();
    private int nextId = 0;

    public StableSpeciesAssigner() {
        this(new JaccardSpeciesDistance(),
                Properties.SPECIATION_THRESHOLD,
                Properties.SPECIES_DORMANT_GENERATIONS);
    }

    public StableSpeciesAssigner(SpeciesDistance distanceMetric,
                                 double threshold,
                                 int dormantWindow) {
        this.distanceMetric = distanceMetric;
        this.threshold = threshold;
        this.dormantWindow = Math.max(0, dormantWindow);
    }

    @Override
    public Map<Integer, List<TestChromosome>> groupBySpecies(List<TestChromosome> population) {
        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();

        // Reset per-call membership snapshot on every entry so dormancy decisions
        // made at advanceGeneration() reflect the latest call's result.
        for (SpeciesEntry entry : registry.values()) {
            entry.lastMembers.clear();
        }

        for (TestChromosome individual : population) {
            int assigned = matchExisting(individual);
            if (assigned < 0) {
                assigned = mintNew(individual);
            }
            speciesMap.computeIfAbsent(assigned, k -> new ArrayList<>()).add(individual);
            registry.get(assigned).lastMembers.add(individual);
        }

        logger.debug("StableSpeciesAssigner: {} species ({} live in registry) from {} individuals",
                speciesMap.size(), registry.size(), population.size());
        return speciesMap;
    }

    /**
     * Advance the per-generation bookkeeping: increment dormancy for empty species,
     * retire IDs past the window, and refresh live leaders to the centroid of their
     * latest membership. Should be called once per generation, after the last
     * {@code groupBySpecies} call in that generation.
     */
    public void advanceGeneration() {
        List<Integer> toRetire = new ArrayList<>();
        for (Map.Entry<Integer, SpeciesEntry> e : registry.entrySet()) {
            SpeciesEntry entry = e.getValue();
            if (entry.lastMembers.isEmpty()) {
                entry.dormantGens++;
                if (entry.dormantGens > dormantWindow) {
                    toRetire.add(e.getKey());
                }
            } else {
                entry.dormantGens = 0;
                refreshLeader(entry);
            }
        }
        for (Integer id : toRetire) {
            registry.remove(id);
        }
        if (!toRetire.isEmpty()) {
            logger.debug("StableSpeciesAssigner: retired {} dormant species (registry now {})",
                    toRetire.size(), registry.size());
        }
    }

    /**
     * Number of species in the registry (live + dormant). Test/diagnostic accessor.
     */
    public int registrySize() {
        return registry.size();
    }

    /** Returns -1 if no existing leader is within the threshold. */
    private int matchExisting(TestChromosome individual) {
        int best = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Map.Entry<Integer, SpeciesEntry> e : registry.entrySet()) {
            double d = distanceMetric.distance(individual, e.getValue().leader);
            if (d <= threshold && d < bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private int mintNew(TestChromosome individual) {
        int id = nextId++;
        SpeciesEntry entry = new SpeciesEntry(individual);
        entry.lastMembers.add(individual);
        registry.put(id, entry);
        return id;
    }

    /**
     * Refresh the leader to the most central member of its current species
     * (minimum mean distance to other members). For singleton species the
     * sole member is the leader by construction.
     */
    private void refreshLeader(SpeciesEntry entry) {
        List<TestChromosome> members = entry.lastMembers;
        if (members.size() <= 1) {
            entry.leader = members.get(0);
            return;
        }
        TestChromosome best = members.get(0);
        double bestMean = Double.POSITIVE_INFINITY;
        for (TestChromosome candidate : members) {
            double sum = 0.0;
            for (TestChromosome other : members) {
                if (other == candidate) {
                    continue;
                }
                sum += distanceMetric.distance(candidate, other);
            }
            double mean = sum / (members.size() - 1);
            if (mean < bestMean) {
                bestMean = mean;
                best = candidate;
            }
        }
        entry.leader = best;
    }

    private static final class SpeciesEntry {
        TestChromosome leader;
        int dormantGens;
        final List<TestChromosome> lastMembers = new ArrayList<>();

        SpeciesEntry(TestChromosome leader) {
            this.leader = leader;
            this.dormantGens = 0;
        }
    }
}
