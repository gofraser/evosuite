package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class MOSATest {

    private ChromosomeFactory<TestChromosome> factory;
    private MOSA mosa;

    @BeforeEach
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
