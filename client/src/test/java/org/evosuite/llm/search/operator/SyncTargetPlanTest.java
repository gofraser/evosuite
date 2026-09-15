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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class SyncTargetPlanTest {

    private static TestChromosome chromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private static TestFitnessFunction goal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        return goal;
    }

    @Test
    void takeMutationRemovesTheTarget() {
        TestChromosome parent = chromosome();
        OperatorTarget target = OperatorTarget.builder(parent, goal("g")).distance(0.4).build();
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(target));

        assertSame(target, plan.takeMutation(parent));
        assertNull(plan.takeMutation(parent), "a parent must be targeted at most once per generation");
        assertEquals(0, plan.remaining());
    }

    @Test
    void takeMutationConvertsCrossoverTargetToMutationKind() {
        TestChromosome parent = chromosome();
        TestChromosome partner = chromosome();
        TestFitnessFunction goal = goal("g");
        OperatorTarget crossover = OperatorTarget.builder(parent, goal)
                .distance(0.3).priority(0.7).partner(partner).build();
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(crossover));

        OperatorTarget taken = plan.takeMutation(parent);

        assertEquals(OperatorTarget.Kind.MUTATION, taken.getKind());
        assertEquals(goal, taken.getGoal());
        assertEquals(0.3, taken.getDistance(), 1e-9);
        assertEquals(0.7, taken.getPriority(), 1e-9);
        assertFalse(taken.getPartner().isPresent());
    }

    @Test
    void takeCrossoverLeavesMutationTargetInPlaceAndReturnsNull() {
        TestChromosome parent = chromosome();
        OperatorTarget mutationTarget = OperatorTarget.builder(parent, goal("g")).build();
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(mutationTarget));

        assertNull(plan.takeCrossover(parent));
        assertEquals(1, plan.remaining(), "a mutation-kind target must remain for takeMutation");
        assertSame(mutationTarget, plan.takeMutation(parent));
    }

    @Test
    void takeCrossoverRemovesCrossoverTarget() {
        TestChromosome parent = chromosome();
        TestChromosome partner = chromosome();
        OperatorTarget crossover = OperatorTarget.builder(parent, goal("g")).partner(partner).build();
        SyncTargetPlan plan = new SyncTargetPlan(Collections.singletonList(crossover));

        assertSame(crossover, plan.takeCrossover(parent));
        assertEquals(0, plan.remaining());
        assertNull(plan.takeMutation(parent));
    }

    @Test
    void unknownParentReturnsNull() {
        SyncTargetPlan plan = new SyncTargetPlan(
                Collections.singletonList(OperatorTarget.builder(chromosome(), goal("g")).build()));

        assertNull(plan.takeMutation(chromosome()));
        assertNull(plan.takeCrossover(chromosome()));
    }

    @Test
    void emptyPlanIsAlwaysSafe() {
        assertNull(SyncTargetPlan.EMPTY.takeMutation(chromosome()));
        assertNull(SyncTargetPlan.EMPTY.takeCrossover(chromosome()));
        assertEquals(0, SyncTargetPlan.EMPTY.remaining());
    }

    @Test
    void firstTargetWinsWhenTwoShareAParent() {
        TestChromosome parent = chromosome();
        OperatorTarget first = OperatorTarget.builder(parent, goal("a")).build();
        OperatorTarget second = OperatorTarget.builder(parent, goal("b")).build();
        SyncTargetPlan plan = new SyncTargetPlan(Arrays.asList(first, second));

        assertEquals(1, plan.remaining());
        assertSame(first, plan.takeMutation(parent));
    }
}
