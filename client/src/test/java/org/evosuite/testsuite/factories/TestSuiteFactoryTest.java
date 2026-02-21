package org.evosuite.testsuite.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TestSuiteFactoryTest {

    @Test
    public void testFixedSizeTestSuiteChromosomeFactoryUsesCorrectFactoryInternal() {
        // Arrange
        int size = 5;
        // Check default constructor
        FixedSizeTestSuiteChromosomeFactory factory = new FixedSizeTestSuiteChromosomeFactory(size);
        TestSuiteChromosome suite = factory.getChromosome();
        assertTrue(suite.getTestChromosomeFactory() instanceof RandomLengthTestFactory);
        assertEquals(size, suite.size());

        // Check new constructor
        ChromosomeFactory<TestChromosome> mockFactory = mock(ChromosomeFactory.class);
        when(mockFactory.getChromosome()).thenReturn(new TestChromosome());
        FixedSizeTestSuiteChromosomeFactory factory2 = new FixedSizeTestSuiteChromosomeFactory(mockFactory, size);
        TestSuiteChromosome suite2 = factory2.getChromosome();
        assertEquals(mockFactory, suite2.getTestChromosomeFactory());
        assertEquals(size, suite2.size());
    }

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
    public void testJUnitTestSuiteChromosomeFactoryDefaults() {
        // Arrange
        ChromosomeFactory<TestChromosome> mockFactory = mock(ChromosomeFactory.class);
        when(mockFactory.getChromosome()).thenReturn(new TestChromosome());

        JUnitTestSuiteChromosomeFactory factory = new JUnitTestSuiteChromosomeFactory(mockFactory);

        // Act
        TestSuiteChromosome suite = factory.getChromosome();

        // Assert
        // Now it should use the mock factory
        assertEquals(mockFactory, suite.getTestChromosomeFactory());
    }

    @Test
    public void testPropertiesRangeCheck() {
        // Save properties
        int originalMin = Properties.MIN_INITIAL_TESTS;
        int originalMax = Properties.MAX_INITIAL_TESTS;

        try {
            Properties.MIN_INITIAL_TESTS = 10;
            Properties.MAX_INITIAL_TESTS = 5;

            // Test FixedSize (doesn't use random count, so skipping)

            // Test TestSuiteChromosomeFactory
            TestSuiteChromosomeFactory factory = new TestSuiteChromosomeFactory();
            TestSuiteChromosome suite = factory.getChromosome();
            assertNotNull(suite);
            // It should work without exception, using min as max

            // Test JUnitTestSuiteChromosomeFactory
            JUnitTestSuiteChromosomeFactory jUnitFactory = new JUnitTestSuiteChromosomeFactory(new RandomLengthTestFactory());
            TestSuiteChromosome jSuite = jUnitFactory.getChromosome();
            assertNotNull(jSuite);

        } finally {
            Properties.MIN_INITIAL_TESTS = originalMin;
            Properties.MAX_INITIAL_TESTS = originalMax;
        }
    }
}
