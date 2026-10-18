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
 * synchronous mutation/crossover hooks, keyed by parent identity. A parent's
 * target is used by exactly the hook matching its kind: a crossover target
 * whose gate is rejected this generation is simply not sent, it is never
 * converted into a mutation attempt. Whichever hook consumes a parent's
 * target (via {@link #take}) removes it, so
 * the other hook sees nothing left for that parent.
 */
public final class SyncTargetPlan {

    /** Immutable, empty plan for when the SYNC hooks are off. */
    public static final SyncTargetPlan EMPTY = new SyncTargetPlan(null);

    private final Map<TestChromosome, OperatorTarget> byParent;
    private final int generation;

    /**
     * @param targets one target per parent; the selector already caps one per
     *                parent, but if two share a parent regardless, the first
     *                one encountered is kept.
     */
    public SyncTargetPlan(List<OperatorTarget> targets) {
        this(targets, -1);
    }

    /** Same as {@link #SyncTargetPlan(List)}, tagged with the generation it was built for. */
    public SyncTargetPlan(List<OperatorTarget> targets, int generation) {
        this.generation = generation;
        this.byParent = new IdentityHashMap<>();
        if (targets != null) {
            for (OperatorTarget target : targets) {
                byParent.putIfAbsent(target.getParent(), target);
            }
        }
    }

    /** The generation this plan's targets were selected for; {@code -1} when not tracked. */
    public int getGeneration() {
        return generation;
    }

    /**
     * Removes and returns {@code parent}'s target only if it is of {@code kind};
     * a target of the other kind is left in place for that kind's hook to
     * consume later, and null is returned.
     */
    public OperatorTarget take(TestChromosome parent, OperatorTarget.Kind kind) {
        OperatorTarget target = byParent.get(parent);
        if (target == null || target.getKind() != kind) {
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
