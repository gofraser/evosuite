package org.evosuite.ga.metaheuristics.mulambda;

import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.DummyChromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.utils.Randomness;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

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
        assertEquals("Initial population size should be mu", mu, ea.getPopulation().size());

        ea.evolve();

        assertEquals("Population size should remain mu after evolve", mu, ea.getPopulation().size());
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

        assertEquals("Population size should remain mu", mu, ea.getPopulation().size());
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
        assertTrue("Best fitness should be >= 100, got " + bestFit, bestFit >= 100.0);
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
        assertEquals("Population size should remain 1", 1, ea.getPopulation().size());
    }
}
