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

import java.util.*;

/**
 * Default species policy that enforces per-species survival caps and
 * optionally balances parent selection across species.
 */
public class DefaultSpeciesPolicy implements SpeciesPolicy {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSpeciesPolicy.class);

    @Override
    public List<TestChromosome> applySurvivalCaps(
            List<TestChromosome> rankedSurvivors,
            Map<Integer, List<TestChromosome>> speciesMap,
            int targetSize,
            double survivalCap) {

        if (speciesMap.isEmpty() || survivalCap >= 1.0) {
            // No capping needed
            return rankedSurvivors.size() <= targetSize
                    ? new ArrayList<>(rankedSurvivors)
                    : new ArrayList<>(rankedSurvivors.subList(0, targetSize));
        }

        int maxPerSpecies = Math.max(1, (int) Math.ceil(targetSize * survivalCap));

        // Build identity map for fast species lookup
        Map<TestChromosome, Integer> individualToSpecies = new IdentityHashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
            for (TestChromosome tc : entry.getValue()) {
                individualToSpecies.put(tc, entry.getKey());
            }
        }

        // First pass: accept individuals respecting per-species cap
        Map<Integer, Integer> speciesCount = new HashMap<>();
        List<TestChromosome> accepted = new ArrayList<>(targetSize);
        List<TestChromosome> overflow = new ArrayList<>();

        for (TestChromosome individual : rankedSurvivors) {
            Integer species = individualToSpecies.get(individual);
            if (species == null) {
                // Unassigned individual (should not normally happen); accept it
                accepted.add(individual);
                continue;
            }
            int count = speciesCount.getOrDefault(species, 0);
            if (count < maxPerSpecies) {
                accepted.add(individual);
                speciesCount.put(species, count + 1);
            } else {
                overflow.add(individual);
            }
        }

        // Second pass: if accepted < targetSize, fill from overflow while still
        // enforcing the cap. An individual from overflow is admitted only if its
        // species has not yet reached maxPerSpecies in the *current* accepted set.
        // If no overflow individual can be admitted under the cap, we fall back to
        // accepting the highest-ranked remaining overflow individuals regardless of
        // species (deterministic, preserves ranked order) so the population does not
        // shrink unexpectedly.
        if (accepted.size() < targetSize && !overflow.isEmpty()) {
            List<TestChromosome> secondPassSkipped = new ArrayList<>();
            for (TestChromosome individual : overflow) {
                if (accepted.size() >= targetSize) {
                    break;
                }
                Integer species = individualToSpecies.get(individual);
                int count = speciesCount.getOrDefault(species, 0);
                if (count < maxPerSpecies) {
                    accepted.add(individual);
                    speciesCount.put(species, count + 1);
                } else {
                    secondPassSkipped.add(individual);
                }
            }
            // Deterministic fallback: if still under target, fill from remaining
            // overflow in ranked order without cap checks. This ensures population
            // size is maintained even when strict capping leaves too few candidates.
            for (TestChromosome individual : secondPassSkipped) {
                if (accepted.size() >= targetSize) {
                    break;
                }
                accepted.add(individual);
            }
        }

        // Truncate to target in case more were accumulated than needed
        if (accepted.size() > targetSize) {
            accepted = new ArrayList<>(accepted.subList(0, targetSize));
        }

        logger.debug("Survival cap: kept {} individuals (cap={} per species, {} species)",
                accepted.size(), maxPerSpecies, speciesMap.size());
        return accepted;
    }

    @Override
    public void applyFitnessSharing(List<TestChromosome> front,
                                    Map<Integer, List<TestChromosome>> speciesMap) {
        if (!Properties.SPECIES_FITNESS_SHARING_ENABLED
                || front == null
                || front.isEmpty()
                || speciesMap == null
                || speciesMap.isEmpty()) {
            return;
        }

        Map<TestChromosome, Integer> individualToSpecies = new IdentityHashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
            for (TestChromosome tc : entry.getValue()) {
                individualToSpecies.put(tc, entry.getKey());
            }
        }

        Map<Integer, Integer> speciesSizes = new HashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
            speciesSizes.put(entry.getKey(), entry.getValue().size());
        }

        for (TestChromosome tc : front) {
            if (tc == null) {
                continue;
            }
            double distance = tc.getDistance();
            if (!Double.isFinite(distance)) {
                continue;
            }
            Integer species = individualToSpecies.get(tc);
            if (species == null) {
                continue;
            }
            int size = Math.max(1, speciesSizes.getOrDefault(species, 1));
            tc.setDistance(distance / size);
        }
    }

    @Override
    public List<TestChromosome> applyProtectedSurvival(
            List<TestChromosome> rankedSurvivors,
            Map<Integer, List<TestChromosome>> speciesMap,
            int targetSize,
            double survivalCap,
            int currentGeneration,
            int minSurvivorsPerSpecies,
            int newbornProtectionGenerations,
            Map<Integer, Integer> speciesBirthGeneration) {

        if (speciesMap.isEmpty()) {
            return rankedSurvivors.size() <= targetSize
                    ? new ArrayList<>(rankedSurvivors)
                    : new ArrayList<>(rankedSurvivors.subList(0, targetSize));
        }

        final int quota = Math.max(0, minSurvivorsPerSpecies);
        final int newbornWindow = Math.max(0, newbornProtectionGenerations);
        final int maxPerSpecies = survivalCap >= 1.0
                ? Integer.MAX_VALUE
                : Math.max(1, (int) Math.ceil(targetSize * survivalCap));

        Map<TestChromosome, Integer> individualToSpecies = new IdentityHashMap<>();
        for (Map.Entry<Integer, List<TestChromosome>> entry : speciesMap.entrySet()) {
            for (TestChromosome tc : entry.getValue()) {
                individualToSpecies.put(tc, entry.getKey());
            }
        }

        Map<Integer, List<TestChromosome>> candidatesBySpecies = new LinkedHashMap<>();
        Map<Integer, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < rankedSurvivors.size(); i++) {
            TestChromosome tc = rankedSurvivors.get(i);
            Integer species = individualToSpecies.get(tc);
            if (species == null) {
                continue;
            }
            candidatesBySpecies.computeIfAbsent(species, k -> new ArrayList<>()).add(tc);
            firstSeen.putIfAbsent(species, i);
        }

        List<Integer> orderedSpecies = new ArrayList<>(candidatesBySpecies.keySet());
        orderedSpecies.sort((a, b) -> {
            int ageCmp = Integer.compare(
                    speciesAge(a, currentGeneration, speciesBirthGeneration),
                    speciesAge(b, currentGeneration, speciesBirthGeneration));
            if (ageCmp != 0) {
                return ageCmp;
            }
            return Integer.compare(firstSeen.getOrDefault(a, Integer.MAX_VALUE),
                    firstSeen.getOrDefault(b, Integer.MAX_VALUE));
        });

        List<TestChromosome> accepted = new ArrayList<>(targetSize);
        Set<TestChromosome> acceptedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, Integer> speciesCount = new HashMap<>();
        Map<Integer, Integer> cursor = new HashMap<>();
        List<TestChromosome> overflow = new ArrayList<>();

        // Pass A: minimum per-species quota (age-ordered).
        if (quota > 0) {
            for (Integer species : orderedSpecies) {
                if (accepted.size() >= targetSize) {
                    break;
                }
                List<TestChromosome> candidates = candidatesBySpecies.get(species);
                int want = Math.min(quota, Math.min(candidates.size(), maxPerSpecies));
                for (int i = 0; i < want && accepted.size() < targetSize; i++) {
                    TestChromosome pick = candidates.get(i);
                    if (acceptedSet.add(pick)) {
                        accepted.add(pick);
                        speciesCount.merge(species, 1, Integer::sum);
                        cursor.put(species, i + 1);
                    }
                }
            }
        }

        // Pass B: extra newborn protection (round-robin across newborn species).
        if (newbornWindow > 0 && accepted.size() < targetSize) {
            List<Integer> newbornSpecies = new ArrayList<>();
            for (Integer species : orderedSpecies) {
                if (speciesAge(species, currentGeneration, speciesBirthGeneration) < newbornWindow) {
                    newbornSpecies.add(species);
                }
            }

            boolean progressed;
            do {
                progressed = false;
                for (Integer species : newbornSpecies) {
                    if (accepted.size() >= targetSize) {
                        break;
                    }
                    int count = speciesCount.getOrDefault(species, 0);
                    if (count >= maxPerSpecies) {
                        continue;
                    }
                    List<TestChromosome> candidates = candidatesBySpecies.get(species);
                    int pos = cursor.getOrDefault(species, 0);
                    if (pos >= candidates.size()) {
                        continue;
                    }
                    TestChromosome pick = candidates.get(pos);
                    cursor.put(species, pos + 1);
                    if (acceptedSet.add(pick)) {
                        accepted.add(pick);
                        speciesCount.put(species, count + 1);
                        progressed = true;
                    }
                }
            } while (progressed && accepted.size() < targetSize);
        }

        // Pass C: ranked fill with cap, then deterministic overflow fallback.
        for (TestChromosome tc : rankedSurvivors) {
            if (accepted.size() >= targetSize) {
                break;
            }
            if (acceptedSet.contains(tc)) {
                continue;
            }
            Integer species = individualToSpecies.get(tc);
            if (species == null) {
                accepted.add(tc);
                acceptedSet.add(tc);
                continue;
            }
            int count = speciesCount.getOrDefault(species, 0);
            if (count < maxPerSpecies) {
                accepted.add(tc);
                acceptedSet.add(tc);
                speciesCount.put(species, count + 1);
            } else {
                overflow.add(tc);
            }
        }

        for (TestChromosome tc : overflow) {
            if (accepted.size() >= targetSize) {
                break;
            }
            if (acceptedSet.add(tc)) {
                accepted.add(tc);
            }
        }

        if (accepted.size() > targetSize) {
            accepted = new ArrayList<>(accepted.subList(0, targetSize));
        }

        return accepted;
    }

    private static int speciesAge(int speciesId,
                                  int currentGeneration,
                                  Map<Integer, Integer> speciesBirthGeneration) {
        if (speciesBirthGeneration == null || speciesBirthGeneration.isEmpty()) {
            return Integer.MAX_VALUE / 2;
        }
        Integer birth = speciesBirthGeneration.get(speciesId);
        if (birth == null) {
            return Integer.MAX_VALUE / 2;
        }
        return Math.max(0, currentGeneration - birth);
    }

    @Override
    public List<TestChromosome> balanceParentPool(
            List<TestChromosome> population,
            Map<Integer, List<TestChromosome>> speciesMap) {

        if (!Properties.SPECIES_BALANCE_PARENT_SELECTION || speciesMap.size() <= 1) {
            // Always return a defensive copy so callers doing clear+addAll are safe
            return new ArrayList<>(population);
        }

        // Round-robin across species to build a balanced pool
        int targetSize = population.size();
        List<TestChromosome> balanced = new ArrayList<>(targetSize);
        List<List<TestChromosome>> speciesLists = new ArrayList<>(speciesMap.values());

        int maxLen = 0;
        for (List<TestChromosome> members : speciesLists) {
            maxLen = Math.max(maxLen, members.size());
        }

        outer:
        for (int i = 0; i < maxLen; i++) {
            for (List<TestChromosome> members : speciesLists) {
                if (balanced.size() >= targetSize) {
                    break outer;
                }
                if (i < members.size()) {
                    balanced.add(members.get(i));
                }
            }
        }

        logger.debug("Balanced parent pool: {} individuals from {} species",
                balanced.size(), speciesLists.size());
        return balanced;
    }
}
