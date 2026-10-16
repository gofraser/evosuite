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
package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.Properties;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An exception goal that is evaluated against an execution which called reflection must still be
 * known to the archive, because the goal manager hands goals derived from a chromosome to the
 * archive and the chromosome's own evaluation of the goal is what registers it.
 */
public class ExceptionGoalRegistrationTest {

    private Properties.Criterion[] originalCriteria;
    private boolean originalTestArchive;

    @BeforeEach
    public void setUp() {
        originalCriteria = Properties.CRITERION;
        originalTestArchive = Properties.TEST_ARCHIVE;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.EXCEPTION};
        Properties.TEST_ARCHIVE = true;
        Archive.getArchiveInstance().reset();
    }

    @AfterEach
    public void tearDown() {
        Archive.getArchiveInstance().reset();
        Properties.CRITERION = originalCriteria;
        Properties.TEST_ARCHIVE = originalTestArchive;
    }

    private static ExceptionCoverageTestFitness goal() {
        return new ExceptionCoverageTestFitness("com.example.Foo", "contains(Ljava/lang/String;)Z",
                NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
    }

    private static TestChromosome reflectiveChromosome() {
        TestChromosome chromosome = new TestChromosome();
        DefaultTestCase test = new DefaultTestCase();
        chromosome.setTestCase(test);
        ExecutionResult result = new ExecutionResult(test) {
            @Override
            public boolean calledReflection() {
                return true;
            }
        };
        chromosome.setLastExecutionResult(result);
        chromosome.setChanged(false);
        return chromosome;
    }

    @Test
    public void goalEvaluatedOnReflectiveExecutionIsRegistered() {
        ExceptionCoverageTestFitness goal = goal();

        double fitness = goal.getFitness(reflectiveChromosome());

        assertEquals(1.0, fitness);
        assertTrue(Archive.getArchiveInstance().hasTarget(goal));
    }

    @Test
    public void goalManagerAcceptsDerivedGoalWhoseExecutionCalledReflection() {
        StructuralGoalManager manager = new StructuralGoalManager(Collections.emptyList()) {
            @Override
            public void calculateFitness(TestChromosome c, GeneticAlgorithm<TestChromosome> ga) {
            }
        };
        ExceptionCoverageTestFitness goal = goal();

        // Throws AssertionError "Unknown goal" when the goal was never registered.
        manager.updateCoveredGoals(goal, reflectiveChromosome());

        assertTrue(Archive.getArchiveInstance().hasTarget(goal));
    }
}
