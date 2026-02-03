package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.ga.populationlimit.IndividualPopulationLimit;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.evosuite.ga.operators.selection.SelectionFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestGAVariants {

    @Before
    public void setup() {
        Properties.CROSSOVER_RATE = 1.0;
        Properties.POPULATION = 10;
        Properties.MUTATION_RATE = 0.5; // Ensure mutation happens to avoid infinite loops if any
    }

    @Test
    public void testStandardGAPopulationOverflow() {
        // Setup
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        StandardGA<TestChromosome> ga = new StandardGA<>(factory);
        ga.setPopulationLimit(new IndividualPopulationLimit<>(10));
        Properties.POPULATION = 10;

        // Initialize population with 9 individuals (so it needs 1 more)
        List<TestChromosome> population = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            population.add(new TestChromosome());
        }
        setPopulation(ga, population);
        ga.currentIteration = 0;

        // Mock selection to return existing chromosomes
        SelectionFunction<TestChromosome> selection = Mockito.mock(SelectionFunction.class);
        Mockito.when(selection.select(Mockito.anyList())).thenReturn(population.get(0));
        ga.setSelectionFunction(selection);

        // Run evolve
        ga.evolve();

        // Check population size. StandardGA currently might result in 11 (9 + 2).
        // Correct behavior should be 10.
        // Or if it replaces the whole population (StandardGA does replacement),
        // it builds newGeneration.
        // StandardGA:
        // List<T> newGeneration = new ArrayList<>(elitism());
        // while (!isNextPopulationFull(newGeneration)) { ... }
        // If elitism is 0. newGeneration is empty.
        // It adds 2 at a time.
        // If limit is 10.
        // 0 -> 2 -> 4 -> 6 -> 8.
        // At 8: isNextPopulationFull(8) is false.
        // Adds 2 -> 10.
        // At 10: isNextPopulationFull(10) is true. Stops.
        // So 10 is fine.

        // Use limit 11.
        // 0 -> ... -> 8 -> 10.
        // At 10: isNextPopulationFull(10) is false (10 < 11).
        // Adds 2 -> 12.
        // 12 > 11. Overflow!

        ga.setPopulationLimit(new IndividualPopulationLimit<>(11));
        Properties.POPULATION = 11;

        // Reset population
        setPopulation(ga, new ArrayList<>()); // StandardGA clears population during evolve anyway, relying on elitism
        // Wait, StandardGA logic:
        // List<T> newGeneration = new ArrayList<>(elitism());
        // StandardGA replaces the population completely.

        ga.evolve();

        List<TestChromosome> newPop = ga.getPopulation();
        // If bug exists, size will be 12. If fixed, 11.
        assertEquals("Population size should match limit", 11, newPop.size());
    }

    @Test
    public void testSteadyStateGAPopulationGrowthWithIdenticalParents() {
        // Setup
        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        SteadyStateGA<TestChromosome> ga = new SteadyStateGA<>(factory);
        ga.setPopulationLimit(new IndividualPopulationLimit<>(10));
        Properties.POPULATION = 10;
        Properties.CROSSOVER_RATE = 1.0;
        Properties.PARENT_CHECK = false; // Disable parent check to ensure replacement logic runs

        // Initialize population with 10 UNIQUE individuals
        List<TestChromosome> population = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            population.add(new TestChromosome());
        }
        setPopulation(ga, population);
        ga.currentIteration = 0;

        // Pick a victim to be selected twice
        TestChromosome victim = population.get(0);

        // Mock selection to return the SAME individual twice
        SelectionFunction<TestChromosome> selection = Mockito.mock(SelectionFunction.class);
        Mockito.when(selection.select(Mockito.anyList())).thenReturn(victim);
        ga.setSelectionFunction(selection);

        // Run evolve
        ga.evolve();

        // Check population size
        // If bug exists: remove(victim) removes 1. remove(victim) returns false.
        // add(offspring1), add(offspring2).
        // Net: -1 + 2 = +1. Size 11.
        assertEquals("Population size should not grow", 10, ga.getPopulation().size());
    }

    @Test
    public void testMonotonicGAPopulationOverflow() {
        // MonotonicGA also builds newGeneration but it keeps parents if offspring rejected.
        // It has similar loop structure.

        ChromosomeFactory<TestChromosome> factory = new TestChromosomeFactory();
        MonotonicGA<TestChromosome> ga = new MonotonicGA<>(factory);
        ga.setPopulationLimit(new IndividualPopulationLimit<>(11));
        Properties.POPULATION = 11;

        // MonotonicGA uses elitism too.

        // Mock selection
        List<TestChromosome> population = new ArrayList<>();
        for(int i=0; i<11; i++) population.add(new TestChromosome());
        setPopulation(ga, population);

        SelectionFunction<TestChromosome> selection = Mockito.mock(SelectionFunction.class);
        Mockito.when(selection.select(Mockito.anyList())).thenReturn(new TestChromosome());
        ga.setSelectionFunction(selection);

        ga.evolve();

        assertEquals("Population size should match limit", 11, ga.getPopulation().size());
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

    static class TestChromosome extends Chromosome<TestChromosome> {
        private static final long serialVersionUID = 1L;

        @Override
        public TestChromosome clone() { return new TestChromosome(); }

        // Default equality is identity, which is what we want for some tests,
        // but for SteadyStateGA test we explicitly used same reference.
        // For distinct objects, identity is fine.

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }
        @Override
        public int hashCode() { return System.identityHashCode(this); }
        @Override
        public int compareTo(TestChromosome o) { return 0; }
        @Override
        public int compareSecondaryObjective(TestChromosome o) { return 0; }
        @Override
        public void mutate() {}
        @Override
        public void crossOver(TestChromosome other, int position1, int position2) throws ConstructionFailedException {}
        @Override
        public boolean localSearch(LocalSearchObjective<TestChromosome> objective) { return false; }
        @Override
        public int size() { return 1; } // Non-zero size
        @Override
        public TestChromosome self() {
            return this;
        }
    }

    static class TestChromosomeFactory implements ChromosomeFactory<TestChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public TestChromosome getChromosome() {
            return new TestChromosome();
        }
    }
}
