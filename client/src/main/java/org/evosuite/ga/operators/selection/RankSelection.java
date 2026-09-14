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
package org.evosuite.ga.operators.selection;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.utils.Randomness;

import java.util.List;


/**
 * {@inheritDoc}
 *
 * <p>Selects an individual by its rank.
 */
public class RankSelection<T extends Chromosome<T>> extends SelectionFunction<T> {

    private static final long serialVersionUID = 7849303009915557682L;

    public RankSelection() {
        // empty constructor
    }

    public RankSelection(RankSelection<?> other) {
        // empty copy constructor
    }

    /**
     * Returns the index of the next individual selected from the given
     * population, which is assumed to be already sorted.
     *
     * @param population the population to select an individual from
     * @return the index of the selected individual in the population
     * @implNote Approximates the index of the selected individual in {@code
     *     O(1)} by transforming an equally distributed random variable {@code
     *     0 <= r <= 1}, as described by Whitley in the GENITOR algorithm (1989).
     *     For rank biases between 1 and 2, this produces results almost identical
     *     to the text-book specification of rank selection.
     */
    @Override
    public int getIndex(List<T> population) {
        return RankSelection.getIdx(population);
    }

    /**
     * Calculates the index.
     *
     * @param list the list
     * @return the index
     */
    public static int getIdx(final List<?> list) {
        return getIndex(list.size(), Properties.RANK_BIAS);
    }

    /**
     * Maps a uniform random draw to an index into a size-{@code size} list assumed sorted from
     * best (index {@code 0}) to worst, using the classic rank-selection formula with bias
     * {@code bias} (the {@code O(1)} GENITOR approximation described by Whitley, 1989). The best
     * ranked element is selected roughly {@code bias} times as often as the worst one. A
     * {@code bias} of {@code 1.0} or below degenerates to uniform selection over the list
     * (and also avoids the division by zero the formula would otherwise hit at {@code bias == 1.0}).
     *
     * @param size the number of candidates to choose among
     * @param bias the rank bias; {@code <= 1.0} means uniform
     * @return the selected index, in {@code [0, size)}
     */
    public static int getIndex(final int size, final double bias) {
        if (size <= 1) {
            return 0;
        }
        if (bias <= 1.0) {
            return Randomness.nextInt(size);
        }

        double r = Randomness.nextDouble();
        double d = bias - Math.sqrt((bias * bias) - (4.0 * (bias - 1.0) * r));
        d = d / 2.0 / (bias - 1.0);

        //this is not needed because population is sorted based on Maximization
        //if(maximize)
        //    d = 1.0 - d; // to do that if we want to have Maximisation

        int index = (int) (size * d);
        if (index >= size) {
            index = size - 1;
        }
        return index;
    }
}
