package org.evosuite.ga.comparators;

import org.evosuite.testcase.TestChromosome;
import org.junit.Test;
import static org.junit.Assert.*;

public class RankAndCrowdingDistanceComparatorTest {

    @Test
    public void testMaximizeTruePreferLowerRank() {
        TestChromosome c1 = new TestChromosome();
        c1.setRank(0); // Better
        c1.setDistance(0.0);

        TestChromosome c2 = new TestChromosome();
        c2.setRank(1); // Worse
        c2.setDistance(0.0);

        // maximize=true simulates usage in TournamentSelectionRankAndCrowdingDistanceComparator
        RankAndCrowdingDistanceComparator<TestChromosome> comparator = new RankAndCrowdingDistanceComparator<>(true);

        // We expect c1 to be better than c2.
        // comparator.compare(c1, c2) should be < 0.
        int result = comparator.compare(c1, c2);

        // This assertion fails with current implementation
        assertTrue("Rank 0 should be preferred over Rank 1 even when maximizing fitness. Result was: " + result, result < 0);
    }

    @Test
    public void testMaximizeFalsePreferLowerRank() {
        TestChromosome c1 = new TestChromosome();
        c1.setRank(0); // Better
        c1.setDistance(0.0);

        TestChromosome c2 = new TestChromosome();
        c2.setRank(1); // Worse
        c2.setDistance(0.0);

        RankAndCrowdingDistanceComparator<TestChromosome> comparator = new RankAndCrowdingDistanceComparator<>(false);

        int result = comparator.compare(c1, c2);
        assertTrue("Rank 0 should be preferred over Rank 1", result < 0);
    }
}
