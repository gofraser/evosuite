/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.operators.ranking;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.comparators.DominanceComparator;
import org.evosuite.ga.comparators.PreferenceSortingComparator;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class ranks the test cases according to the
 * the "PreferenceCriterion" defined for the MOSA algorithm.
 *
 * @author Annibale Panichella, Fitsum M. Kifetew
 */
public class RankBasedPreferenceSorting<T extends Chromosome<T>> implements RankingFunction<T> {

    private static final long serialVersionUID = -6636175563989586394L;

    private static final Logger logger = LoggerFactory.getLogger(RankBasedPreferenceSorting.class);

    /**
     * A list containing all the fronts found during the search.
     */
    private List<List<T>> fronts = null;

    /**
     * Optional species context for species-aware tiebreaking in front-0 construction.
     * Maps each individual to its species ID. When set, tied candidates in the
     * preference front are resolved by preferring the candidate from the species
     * with fewer members already selected into front-0, promoting species diversity.
     * Transient: only meaningful within a single ranking call.
     */
    private transient Map<T, Integer> speciesContext;

    /**
     * Sets the species context for the next ranking computation. When provided,
     * front-0 tiebreaking will prefer candidates from rarer species.
     *
     * @param speciesContext maps individuals to species IDs, or null to disable
     */
    public void setSpeciesContext(Map<T, Integer> speciesContext) {
        this.speciesContext = speciesContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeRankingAssignment(List<T> solutions,
                                         Set<? extends FitnessFunction<T>> uncoveredGoals) {
        if (solutions.isEmpty()) {
            logger.debug("solution is empty");
            return;
        }

        this.fronts = new ArrayList<>(solutions.size());

        // first apply the "preference sorting" to the first front only
        // then compute the ranks according to the non-dominate sorting algorithm
        List<T> zeroFront = this.getZeroFront(solutions, uncoveredGoals);
        this.fronts.add(zeroFront);
        int frontIndex = 1;

        if (zeroFront.size() < Properties.POPULATION) {
            int rankedSolutions = zeroFront.size();
            DominanceComparator<T> comparator = new DominanceComparator<>(uncoveredGoals);

            List<T> remaining = new ArrayList<>(solutions.size());
            remaining.addAll(solutions);
            remaining.removeAll(zeroFront);
            while (rankedSolutions < Properties.POPULATION && remaining.size() > 0) {
                List<T> newFront = this.getNonDominatedSolutions(remaining, comparator, frontIndex);
                this.fronts.add(newFront);
                remaining.removeAll(newFront);
                rankedSolutions += newFront.size();
                frontIndex++;
            }

        } else {
            List<T> remaining = new ArrayList<>(solutions.size());
            remaining.addAll(solutions);
            remaining.removeAll(zeroFront);

            for (T t : remaining) {
                t.setRank(frontIndex);
            }
            this.fronts.add(remaining);
        }
    }

    /**
     * Returns the first (i.e. non-dominated) sub-front.
     *
     * @param solutionSet     the solutions to rank
     * @param uncoveredGoals the goals used for ranking
     * @return the non-dominated solutions (first sub-front)
     */
    private List<T> getZeroFront(List<T> solutionSet, Set<? extends FitnessFunction<T>> uncoveredGoals) {
        Set<T> zeroFront = new LinkedHashSet<>(solutionSet.size());
        // Track per-species representation in front-0 for species-aware tiebreaking
        Map<Integer, Integer> front0SpeciesCount = (speciesContext != null) ? new HashMap<>() : null;

        for (FitnessFunction<T> f : uncoveredGoals) {
            // for each uncovered goal, pick the best test using the proper comparator
            PreferenceSortingComparator<T> comp = new PreferenceSortingComparator<>(f);

            T best = null;
            for (T test : solutionSet) {
                int flag = comp.compare(test, best);
                if (flag < 0) {
                    best = test;
                } else if (flag == 0) {
                    if (shouldReplaceTied(best, test, front0SpeciesCount)) {
                        best = test;
                    }
                }
            }
            assert best != null;

            // Update front-0 species count for subsequent tiebreaks
            if (front0SpeciesCount != null) {
                Integer species = speciesContext.get(best);
                if (species != null) {
                    front0SpeciesCount.merge(species, 1, Integer::sum);
                }
            }

            best.setRank(0);
            zeroFront.add(best);
        }
        return new ArrayList<>(zeroFront);
    }

    /**
     * Decides whether {@code candidate} should replace {@code current} when both
     * are tied on fitness and secondary objectives. When species context is
     * available, prefers the candidate from the species with fewer members
     * already in front-0; otherwise falls back to a coin flip.
     */
    private boolean shouldReplaceTied(T current, T candidate,
                                       Map<Integer, Integer> front0SpeciesCount) {
        if (front0SpeciesCount != null && speciesContext != null) {
            Integer currentSpecies = speciesContext.get(current);
            Integer candidateSpecies = speciesContext.get(candidate);
            if (currentSpecies != null && candidateSpecies != null) {
                int currentCount = front0SpeciesCount.getOrDefault(currentSpecies, 0);
                int candidateCount = front0SpeciesCount.getOrDefault(candidateSpecies, 0);
                if (candidateCount < currentCount) {
                    return true;  // candidate's species is rarer in front-0
                }
                if (candidateCount > currentCount) {
                    return false; // current's species is rarer
                }
                // Equal counts — fall through to coin flip
            }
        }
        return Randomness.nextBoolean();
    }

    private List<T> getNonDominatedSolutions(List<T> solutions, DominanceComparator<T> comparator, int frontIndex) {
        List<T> front = new ArrayList<>(solutions.size());
        for (T p : solutions) {
            boolean isDominated = false;
            List<T> dominatedSolutions = new ArrayList<>(solutions.size());
            for (T best : front) {
                int flag = comparator.compare(p, best);
                if (flag < 0) {
                    dominatedSolutions.add(best);
                }
                if (flag > 0) {
                    isDominated = true;
                    break;
                }
            }
            if (isDominated) {
                continue;
            }

            p.setRank(frontIndex);
            front.add(p);
            front.removeAll(dominatedSolutions);
        }
        return front;
    }

    /**
     * {@inheritDoc}
     */
    public List<T> getSubfront(int rank) {
        if (this.fronts == null || rank >= this.fronts.size()) {
            return new ArrayList<>();
        }
        return this.fronts.get(rank);
    }

    /**
     * {@inheritDoc}
     */
    public int getNumberOfSubfronts() {
        return this.fronts.size();
    }
}
