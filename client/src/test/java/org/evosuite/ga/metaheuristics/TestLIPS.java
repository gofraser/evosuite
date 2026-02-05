package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestLIPS {

    @Before
    public void setup() {
        Properties.CROSSOVER_RATE = 1.0;
        Properties.POPULATION = 10;
        Properties.MUTATION_RATE = 0.5;
        Properties.SEARCH_BUDGET = 60;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.GLOBAL_TIMEOUT = 120;
    }

    @Test
    public void testGetBestIndividualReturnsValue() {
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        LIPS lips = new LIPS(factory);
        try {
            TestChromosome best = lips.getBestIndividual();
            assertNotNull(best);
        } catch (UnsupportedOperationException e) {
            fail("Should not throw UnsupportedOperationException");
        }
    }

    @Test
    public void testGetBestIndividualsReturnsArchive() {
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        LIPS lips = new LIPS(factory);
        List<TestChromosome> bests = lips.getBestIndividuals();
        assertNotNull(bests);
        // Archive empty
        assertTrue(bests.isEmpty());
    }

    @Test
    public void testEvolveWithSmallPopulation() {
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        LIPS lips = new LIPS(factory);
        Properties.POPULATION = 1; // Small population

        // Setup population
        List<TestChromosome> population = new ArrayList<>();
        population.add(new TestChromosome());
        setPopulation(lips, population);

        // Mock current target as evolve sorts by it
        TestFitnessFunction target = Mockito.mock(TestFitnessFunction.class);
        setField(lips, "currentTarget", target);

        try {
            lips.evolve();
            // Should not crash
        } catch (Exception e) {
             e.printStackTrace();
             fail("Unexpected exception: " + e);
        }
    }

    private void setPopulation(GeneticAlgorithm<TestChromosome> ga, List<TestChromosome> population) {
        setField(ga, "population", population);
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field;
            try {
                field = obj.getClass().getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Try superclass
                field = obj.getClass().getSuperclass().getDeclaredField(fieldName);
            }
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Use a mockable/simple TestChromosome
    static class TestChromosomeFactory implements ChromosomeFactory<TestChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public TestChromosome getChromosome() {
            return new TestChromosome();
        }
    }
}
