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
package org.evosuite.testcase.mutation;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RandomInsertionTest {

    @Test
    public void testInsertionProbabilitiesLogWarning() {
        // Just checking it doesn't crash when probabilities are zero
        Properties.INSERTION_UUT = 0.0;
        Properties.INSERTION_ENVIRONMENT = 0.0;
        Properties.INSERTION_PARAMETER = 0.0;

        RandomInsertion ri = new RandomInsertion();
        TestCase tc = new DefaultTestCase();
        // It shouldn't crash
        int pos = ri.insertStatement(tc, 0);
        // Since TestCluster is likely empty/not initialized with generators, it should fail to insert
        assertEquals(InsertionStrategy.INSERTION_ERROR, pos);
    }

    @Test
    public void testInsertionWithHighProbabilities() {
         // Even if we want to insert, if TestCluster is empty, it should fail gracefully
        Properties.INSERTION_UUT = 1.0;
        Properties.INSERTION_ENVIRONMENT = 0.0;
        Properties.INSERTION_PARAMETER = 0.0;

        RandomInsertion ri = new RandomInsertion();
        TestCase tc = new DefaultTestCase();
        int pos = ri.insertStatement(tc, 0);
        assertEquals(InsertionStrategy.INSERTION_ERROR, pos);
    }
}
