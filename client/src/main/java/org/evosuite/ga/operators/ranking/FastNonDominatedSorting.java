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

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.comparators.DominanceComparator;

import java.util.*;

/**
 * This class ranks the test cases according to the
 * "PreferenceCriterion" defined for the MOSA algorithm.
 *
 * @author Annibale Panichella, Fitsum M. Kifetew
 */

public class FastNonDominatedSorting<T extends Chromosome<T>> implements RankingFunction<T> {

    private static final long serialVersionUID = -5649595833522859850L;

    /**
     * An array containing all the fronts found during the search.
     */
    private List<List<T>> ranking;

    @Override
    public void computeRankingAssignment(List<T> solutions,
                                         Set<? extends FitnessFunction<T>> uncoveredGoals) {
        ranking = getNextNonDominatedFronts(solutions, uncoveredGoals);
    }


    /**
     * This method ranks the remaining test cases using the traditional "Non-Dominated Sorting Algorithm".
     *
     * @param solutionSet     set of test cases to rank with "Non-Dominated Sorting Algorithm"
     * @param uncoveredGoals  set of goals
     * @return the list of fronts according to the uncovered goals
     */
    private List<List<T>> getNextNonDominatedFronts(List<T> solutionSet,
                                                    Set<? extends FitnessFunction<T>> uncoveredGoals) {
        DominanceComparator<T> criterion = new DominanceComparator<>(uncoveredGoals);

        // dominateMe[i] contains the number of solutions dominating i
        int[] dominateMe = new int[solutionSet.size()];

        // dominatedList[k] contains the list of solutions dominated by k
        List<List<Integer>> dominatedList = new ArrayList<>(solutionSet.size());

        // front[i] contains the list of individuals belonging to the front i
        List<List<Integer>> front = new ArrayList<>(solutionSet.size() + 1);

        // flagDominate is an auxiliary encodings.variable
        int flagDominate;

        // Initialize the fronts
        for (int i = 0; i < solutionSet.size() + 1; i++) {
            front.add(new LinkedList<>());
        }

        // Initialize distance
        for (T solution : solutionSet) {
            solution.setDistance(Double.MAX_VALUE);
        }

        // -> Fast non dominated sorting algorithm
        for (int p = 0; p < solutionSet.size(); p++) {
            // Initialize the list of individuals that i dominate and the number
            // of individuals that dominate me
            dominatedList.add(new LinkedList<>());
            dominateMe[p] = 0;
        }

        for (int p = 0; p < (solutionSet.size() - 1); p++) {
            // For all q individuals , calculate if p dominates q or vice versa
            for (int q = p + 1; q < solutionSet.size(); q++) {
                flagDominate = criterion.compare(solutionSet.get(p), solutionSet.get(q));

                if (flagDominate == -1) {
                    dominatedList.get(p).add(q);
                    dominateMe[q]++;
                } else if (flagDominate == 1) {
                    dominatedList.get(q).add(p);
                    dominateMe[p]++;
                }
            }
            // If nobody dominates p, p belongs to the first front
        }
        for (int p = 0; p < solutionSet.size(); p++) {
            if (dominateMe[p] == 0) {
                front.get(0).add(p);
                solutionSet.get(p).setRank(1);
            }
        }

        // Obtain the rest of fronts
        int i = 0;
        Iterator<Integer> it1;
        Iterator<Integer> it2; // Iterators
        while (front.get(i).size() != 0) {
            i++;
            it1 = front.get(i - 1).iterator();
            while (it1.hasNext()) {
                it2 = dominatedList.get(it1.next()).iterator();
                while (it2.hasNext()) {
                    int index = it2.next();
                    dominateMe[index]--;
                    if (dominateMe[index] == 0) {
                        front.get(i).add(index);
                        solutionSet.get(index).setRank(i + 1);
                    }
                }
            }
        }
        List<List<T>> fronts = new ArrayList<>(i);
        // 0,1,2,....,i-1 are front, then i fronts
        for (int j = 0; j < i; j++) {
            List<T> currentFront = new ArrayList<>();
            it1 = front.get(j).iterator();
            while (it1.hasNext()) {
                currentFront.add(solutionSet.get(it1.next()));
            }
            fronts.add(currentFront);
        }
        return fronts;
    }

    @Override
    public List<T> getSubfront(int rank) {
        return ranking.get(rank);
    }

    @Override
    public int getNumberOfSubfronts() {
        return ranking.size();
    }

}
