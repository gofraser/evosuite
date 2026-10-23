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
package org.evosuite.testsuite.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TestSuiteFactoryTest {

    @Test
    public void testTestSuiteChromosomeFactoryUsesCorrectFactory() {
        // Arrange
        ChromosomeFactory<TestChromosome> mockFactory = mock(ChromosomeFactory.class);
        when(mockFactory.getChromosome()).thenReturn(new TestChromosome());

        TestSuiteChromosomeFactory factory = new TestSuiteChromosomeFactory(mockFactory);

        // Act
        TestSuiteChromosome suite = factory.getChromosome();

        // Assert
        assertEquals(mockFactory, suite.getTestChromosomeFactory());
        // Verify mock was called at least once (since MIN_INITIAL_TESTS defaults to 1)
        verify(mockFactory, atLeastOnce()).getChromosome();
    }

    @Test
    public void testPropertiesRangeCheck() {
        // Save properties
        int originalMin = Properties.MIN_INITIAL_TESTS;
        int originalMax = Properties.MAX_INITIAL_TESTS;

        try {
            Properties.MIN_INITIAL_TESTS = 10;
            Properties.MAX_INITIAL_TESTS = 5;

            TestSuiteChromosomeFactory factory = new TestSuiteChromosomeFactory();
            TestSuiteChromosome suite = factory.getChromosome();
            assertNotNull(suite);
            // It should work without exception, using min as max

        } finally {
            Properties.MIN_INITIAL_TESTS = originalMin;
            Properties.MAX_INITIAL_TESTS = originalMax;
        }
    }
}
