package org.evosuite.ga;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TournamentChromosomeFactoryTest {

    @Test
    public void testMinimization() {
        FitnessFunction<DummyChromosome> ff = new FitnessFunction<DummyChromosome>() {
            private static final long serialVersionUID = 1L;
            @Override
            public double getFitness(DummyChromosome individual) {
                return individual.getFitness();
            }
            @Override
            public boolean isMaximizationFunction() {
                return false;
            }
        };

        ChromosomeFactory<DummyChromosome> factory = mock(ChromosomeFactory.class);

        DummyChromosome c1 = new DummyChromosome();
        c1.setFitness(ff, 10.0);

        DummyChromosome c2 = new DummyChromosome();
        c2.setFitness(ff, 5.0); // Better for minimization

        DummyChromosome c3 = new DummyChromosome();
        c3.setFitness(ff, 15.0);

        // Factory returns c1, then c2, then c3
        when(factory.getChromosome()).thenReturn(c1, c2, c3, c1, c2, c3, c1, c2, c3, c1);

        TournamentChromosomeFactory<DummyChromosome> tournamentFactory =
            new TournamentChromosomeFactory<>(ff, factory);

        DummyChromosome best = tournamentFactory.getChromosome();

        // Should be c2 (5.0) because it is smallest
        assertEquals(5.0, best.getFitness(), 0.001);
    }

    @Test
    public void testMaximization() {
        FitnessFunction<DummyChromosome> ff = new FitnessFunction<DummyChromosome>() {
            private static final long serialVersionUID = 1L;
            @Override
            public double getFitness(DummyChromosome individual) {
                return individual.getFitness();
            }
            @Override
            public boolean isMaximizationFunction() {
                return true;
            }
        };

        ChromosomeFactory<DummyChromosome> factory = mock(ChromosomeFactory.class);

        DummyChromosome c1 = new DummyChromosome();
        c1.setFitness(ff, 10.0);

        DummyChromosome c2 = new DummyChromosome();
        c2.setFitness(ff, 20.0); // Better for maximization

        DummyChromosome c3 = new DummyChromosome();
        c3.setFitness(ff, 5.0);

        // Factory returns c1, then c2, then c3
        when(factory.getChromosome()).thenReturn(c1, c2, c3, c1, c2, c3, c1, c2, c3, c1);

        TournamentChromosomeFactory<DummyChromosome> tournamentFactory =
            new TournamentChromosomeFactory<>(ff, factory);

        DummyChromosome best = tournamentFactory.getChromosome();

        assertEquals(20.0, best.getFitness(), 0.001, "Should pick highest fitness for maximization");
    }
}
