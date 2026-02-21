package org.evosuite.coverage.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
