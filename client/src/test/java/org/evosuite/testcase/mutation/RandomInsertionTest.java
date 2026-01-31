package org.evosuite.testcase.mutation;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.junit.Test;
import static org.junit.Assert.*;

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
