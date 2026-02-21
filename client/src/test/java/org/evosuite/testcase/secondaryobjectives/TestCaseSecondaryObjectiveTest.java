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
