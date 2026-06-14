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
package org.evosuite.ga.metaheuristics.mosa;

import java.util.Map;

/**
 * Fate of an offspring relative to its parent, used for plateau diagnostics
 * (is the search drifting neutrally or producing rejected offspring?).
 *
 * <p>Classification deliberately compares fitness only over goals present in
 * <em>both</em> fitness maps: in MOSA/DynaMOSA, parents can carry stale cached
 * entries for goals that were covered and archived before the offspring was
 * evaluated, so whole-map sums are not comparable.
 */
public enum OffspringFate {

    BETTER, NEUTRAL, WORSE;

    static final double EPS = 1e-8;

    /**
     * Classifies an offspring's fitness map against its parent's.
     *
     * <ul>
     *   <li>{@link #BETTER}: the offspring covers a goal (value {@code 0.0})
     *       for which the parent has no value or a positive value, or the
     *       shared-goal sum improves by more than {@link #EPS}.</li>
     *   <li>{@link #WORSE}: the shared-goal sum degrades by more than
     *       {@link #EPS} (and no new coverage).</li>
     *   <li>{@link #NEUTRAL}: otherwise.</li>
     * </ul>
     *
     * @param offspring the offspring's fitness values (goal → fitness)
     * @param parent    the parent's fitness values
     * @param <K>       goal key type
     * @return the fate
     */
    public static <K> OffspringFate classify(Map<K, Double> offspring, Map<K, Double> parent) {
        double offspringSum = 0.0;
        double parentSum = 0.0;
        boolean newCoverage = false;
        for (Map.Entry<K, Double> e : offspring.entrySet()) {
            Double ov = e.getValue();
            if (ov == null) {
                continue;
            }
            Double pv = parent.get(e.getKey());
            if (ov == 0.0 && (pv == null || pv > 0.0)) {
                newCoverage = true;
            }
            if (pv != null) {
                offspringSum += ov;
                parentSum += pv;
            }
        }
        if (newCoverage || offspringSum < parentSum - EPS) {
            return BETTER;
        }
        if (offspringSum > parentSum + EPS) {
            return WORSE;
        }
        return NEUTRAL;
    }
}
