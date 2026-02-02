package org.evosuite.ga.operators.ranking;

import org.evosuite.ga.DummyChromosome;
import org.evosuite.ga.FitnessFunction;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FastNonDominatedSortingTest {

    private static class DummyFitnessFunction extends FitnessFunction<DummyChromosome> {
        private static final long serialVersionUID = 1L;
        private final int index;

        public DummyFitnessFunction(int index) {
            this.index = index;
        }

        @Override
        public double getFitness(DummyChromosome individual) {
            if (individual.size() > index) {
                return individual.get(index);
            }
            return Double.MAX_VALUE;
        }

        @Override
        public boolean isMaximizationFunction() {
            return false;
        }
    }

    @Test
    public void testSorting() {
        FastNonDominatedSorting<DummyChromosome> sorting = new FastNonDominatedSorting<>();

        // A: (1, 1)
        // B: (2, 2)
        // C: (1, 3)
        // D: (3, 1)
        // E: (0, 5)

        DummyChromosome A = new DummyChromosome(1, 1);
        DummyChromosome B = new DummyChromosome(2, 2);
        DummyChromosome C = new DummyChromosome(1, 3);
        DummyChromosome D = new DummyChromosome(3, 1);
        DummyChromosome E = new DummyChromosome(0, 5);

        List<DummyChromosome> population = new ArrayList<>(Arrays.asList(A, B, C, D, E));

        // Use LinkedHashSet to ensure deterministic iteration order, though not strictly required for correctness of fronts
        Set<FitnessFunction<DummyChromosome>> goals = new LinkedHashSet<>();
        goals.add(new DummyFitnessFunction(0));
        goals.add(new DummyFitnessFunction(1));

        sorting.computeRankingAssignment(population, goals);

        assertEquals("A should be rank 1", 1, A.getRank());
        assertEquals("E should be rank 1", 1, E.getRank());

        assertEquals("B should be rank 2", 2, B.getRank());
        assertEquals("C should be rank 2", 2, C.getRank());
        assertEquals("D should be rank 2", 2, D.getRank());

        assertEquals("Number of subfronts", 2, sorting.getNumberOfSubfronts());
        List<DummyChromosome> front0 = sorting.getSubfront(0);
        assertTrue(front0.contains(A));
        assertTrue(front0.contains(E));
        assertEquals(2, front0.size());

        List<DummyChromosome> front1 = sorting.getSubfront(1);
        assertTrue(front1.contains(B));
        assertTrue(front1.contains(C));
        assertTrue(front1.contains(D));
        assertEquals(3, front1.size());
    }
}
