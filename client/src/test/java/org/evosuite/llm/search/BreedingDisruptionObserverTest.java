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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link BreedingDisruptionObserver} records the selector target
 * (goal + distance) a semantic operator worked on and the SYNC paired probe's
 * twin measurements alongside the rest of a disruption event.
 */
class BreedingDisruptionObserverTest {

    @TempDir
    Path tempDir;

    private boolean prevEnabled;
    private String prevOutputDir;

    @BeforeEach
    void setUp() {
        prevEnabled = Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED;
        prevOutputDir = Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR;
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = true;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = tempDir.toString();
        DisruptionRecorder.resetInstance();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = prevEnabled;
        Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = prevOutputDir;
        DisruptionRecorder.resetInstance();
    }

    private static TestFitnessFunction mockGoal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn(name);
        return goal;
    }

    @Test
    void semanticMutationRecordsTargetGoalAndDistances() {
        BreedingDisruptionObserver observer = new BreedingDisruptionObserver(DisruptionRecorder.getInstance(), false);
        TestFitnessFunction goal = mockGoal("goalA");
        TestChromosome parent = new TestChromosome();
        TestChromosome offspring = new TestChromosome();
        offspring.setFitness(goal, 0.0);
        OperatorTarget target = OperatorTarget.builder(parent, goal).distance(0.7).build();

        observer.recordMutation(offspring, parent, 0, OperatorAttemptResult.semanticApplied(target, null),
                true, 1, IsolatedProbe.NONE, SemanticProbeResult.NONE);

        DisruptionEvent event = DisruptionRecorder.getInstance().getEvents().get(0);
        assertEquals(GoalId.of(goal), event.getTargetGoalId());
        assertEquals(0.7, event.getTargetDistanceBefore());
        assertEquals(0.0, event.getTargetDistanceAfter());
    }

    @Test
    void standardEventWithoutTargetLeavesTargetCellsEmpty() {
        BreedingDisruptionObserver observer = new BreedingDisruptionObserver(DisruptionRecorder.getInstance(), false);
        TestChromosome parent = new TestChromosome();
        TestChromosome offspring = new TestChromosome();

        observer.recordMutation(offspring, parent, 0, OperatorAttemptResult.standardOnly(
                        OperatorAttemptResult.SkipReason.NOT_CONFIGURED),
                false, 1, IsolatedProbe.NONE, SemanticProbeResult.NONE);

        DisruptionEvent event = DisruptionRecorder.getInstance().getEvents().get(0);
        assertNull(event.getTargetGoalId());
        assertTrue(Double.isNaN(event.getTargetDistanceBefore()));
        assertTrue(Double.isNaN(event.getTargetDistanceAfter()));
        assertFalse(event.isPairedProbe());
    }

    @Test
    void probeWithTwinRecordsBothSidesOverTheFrozenGoals() {
        BreedingDisruptionObserver observer = new BreedingDisruptionObserver(DisruptionRecorder.getInstance(), false);
        TestFitnessFunction goal = mockGoal("goalB");
        TestChromosome parent = new TestChromosome();
        TestChromosome offspring = new TestChromosome();
        TestChromosome semanticSnapshot = new TestChromosome();
        semanticSnapshot.setFitness(goal, 0.0);
        TestChromosome twin = new TestChromosome();
        twin.setFitness(goal, 3.5);
        SemanticProbeResult probe = SemanticProbeResult.withTwin(semanticSnapshot, twin, false, goal);

        observer.recordMutation(offspring, parent, 0, OperatorAttemptResult.semanticApplied(),
                false, 1, IsolatedProbe.NONE, probe);

        DisruptionEvent event = DisruptionRecorder.getInstance().getEvents().get(0);
        assertEquals(0.0, event.getSemanticFitnessIsolated());
        assertEquals(0.0, event.getSemanticTargetDistanceIsolated());
        assertTrue(event.isPairedProbe());
        assertFalse(event.isPairedProbeFailure());
        assertFalse(event.isTwinChanged(), "an unchanged twin is a valid paired outcome");
        assertEquals(3.5, event.getTwinFitness());
        assertEquals(3.5, event.getTwinTargetDistance());
    }

    @Test
    void semanticCrossoverTakesItsTargetDistanceFromTheIsolatedSnapshot() {
        BreedingDisruptionObserver observer = new BreedingDisruptionObserver(DisruptionRecorder.getInstance(), false);
        TestFitnessFunction goal = mockGoal("goalC");
        TestChromosome parent = new TestChromosome();
        TestChromosome partner = new TestChromosome();
        TestChromosome offspring = new TestChromosome();
        OperatorTarget target = OperatorTarget.builder(parent, goal).distance(0.4).partner(partner).build();
        TestChromosome semanticSnapshot = new TestChromosome();
        semanticSnapshot.setFitness(goal, 0.0);

        observer.recordCrossover(offspring, parent, partner, 0, OperatorAttemptResult.semanticApplied(target, null),
                true, 1, IsolatedProbe.NONE, SemanticProbeResult.semanticOnly(semanticSnapshot, goal));

        DisruptionEvent event = DisruptionRecorder.getInstance().getEvents().get(0);
        assertEquals(0.4, event.getTargetDistanceBefore());
        assertEquals(0.0, event.getTargetDistanceAfter());
        assertEquals(System.identityHashCode(partner), event.getParent2Hash());
        assertFalse(event.isPairedProbe());
    }

    @Test
    void twinFailureRecordsFailureFlagButKeepsTheSemanticSide() {
        BreedingDisruptionObserver observer = new BreedingDisruptionObserver(DisruptionRecorder.getInstance(), false);
        TestFitnessFunction goal = mockGoal("goalD");
        TestChromosome parent = new TestChromosome();
        TestChromosome offspring = new TestChromosome();
        TestChromosome semanticSnapshot = new TestChromosome();
        semanticSnapshot.setFitness(goal, 0.25);

        observer.recordMutation(offspring, parent, 0, OperatorAttemptResult.semanticApplied(),
                false, 1, IsolatedProbe.NONE, SemanticProbeResult.twinFailed(semanticSnapshot, goal));

        DisruptionEvent event = DisruptionRecorder.getInstance().getEvents().get(0);
        assertEquals(0.25, event.getSemanticTargetDistanceIsolated());
        assertFalse(event.isPairedProbe());
        assertTrue(event.isPairedProbeFailure());
        assertTrue(Double.isNaN(event.getTwinFitness()));
        assertTrue(Double.isNaN(event.getTwinTargetDistance()));
    }
}
