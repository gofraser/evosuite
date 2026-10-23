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
package org.evosuite.coverage.mutation;

import org.evosuite.Properties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * Counts how often executing a mutant timed out and disables mutants that timed out too often.
 *
 * @author Gordon Fraser
 */
public final class MutationTimeouts {

    private static final Map<Mutation, Integer> timeouts = new HashMap<>();

    private static final int MAX_TIMEOUTS = Properties.MUTATION_TIMEOUTS;

    private static final Set<Mutation> disabled = new HashSet<>();

    private MutationTimeouts() {
    }

    /**
     * <p>isDisabled.</p>
     *
     * @param mutation a {@link org.evosuite.coverage.mutation.Mutation} object.
     * @return a boolean.
     */
    public static boolean isDisabled(Mutation mutation) {
        return disabled.contains(mutation);
    }

    /**
     * Records a timeout for the mutant and disables it once it has timed out too often.
     *
     * @param mutation a {@link org.evosuite.coverage.mutation.Mutation} object.
     */
    public static void timeOut(Mutation mutation) {
        int t = timeouts.merge(mutation, 1, Integer::sum);
        if (t >= MAX_TIMEOUTS) {
            disabled.add(mutation);
        }
    }

    /**
     * Disables the mutant because executing it raised an exception.
     *
     * @param mutation a {@link org.evosuite.coverage.mutation.Mutation} object.
     */
    public static void raisedException(Mutation mutation) {
        disabled.add(mutation);
    }

    /**
     * Resets all static fields.
     */
    public static void resetStatic() {
        timeouts.clear();
        disabled.clear();
    }

}
