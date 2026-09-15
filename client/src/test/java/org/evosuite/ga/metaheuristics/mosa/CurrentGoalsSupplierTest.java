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

import org.evosuite.Properties;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies {@code AbstractMOSA.getCurrentGoals()} / {@code DynaMOSA.getCurrentGoals()}:
 * the set of goals LLM prompts (operators, stagnation, async producer, seed factory)
 * should target.
 *
 * <p>DynaMOSA's frontier is published, not read live: changes to {@code
 * goalsManager.getCurrentGoals()} become visible through {@code getCurrentGoals()} only
 * after {@code publishCurrentGoals()} runs, and the returned snapshot is immutable and
 * independent of whatever the manager does afterwards.
 */
class CurrentGoalsSupplierTest extends MosaTestSupport {

    @BeforeEach
    void resetArchive() {
        Archive.getArchiveInstance().reset();
    }

    @AfterEach
    void resetArchiveAfter() {
        Archive.getArchiveInstance().reset();
    }

    @Test
    void dynaMosaCurrentGoalsReturnsManagerFrontierNotArchiveOnlyGoal() {
        // The archive only accepts targets whose type maps to an enabled criterion.
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());

        TestFitnessFunction frontierGoal = simpleGoal("frontier");
        dynaMosa.goalsManager.getCurrentGoals().add(frontierGoal);
        dynaMosa.publishCurrentGoals();

        TestFitnessFunction archiveOnlyGoal = mock(MethodCoverageTestFitness.class);
        Archive.getArchiveInstance().addTarget(archiveOnlyGoal);

        Set<TestFitnessFunction> current = dynaMosa.getCurrentGoals();
        assertTrue(current.contains(frontierGoal),
                "current goals must include the manager's frontier goal");
        assertFalse(current.contains(archiveOnlyGoal),
                "current goals must not include a goal that is only an uncovered archive target");

        assertTrue(dynaMosa.getUncoveredGoals().contains(archiveOnlyGoal),
                "uncovered goals should still surface the archive-only goal");
    }

    @Test
    void dynaMosaCurrentGoalsIsEmptyBeforeThePublishThatWouldIncludeIt() {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());

        dynaMosa.goalsManager.getCurrentGoals().add(simpleGoal("not-yet-published"));

        assertTrue(dynaMosa.getCurrentGoals().isEmpty(),
                "a goal added to the manager's live set must not be visible until "
                        + "publishCurrentGoals() runs");
    }

    @Test
    void dynaMosaCurrentGoalsIsUnmodifiable() {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());
        dynaMosa.goalsManager.getCurrentGoals().add(simpleGoal("g"));
        dynaMosa.publishCurrentGoals();

        Set<TestFitnessFunction> current = dynaMosa.getCurrentGoals();
        assertThrows(UnsupportedOperationException.class,
                () -> current.add(simpleGoal("added-after-the-fact")),
                "the published snapshot must be unmodifiable");
    }

    @Test
    void dynaMosaCurrentGoalsIsNotTheManagersLiveSet() {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());
        dynaMosa.goalsManager.getCurrentGoals().add(simpleGoal("g"));
        dynaMosa.publishCurrentGoals();

        Set<TestFitnessFunction> snapshot = dynaMosa.getCurrentGoals();

        // Mutating the manager's live set after the snapshot was taken must not be
        // observable through the already-published snapshot.
        dynaMosa.goalsManager.getCurrentGoals().add(simpleGoal("added-after-the-fact"));

        assertEquals(1, snapshot.size(),
                "the snapshot must be independent of the manager's live set");
        assertNotSame(dynaMosa.goalsManager.getCurrentGoals(), snapshot,
                "getCurrentGoals() must never return the manager's live set");
    }

    @Test
    void aLaterPublishReflectsManagerChanges() {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());

        dynaMosa.publishCurrentGoals();
        assertTrue(dynaMosa.getCurrentGoals().isEmpty());

        TestFitnessFunction goal = simpleGoal("later");
        dynaMosa.goalsManager.getCurrentGoals().add(goal);
        dynaMosa.publishCurrentGoals();

        assertEquals(Collections.singleton(goal), dynaMosa.getCurrentGoals(),
                "a fresh publish must reflect goals added to the manager since the last one");
    }

    @Test
    void dynaMosaCurrentGoalsIsEmptyWhenGoalsManagerIsNull() {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);

        assertTrue(dynaMosa.getCurrentGoals().isEmpty(),
                "current goals must be empty before the goals manager is initialised");
    }

    @Test
    void mosaCurrentGoalsEqualsUncoveredGoals() {
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.METHOD};

        MOSA mosa = new MOSA(TestChromosome::new);

        TestFitnessFunction goal = mock(MethodCoverageTestFitness.class);
        Archive.getArchiveInstance().addTarget(goal);

        assertEquals(mosa.getUncoveredGoals(), mosa.getCurrentGoals(),
                "MOSA does not narrow the frontier: current goals are all uncovered goals");
    }

    static TestFitnessFunction simpleGoal(String name) {
        return new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual, ExecutionResult result) {
                return 1.0;
            }

            @Override
            public int compareTo(TestFitnessFunction o) {
                return 0;
            }

            @Override
            public boolean isMaximizationFunction() {
                return false;
            }

            @Override
            public String getTargetClass() {
                return "Test";
            }

            @Override
            public String getTargetMethod() {
                return name;
            }

            @Override
            public int hashCode() {
                return name.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                return this == o;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
