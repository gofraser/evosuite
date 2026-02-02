package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MOSATest {

    private ChromosomeFactory<TestChromosome> factory;
    private MOSA mosa;

    @Before
    public void setUp() {
        factory = mock(ChromosomeFactory.class);
        mosa = new MOSA(factory);
    }

    @Test
    public void testInitialization() {
        assertNotNull(mosa);
        assertTrue(mosa.getFitnessFunctions().isEmpty());
    }
}
