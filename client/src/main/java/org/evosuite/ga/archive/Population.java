/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.ga.archive;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.Randomness;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

class Population implements Serializable {

    private static final long serialVersionUID = 1671692598239736237L;

    private int counter = 0;

    private int capacity;

    private final List<Pair<Double, TestChromosome>> solutions;

    /**
     * @param populationSize
     */
    Population(int populationSize) {
        this.capacity = populationSize;
        this.solutions = new ArrayList<>(populationSize);
    }

    /**
     * @return
     */
    int counter() {
        return this.counter;
    }

    /**
     * @return
     */
    boolean isCovered() {
        return this.solutions.size() == 1 && this.capacity == 1
                && this.solutions.get(0).getLeft() == 1.0;
    }

    /**
     * @param h [0,1] value, where 1 means that the target is covered, and whereas 0 is the worst
     *          possible heuristics value
     * @param t
     */
    boolean addSolution(Double h, TestChromosome t) {
        assert h >= 0.0 && h <= 1.0;

        if (h == 0.0) {
            // from the paper that describes this type of archive: "if h=0, the test is not added
            // regardless of the following conditions"
            return false;
        }

        if (h < 1.0 && this.isCovered()) {
            // candidate solution T does not cover the already fully covered target, therefore there is
            // no way it could be any better
            return false;
        }

        Pair<Double, TestChromosome> candidateSolution = new ImmutablePair<>(h, t);

        boolean added = false;

        // does the candidate solution fully cover the target?
        if (h == 1.0) {
            // yes. has the target been fully covered by a previous solution?
            if (this.isCovered()) {
                Pair<Double, TestChromosome> currentSolution = this.solutions.get(0);

                if (isPairBetterThanCurrent(currentSolution, candidateSolution)) {
                    added = true;
                    this.solutions.set(0, candidateSolution);
                }
            } else {
                // as the target is now fully covered by the candidate solution T, from now on there is no
                // need to keep more than one solution, only the single best one. therefore, we can get
                // rid of all solutions (if any) and shrink the number of solutions to only one.
                added = true;
                this.capacity = 1;
                this.solutions.clear();
                this.solutions.add(candidateSolution);
            }
        } else {
            // no, candidate solution T does not fully cover the target.

            // is there enough room for yet another solution?
            if (this.solutions.size() < this.capacity) {
                // yes, there is.

                // as an optimisation, in here we could check whether candidateSolution is an existing
                // solution, however it could be quite expensive to do it and most likely not worth it
                this.solutions.add(candidateSolution);
                this.sortPairSolutions(); // keep solutions sorted from the best to the worse
            } else {
                // no, there is not. so, replace the worst one, if candidate is better.
                this.sortPairSolutions();
                Pair<Double, TestChromosome> worstSolution = this.solutions.get(this.capacity - 1);

                if (isPairBetterThanCurrent(worstSolution, candidateSolution)) {
                    this.solutions.set(this.capacity - 1, candidateSolution);
                }
            }
        }

        // a set of solutions larger that a maximum capacity would be considered illegal
        assert this.solutions.size() <= this.capacity;

        if (added) {
            // reset counter if and only if a new/better solution has been found
            this.counter = 0;
        }

        return added;
    }

    /**
     * @param currentSolution
     * @param candidateSolution
     * @return
     */
    private boolean isPairBetterThanCurrent(Pair<Double, TestChromosome> currentSolution,
                                            Pair<Double, TestChromosome> candidateSolution) {
        int cmp = Double.compare(currentSolution.getLeft(), candidateSolution.getLeft());
        if (cmp < 0) {
            return true;
        } else if (cmp > 0) {
            return false;
        }
        assert cmp == 0;

        return Archive.isBetterThanCurrent(currentSolution.getRight(), candidateSolution.getRight());
    }

    /**
     * @return
     */
    TestChromosome sampleSolution() {
        if (this.numSolutions() == 0) {
            return null;
        }
        this.counter++;
        return Randomness.choice(this.solutions).getRight();
    }

    /**
     * DESC sort, i.e., from the pair with the highest h to the pair with the lowest h
     */
    private void sortPairSolutions() {
        this.solutions.sort((solution0, solution1) -> {
            if (solution0.getLeft() < solution1.getLeft()) {
                return 1;
            } else if (solution0.getLeft() > solution1.getLeft()) {
                return -1;
            }
            return 0;
        });
    }

    /**
     * @return
     */
    int numSolutions() {
        return this.solutions.size();
    }

    /**
     * @return
     */
    TestChromosome getBestSolutionIfAny() {
        if (this.numSolutions() == 0 || !this.isCovered()) {
            return null;
        }
        return this.solutions.get(0).getRight();
    }

    /**
     * @param newPopulationSize
     */
    void shrinkPopulation(int newPopulationSize) {
        assert newPopulationSize > 0;

        if (this.isCovered()) {
            return;
        }

        this.capacity = newPopulationSize;

        if (this.numSolutions() < newPopulationSize) {
            // no need to shrink it
            return;
        }

        List<Pair<Double, TestChromosome>> shrinkSolutions = new ArrayList<>(newPopulationSize);
        for (int i = 0; i < newPopulationSize; i++) {
            shrinkSolutions.add(this.solutions.get(i));
        }
        this.solutions.clear();
        this.solutions.addAll(shrinkSolutions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return 31 * counter + capacity + this.solutions.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }

        Population p = (Population) obj;
        if (this.counter != p.counter) {
            return false;
        }
        if (this.capacity != p.capacity) {
            return false;
        }
        if (this.solutions.size() != p.solutions.size()) {
            return false;
        }

        return this.solutions.equals(p.solutions);
    }
}
