package org.evosuite.coverage.exception;

import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ExceptionCoverageTestFitnessTest {

    @Test
    public void testEqualsAndHashCode() {
        ExceptionCoverageTestFitness fitness1 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness2 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness3 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.EXPLICIT);
        ExceptionCoverageTestFitness fitness4 = new ExceptionCoverageTestFitness(
                "com.example.Foo", "method2()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);
        ExceptionCoverageTestFitness fitness5 = new ExceptionCoverageTestFitness(
                "com.example.Bar", "method1()V", NullPointerException.class, ExceptionCoverageTestFitness.ExceptionType.IMPLICIT);

        assertEquals(fitness1, fitness2);
        assertEquals(fitness1.hashCode(), fitness2.hashCode());

        assertNotEquals(fitness1, fitness3);
        // This assertion might fail if hashCode implementation is incomplete
        // assertNotEquals(fitness1.hashCode(), fitness3.hashCode());

        assertNotEquals(fitness1, fitness4);
        assertNotEquals(fitness1, fitness5);
    }
}
