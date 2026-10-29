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
package org.evosuite.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public abstract class ListUtil {
    public static <T> List<T> tail(List<T> list) {
        return list.subList(1, list.size());
    }

    public static <T> boolean anyEquals(List<T> list, T obj) {
        return list.contains(obj);
    }

    public static <T> List<T> shuffledList(List<T> list) {
        ArrayList<T> result = new ArrayList<>(list);
        Collections.shuffle(result);
        return result;
    }

    public static <T> List<T> shuffledList(List<T> list, Random rnd) {
        ArrayList<T> result = new ArrayList<>(list);
        Collections.shuffle(result, rnd);
        return result;
    }

    private static int getIndex(List<?> population, double rankBias) {
        double r = Randomness.nextDouble();
        double d = rankBias
                - Math.sqrt((rankBias * rankBias)
                - (4.0 * (rankBias - 1.0) * r));
        int length = population.size();

        d = d / 2.0 / (rankBias - 1.0);

        //this is not needed because population is sorted based on Maximization
        //if(maximize)
        //  d = 1.0 - d; // to do that if we want to have Maximisation

        int index = (int) (length * d);
        return index;
    }

    /**
     * Picks an element of a list sorted best first, favouring the front.
     *
     * @param rankBias the selection pressure, between 1 and 2
     */
    public static <T> T selectRankBiased(List<T> list, double rankBias) {
        int index = getIndex(list, rankBias);
        return list.get(index);
    }
}
