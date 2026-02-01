package org.evosuite.coverage.exception;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExceptionCoverageFactoryTest {

    @Test
    public void testClearGoals() {
        ExceptionCoverageTestFitness goal = new ExceptionCoverageTestFitness(
                "Foo", "foo()", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);

        ExceptionCoverageFactory.getGoals().put(goal.getKey(), goal);
        assertFalse(ExceptionCoverageFactory.getGoals().isEmpty());

        ExceptionCoverageFactory.clear();
        assertTrue(ExceptionCoverageFactory.getGoals().isEmpty());
    }
}
