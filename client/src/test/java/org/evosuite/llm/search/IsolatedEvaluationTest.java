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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedEvaluationTest {

    private boolean savedArchive;

    @BeforeEach
    void save() {
        savedArchive = Properties.TEST_ARCHIVE;
    }

    @AfterEach
    void restore() {
        Properties.TEST_ARCHIVE = savedArchive;
    }

    private static TestChromosome chromosome() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(test);
        return tc;
    }

    @Test
    void storesFitnessForExactlyTheFrozenGoalsWithTheArchiveSwitchedOff() {
        Properties.TEST_ARCHIVE = true;
        RecordingGoal a = new RecordingGoal("a", 0.0);
        RecordingGoal b = new RecordingGoal("b", 0.5);
        RecordingGoal stale = new RecordingGoal("stale", 0.9);
        TestChromosome tc = chromosome();
        tc.setFitness(stale, 0.9);

        IsolatedEvaluation.evaluate(tc, Arrays.asList(a, b));

        assertEquals(2, tc.getFitnessValues().size(), "stale values must not survive an isolated evaluation");
        assertEquals(0.0, IsolatedEvaluation.distance(tc, a), 1e-9);
        assertEquals(0.5, IsolatedEvaluation.distance(tc, b), 1e-9);
        assertTrue(Double.isNaN(IsolatedEvaluation.distance(tc, stale)));
        assertFalse(a.archiveWasOn, "goal fitness must be computed with the archive switched off");
        assertFalse(tc.isChanged());
        assertNotNull(tc.getLastExecutionResult());
        assertTrue(Properties.TEST_ARCHIVE, "the archive flag must be restored afterwards");
    }

    /**
     * Some goal types (ExceptionCoverageTestFitness, AllDefsCoverageTestFitness) return the
     * value from the two-argument overload without storing it themselves.
     */
    @Test
    void storesTheReturnedValueForGoalsThatDoNotUpdateTheIndividual() {
        RecordingGoal quiet = new RecordingGoal("quiet", 0.25, false);
        TestChromosome tc = chromosome();

        IsolatedEvaluation.evaluate(tc, Collections.singletonList(quiet));

        assertEquals(0.25, IsolatedEvaluation.distance(tc, quiet), 1e-9);
    }

    @Test
    void distanceIsNanForUnknownGoalOrChromosome() {
        RecordingGoal a = new RecordingGoal("a", 0.0);
        assertTrue(Double.isNaN(IsolatedEvaluation.distance(null, a)));
        assertTrue(Double.isNaN(IsolatedEvaluation.distance(chromosome(), null)));
        assertTrue(Double.isNaN(IsolatedEvaluation.distance(chromosome(), a)));
    }

    @Test
    void emptyGoalSetLeavesNoFitnessValues() {
        TestChromosome tc = chromosome();
        IsolatedEvaluation.evaluate(tc, Collections.emptyList());
        assertTrue(tc.getFitnessValues().isEmpty());
        assertNotNull(tc.getLastExecutionResult());
    }

    /** Goal that reports the archive flag it saw and stores a fixed distance. */
    private static final class RecordingGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final double value;
        private final boolean storesOwnValue;
        boolean archiveWasOn;

        RecordingGoal(String name, double value) {
            this(name, value, true);
        }

        RecordingGoal(String name, double value, boolean storesOwnValue) {
            this.name = name;
            this.value = value;
            this.storesOwnValue = storesOwnValue;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            archiveWasOn |= Properties.TEST_ARCHIVE;
            if (storesOwnValue) {
                updateIndividual(individual, value);
            }
            return value;
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return other instanceof RecordingGoal ? name.compareTo(((RecordingGoal) other).name) : compareClassName(other);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof RecordingGoal && name.equals(((RecordingGoal) other).name));
        }

        @Override
        public String getTargetClass() {
            return "Stub";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }
    }
}
