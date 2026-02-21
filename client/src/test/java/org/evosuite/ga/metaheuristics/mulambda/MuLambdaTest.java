package org.evosuite.ga.metaheuristics.mulambda;

import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.DummyChromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MuLambdaTest {

    static class DummyFactory implements ChromosomeFactory<DummyChromosome> {
        @Override
        public DummyChromosome getChromosome() {
            return new DummyChromosome(Randomness.nextInt(100));
        }
    }

    static class MaximizeFitness extends FitnessFunction<DummyChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public double getFitness(DummyChromosome individual) {
            double fit = individual.get(0);
            updateIndividual(individual, fit);
            return fit;
        }

        @Override
        public boolean isMaximizationFunction() {
            return true;
        }
    }

    // Subclass to expose protected methods for testing
    static class TestableMuPlusLambdaEA extends MuPlusLambdaEA<DummyChromosome> {
        public TestableMuPlusLambdaEA(ChromosomeFactory<DummyChromosome> factory, int mu, int lambda) {
            super(factory, mu, lambda);
        }
        @Override
        public void calculateFitnessAndSortPopulation() {
            super.calculateFitnessAndSortPopulation();
        }
    }

    @Test
    public void testMuLambdaPopulationSizeAndReplacement() {
        Randomness.setSeed(42);
        int mu = 5;
        int lambda = 10;
        MuLambdaEA<DummyChromosome> ea = new MuLambdaEA<>(new DummyFactory(), mu, lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        assertEquals(mu, ea.getPopulation().size(), "Initial population size should be mu");

        ea.evolve();

        assertEquals(mu, ea.getPopulation().size(), "Population size should remain mu after evolve");
    }

    @Test
    public void testMuLambdaUnevenLambda() {
        Randomness.setSeed(42);
        int mu = 3;
        int lambda = 10;
        MuLambdaEA<DummyChromosome> ea = new MuLambdaEA<>(new DummyFactory(), mu, lambda);
        ea.addFitnessFunction(new MaximizeFitness());
        ea.initializePopulation();

        ea.evolve();

        assertEquals(mu, ea.getPopulation().size(), "Population size should remain mu");
    }

    @Test
    public void testMuPlusLambdaSelection() {
        Randomness.setSeed(42);
        int mu = 2;
        int lambda = 4;
        TestableMuPlusLambdaEA ea = new TestableMuPlusLambdaEA(new DummyFactory(), mu, lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        List<DummyChromosome> pop = ea.getPopulation();
        // Manually set high fitness for parents
        ((DummyChromosome)pop.get(0)).getGenes().set(0, 100);
        ((DummyChromosome)pop.get(1)).getGenes().set(0, 100);
        ea.calculateFitnessAndSortPopulation();

        ea.evolve();

        double bestFit = ea.getBestIndividual().getFitness();
        assertTrue(bestFit >= 100.0, "Best fitness should be >= 100, got " + bestFit);
    }

    @Test
    public void testOnePlusLambdaLambdaPopulation() {
        Randomness.setSeed(42);
        int lambda = 4;
        OnePlusLambdaLambdaGA<DummyChromosome> ea = new OnePlusLambdaLambdaGA<>(new DummyFactory(), lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        assertEquals(1, ea.getPopulation().size());

        ea.evolve();
        assertEquals(1, ea.getPopulation().size(), "Population size should remain 1");
    }

    @Test
    public void testMuLambdaAgeIncrements() {
        Randomness.setSeed(42);
        int mu = 5;
        int lambda = 10;
        MuLambdaEA<DummyChromosome> ea = new MuLambdaEA<>(new DummyFactory(), mu, lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        assertEquals(0, ea.getAge());

        ea.evolve();
        assertEquals(1, ea.getAge(), "Age should increment after evolve");
    }

    @Test
    public void testMuPlusLambdaAgeIncrements() {
        Randomness.setSeed(42);
        int mu = 5;
        int lambda = 10;
        MuPlusLambdaEA<DummyChromosome> ea = new MuPlusLambdaEA<>(new DummyFactory(), mu, lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        assertEquals(0, ea.getAge());

        ea.evolve();
        assertEquals(1, ea.getAge(), "Age should increment after evolve");
    }

    @Test
    public void testOnePlusLambdaLambdaAgeIncrements() {
        Randomness.setSeed(42);
        int lambda = 10;
        OnePlusLambdaLambdaGA<DummyChromosome> ea = new OnePlusLambdaLambdaGA<>(new DummyFactory(), lambda);
        ea.addFitnessFunction(new MaximizeFitness());

        ea.initializePopulation();
        assertEquals(0, ea.getAge());

        ea.evolve();
        assertEquals(1, ea.getAge(), "Age should increment after evolve");
    }
}
