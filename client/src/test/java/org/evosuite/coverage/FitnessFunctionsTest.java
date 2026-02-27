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
package org.evosuite.coverage;

import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.mutation.MutationFactory;
import org.evosuite.coverage.mutation.StrongMutationSuiteFitness;
import org.evosuite.coverage.mutation.StrongMutationTestFitness;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FitnessFunctionsTest {

    @Test
    public void testGetFitnessFunction() {
        TestSuiteFitnessFunction ff = FitnessFunctions.getFitnessFunction(Criterion.BRANCH);
        assertTrue(ff instanceof BranchCoverageSuiteFitness);

        ff = FitnessFunctions.getFitnessFunction(Criterion.STRONGMUTATION);
        assertTrue(ff instanceof StrongMutationSuiteFitness);
    }

    @Test
    public void testGetFitnessFactory() {
        Object factory = FitnessFunctions.getFitnessFactory(Criterion.BRANCH);
        assertTrue(factory instanceof BranchCoverageFactory);

        factory = FitnessFunctions.getFitnessFactory(Criterion.STRONGMUTATION);
        assertTrue(factory instanceof MutationFactory);
    }

    @Test
    public void testGetTestFitnessFunctionClass() {
        Class<?> clazz = FitnessFunctions.getTestFitnessFunctionClass(Criterion.BRANCH);
        assertEquals(BranchCoverageTestFitness.class, clazz);

        clazz = FitnessFunctions.getTestFitnessFunctionClass(Criterion.STRONGMUTATION);
        assertEquals(StrongMutationTestFitness.class, clazz);
    }
}
