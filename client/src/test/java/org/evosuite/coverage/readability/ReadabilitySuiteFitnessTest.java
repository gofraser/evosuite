package org.evosuite.coverage.readability;

import org.evosuite.assertion.Assertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReadabilitySuiteFitnessTest {

    @Test
    public void testGetFitness() {
        ReadabilitySuiteFitness fitness = new ReadabilitySuiteFitness();
        TestSuiteChromosome suite = new TestSuiteChromosome();

        TestChromosome test1 = new TestChromosome();
        TestCase mockTestCase1 = mock(TestCase.class);
        test1.setTestCase(mockTestCase1);
        when(mockTestCase1.size()).thenReturn(10);
        when(mockTestCase1.getAssertions()).thenReturn(Collections.emptyList());

        TestChromosome test2 = new TestChromosome();
        TestCase mockTestCase2 = mock(TestCase.class);
        test2.setTestCase(mockTestCase2);
        when(mockTestCase2.size()).thenReturn(5);
        Assertion mockAssertion = mock(Assertion.class);
        when(mockTestCase2.getAssertions()).thenReturn(Arrays.asList(mockAssertion, mockAssertion)); // 2 assertions

        suite.addTest(test1);
        suite.addTest(test2);

        // Score 1: 10 + 0 = 10
        // Score 2: 5 + 2 = 7
        // Average: (10 + 7) / 2 = 8.5

        double result = fitness.getFitness(suite);
        assertEquals(8.5, result, 0.001);
    }
}
