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
package org.evosuite.llm.search.operator;

import org.evosuite.testcase.TestChromosome;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * One generation's supply of {@link OperatorTargetSelector} targets for the
 * synchronous mutation/crossover hooks, keyed by parent identity. A parent is
 * targeted at most once per generation: whichever hook consumes it first
 * (via {@link #takeMutation} or {@link #takeCrossover}) removes it, so the
 * other hook sees nothing left for that parent.
 */
public final class SyncTargetPlan {

    /** Immutable, empty plan for when the SYNC hooks are off. */
    public static final SyncTargetPlan EMPTY = new SyncTargetPlan(null);

    private final Map<TestChromosome, OperatorTarget> byParent;

    /**
     * @param targets one target per parent; the selector already caps one per
     *                parent, but if two share a parent regardless, the first
     *                one encountered is kept.
     */
    public SyncTargetPlan(List<OperatorTarget> targets) {
        this.byParent = new IdentityHashMap<>();
        if (targets != null) {
            for (OperatorTarget target : targets) {
                byParent.putIfAbsent(target.getParent(), target);
            }
        }
    }

    /**
     * Removes and returns {@code parent}'s target, whatever its kind: a
     * crossover target is converted to its mutation form (see
     * {@link OperatorTarget#asMutation()}) since the mutation hook has no use
     * for a partner. Null when {@code parent} has no target this generation.
     */
    public OperatorTarget takeMutation(TestChromosome parent) {
        OperatorTarget target = byParent.remove(parent);
        return target == null ? null : target.asMutation();
    }

    /**
     * Removes and returns {@code parent}'s target only if it is a crossover
     * target; a mutation target is left in place for {@link #takeMutation} to
     * consume later, and null is returned.
     */
    public OperatorTarget takeCrossover(TestChromosome parent) {
        OperatorTarget target = byParent.get(parent);
        if (target == null || target.getKind() != OperatorTarget.Kind.CROSSOVER) {
            return null;
        }
        byParent.remove(parent);
        return target;
    }

    /** Number of parents that still have an unconsumed target this generation. */
    public int remaining() {
        return byParent.size();
    }
}
