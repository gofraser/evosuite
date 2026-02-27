/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.coverage.io.input;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.Properties;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.evosuite.coverage.io.IOCoverageConstants.*;

public class InputCoverageTestFitnessTest {

    @Test
    public void testGetFitness() {
        Properties.TEST_ARCHIVE = false;
        // Goal: NUM_POSITIVE
        InputCoverageGoal numGoal = new InputCoverageGoal("Foo", "bar", 0, Type.INT_TYPE, NUM_POSITIVE);
        InputCoverageTestFitness numFitness = new InputCoverageTestFitness(numGoal);

        // Observed: -100 (NUM_NEGATIVE)
        InputCoverageGoal numCovered = new InputCoverageGoal("Foo", "bar", 0, Type.INT_TYPE, NUM_NEGATIVE, -100);

        Set<InputCoverageGoal> numGoals = new LinkedHashSet<>();
        numGoals.add(numCovered);
        Map<Integer, Set<InputCoverageGoal>> numMap = new LinkedHashMap<>();
        numMap.put(0, numGoals);

        ExecutionResult numResult = new ExecutionResult(new DefaultTestCase(), null);
        numResult.setInputGoals(numMap);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(numResult.test);

        double val = numFitness.getFitness(chromosome, numResult);

        System.out.println("Fitness for -100 to Positive: " + val);

        // If the current implementation is broken (contains check fails), it returns 1.0.
        // If correct, it should return distance > 1.0 (approx 101.0).

        Assertions.assertNotEquals(1.0, val, 0.001, "Fitness should reflect distance, not just covered/uncovered status");
        Assertions.assertTrue(val > 100.0, "Fitness should be > 1.0 for large distance");
    }
}
