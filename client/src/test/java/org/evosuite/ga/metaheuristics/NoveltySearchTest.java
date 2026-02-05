package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.NoveltyFunction;
import org.evosuite.ga.populationlimit.IndividualPopulationLimit;
import org.evosuite.testcase.TestChromosome;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.evosuite.ga.operators.selection.SelectionFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class NoveltySearchTest {

    @Before
    public void setup() {
        Properties.CROSSOVER_RATE = 1.0;
        Properties.POPULATION = 10;
        Properties.MUTATION_RATE = 0.5;
        Properties.P_TEST_DELETE = 0.0;
        Properties.P_TEST_CHANGE = 0.0;
        Properties.P_TEST_INSERT = 0.0;
    }

    @Test
    public void testPopulationOverflow() {
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        NoveltySearch search = new NoveltySearch(factory);
        search.setNoveltyFunction(new DummyNoveltyFunction());

        search.setPopulationLimit(new IndividualPopulationLimit<>(11));
        Properties.POPULATION = 11;

        List<TestChromosome> population = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            population.add(new TestChromosome());
        }
        setPopulation(search, population);
        search.currentIteration = 0;

        SelectionFunction<TestChromosome> selection = Mockito.mock(SelectionFunction.class);
        Mockito.when(selection.select(Mockito.anyList())).thenReturn(population.get(0));
        search.setSelectionFunction(selection);

        search.evolve();

        List<TestChromosome> newPop = search.getPopulation();
        assertEquals("Population size should match limit", 11, newPop.size());
    }

    @Test
    public void testInitializationOfNoveltyFunction() {
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        NoveltySearch search = new NoveltySearch(factory);

        // Use dummy to avoid NaN from BranchNoveltyFunction when no branches exist
        search.setNoveltyFunction(new DummyNoveltyFunction());

        // This should not throw NPE anymore
        search.initializePopulation();

        // Verify population is initialized
        assertEquals(Properties.POPULATION, search.getPopulation().size());
    }

    private void setPopulation(GeneticAlgorithm<TestChromosome> ga, List<TestChromosome> population) {
        try {
            java.lang.reflect.Field field = GeneticAlgorithm.class.getDeclaredField("population");
            field.setAccessible(true);
            field.set(ga, population);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class TestChromosomeFactory implements ChromosomeFactory<TestChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public TestChromosome getChromosome() {
            return new TestChromosome();
        }
    }

    static class DummyNoveltyFunction extends NoveltyFunction<TestChromosome> {
        @Override
        public double getDistance(TestChromosome individual1, TestChromosome individual2) {
            return 0.5; // Return valid distance
        }
    }
}
