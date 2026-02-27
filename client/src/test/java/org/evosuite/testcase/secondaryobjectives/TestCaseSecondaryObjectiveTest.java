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
package org.evosuite.testcase.secondaryobjectives;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestCaseSecondaryObjectiveTest {

    private Properties.SecondaryObjective[] originalObjectives;

    @BeforeEach
    public void setUp() {
        originalObjectives = Properties.SECONDARY_OBJECTIVE;
        TestChromosome.getSecondaryObjectives().clear();
    }

    @AfterEach
    public void tearDown() {
        Properties.SECONDARY_OBJECTIVE = originalObjectives;
        TestChromosome.getSecondaryObjectives().clear();
    }

    @Test
    public void testSetSecondaryObjectives_TotalLength() {
        Properties.SECONDARY_OBJECTIVE = new Properties.SecondaryObjective[]{Properties.SecondaryObjective.TOTAL_LENGTH};
        TestCaseSecondaryObjective.setSecondaryObjectives();
        assertEquals(1, TestChromosome.getSecondaryObjectives().size());
        assertTrue(TestChromosome.getSecondaryObjectives().get(0) instanceof MinimizeLengthSecondaryObjective);
    }

    @Test
    public void testSetSecondaryObjectives_Exceptions() {
        Properties.SECONDARY_OBJECTIVE = new Properties.SecondaryObjective[]{Properties.SecondaryObjective.EXCEPTIONS};
        TestCaseSecondaryObjective.setSecondaryObjectives();
        assertEquals(1, TestChromosome.getSecondaryObjectives().size());
        assertTrue(TestChromosome.getSecondaryObjectives().get(0) instanceof MinimizeExceptionsSecondaryObjective);
    }

    @Test
    public void testSetSecondaryObjectives_IBranch() {
        Properties.SECONDARY_OBJECTIVE = new Properties.SecondaryObjective[]{Properties.SecondaryObjective.IBRANCH};
        // This currently throws silently (caught), so nothing added.
        // After refactoring, it should just ignore it (also nothing added).
        TestCaseSecondaryObjective.setSecondaryObjectives();
        assertEquals(0, TestChromosome.getSecondaryObjectives().size());
    }

    @Test
    public void testSetSecondaryObjectives_Multiple() {
        Properties.SECONDARY_OBJECTIVE = new Properties.SecondaryObjective[]{
            Properties.SecondaryObjective.TOTAL_LENGTH,
            Properties.SecondaryObjective.EXCEPTIONS
        };
        TestCaseSecondaryObjective.setSecondaryObjectives();
        assertEquals(2, TestChromosome.getSecondaryObjectives().size());
    }
}
