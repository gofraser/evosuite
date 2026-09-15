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

import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTargetTest {

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        return chromosome;
    }

    private static TestFitnessFunction goal(String name) {
        return new StubGoal(name);
    }

    @Test
    void mutationTargetHasEmptyPartnerAndCard() {
        TestChromosome parent = chromosome(1);
        OperatorTarget target = OperatorTarget.builder(parent, goal("g")).distance(0.3).priority(0.7).build();

        assertEquals(OperatorTarget.Kind.MUTATION, target.getKind());
        assertFalse(target.getPartner().isPresent());
        assertFalse(target.getCard().isPresent());
    }

    @Test
    void crossoverTargetExposesPartner() {
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        OperatorTarget target = OperatorTarget.builder(parent, goal("g"))
                .distance(0.3).priority(0.7).partner(partner).build();

        assertEquals(OperatorTarget.Kind.CROSSOVER, target.getKind());
        assertTrue(target.getPartner().isPresent());
        assertEquals(partner, target.getPartner().get());
    }

    @Test
    void cardIsExposedWhenSet() {
        TestChromosome parent = chromosome(1);
        TestFitnessFunction g = goal("g");
        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("card").relatedGoals(Collections.singletonList(g))
                .impact(1.0).blockage(1.0).confidence(1.0).build();
        OperatorTarget target = OperatorTarget.builder(parent, g).distance(0.3).priority(0.7).card(card).build();

        assertTrue(target.getCard().isPresent());
        assertEquals(card, target.getCard().get());
    }

    @Test
    void keyIdentifiesOnlyTheGoal() {
        TestChromosome parentA = chromosome(1);
        TestChromosome parentB = chromosome(2);
        TestFitnessFunction g = goal("shared-goal");

        RepeatedInjectionTarget fromA =
                OperatorTarget.builder(parentA, g).distance(0.3).priority(0.7).build().toRepeatedInjectionTarget();
        RepeatedInjectionTarget fromB =
                OperatorTarget.builder(parentB, g).distance(0.3).priority(0.7).build().toRepeatedInjectionTarget();

        assertEquals(fromA.getKey(), fromB.getKey(), "key must depend only on the goal, not on the parent");
        assertTrue(fromA.getKey().startsWith("operator|"));
        assertNotEquals(fromA.getFingerprint(), fromB.getFingerprint(),
                "fingerprint must distinguish different parents for the same goal");
    }

    @Test
    void partnerChangesFingerprintButNotKey() {
        TestChromosome parent = chromosome(1);
        TestChromosome partner = chromosome(2);
        TestFitnessFunction g = goal("g");

        RepeatedInjectionTarget mutation =
                OperatorTarget.builder(parent, g).distance(0.3).priority(0.7).build().toRepeatedInjectionTarget();
        RepeatedInjectionTarget crossover = OperatorTarget.builder(parent, g)
                .distance(0.3).priority(0.7).partner(partner).build().toRepeatedInjectionTarget();

        assertEquals(mutation.getKey(), crossover.getKey());
        assertNotEquals(mutation.getFingerprint(), crossover.getFingerprint());
        assertTrue(crossover.getFingerprint().contains("|partner="));
        assertFalse(mutation.getFingerprint().contains("|partner="));
    }

    @Test
    void sameParentAndGoalProduceSameFingerprint() {
        TestChromosome parent = chromosome(7);
        TestFitnessFunction g = goal("g");

        RepeatedInjectionTarget first =
                OperatorTarget.builder(parent, g).distance(0.1).priority(0.9).build().toRepeatedInjectionTarget();
        RepeatedInjectionTarget second =
                OperatorTarget.builder(parent, g).distance(0.2).priority(0.5).build().toRepeatedInjectionTarget();

        assertEquals(first.getKey(), second.getKey());
        assertEquals(first.getFingerprint(), second.getFingerprint(),
                "fingerprint depends on parent/partner test code, not on distance or priority");
    }

    /** Minimal stub goal whose fitness is read from the individual's cached fitness map. */
    static final class StubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;

        StubGoal(String name) {
            this.name = name;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return individual.getFitnessValues().getOrDefault(this, 1.0);
        }

        @Override
        public ExecutionResult runTest(org.evosuite.testcase.TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            if (!(other instanceof StubGoal)) {
                return compareClassName(other);
            }
            return name.compareTo(((StubGoal) other).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StubGoal)) {
                return false;
            }
            return name.equals(((StubGoal) other).name);
        }

        @Override
        public String getTargetClass() {
            return "example.Target";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }

        @Override
        public String toString() {
            return "StubGoal[" + name + "]";
        }
    }
}
