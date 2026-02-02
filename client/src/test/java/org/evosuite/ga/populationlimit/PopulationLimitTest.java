package org.evosuite.ga.populationlimit;

import org.evosuite.Properties;
import org.evosuite.ga.DummyChromosome;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PopulationLimitTest {

    @Test
    public void testIndividualPopulationLimit() {
        int limit = 5;
        IndividualPopulationLimit<DummyChromosome> popLimit = new IndividualPopulationLimit<>(limit);

        List<DummyChromosome> population = new ArrayList<>();
        assertFalse(popLimit.isPopulationFull(population));

        for (int i = 0; i < limit; i++) {
            population.add(new DummyChromosome(1));
        }

        assertTrue(popLimit.isPopulationFull(population));
        assertEquals(limit, popLimit.getLimit());
    }

    @Test
    public void testIndividualPopulationLimitDefault() {
        // Set Properties.POPULATION to a known value
        int originalPop = Properties.POPULATION;
        try {
            Properties.POPULATION = 10;
            IndividualPopulationLimit<DummyChromosome> popLimit = new IndividualPopulationLimit<>();
            assertEquals(10, popLimit.getLimit());
        } finally {
            Properties.POPULATION = originalPop;
        }
    }

    @Test
    public void testSizePopulationLimit() {
        int limit = 10;
        SizePopulationLimit<DummyChromosome> popLimit = new SizePopulationLimit<>(limit);

        List<DummyChromosome> population = new ArrayList<>();
        assertFalse(popLimit.isPopulationFull(population));

        // Add chromosome of size 4
        population.add(new DummyChromosome(1, 2, 3, 4));
        assertFalse(popLimit.isPopulationFull(population));

        // Add chromosome of size 5 (total 9)
        population.add(new DummyChromosome(1, 2, 3, 4, 5));
        assertFalse(popLimit.isPopulationFull(population));

        // Add chromosome of size 2 (total 11)
        population.add(new DummyChromosome(1, 2));
        assertTrue(popLimit.isPopulationFull(population));

        assertEquals(limit, popLimit.getLimit());
    }

    @Test
    public void testSizePopulationLimitDefault() {
        int originalPop = Properties.POPULATION;
        try {
            Properties.POPULATION = 20;
            SizePopulationLimit<DummyChromosome> popLimit = new SizePopulationLimit<>();
            assertEquals(20, popLimit.getLimit());
        } finally {
            Properties.POPULATION = originalPop;
        }
    }

    @Test
    public void testCopyConstructors() {
        IndividualPopulationLimit<DummyChromosome> indLimit = new IndividualPopulationLimit<>(7);
        IndividualPopulationLimit<DummyChromosome> copyInd = new IndividualPopulationLimit<>(indLimit);
        assertEquals(7, copyInd.getLimit());

        SizePopulationLimit<DummyChromosome> sizeLimit = new SizePopulationLimit<>(15);
        SizePopulationLimit<DummyChromosome> copySize = new SizePopulationLimit<>(sizeLimit);
        assertEquals(15, copySize.getLimit());
    }
}
