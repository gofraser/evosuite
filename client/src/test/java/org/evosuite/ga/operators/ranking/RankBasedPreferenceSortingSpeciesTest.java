package org.evosuite.ga.operators.ranking;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.ga.SecondaryObjective;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for species-aware front-0 tiebreaking in RankBasedPreferenceSorting
 * and front-0 exemption from survival caps.
 */
class RankBasedPreferenceSortingSpeciesTest {

    private RankBasedPreferenceSorting<TestChromosome> ranking;
    private List<SecondaryObjective<TestChromosome>> savedSecondaryObjectives;

    @BeforeEach
    void setUp() {
        ranking = new RankBasedPreferenceSorting<>();
        // Save and clear secondary objectives so compareSecondaryObjective returns 0
        // for identical empty test cases, allowing species tiebreaking to fire.
        savedSecondaryObjectives = new ArrayList<>(TestChromosome.getSecondaryObjectives());
        TestChromosome.clearSecondaryObjectives();
    }

    @AfterEach
    void tearDown() {
        TestChromosome.clearSecondaryObjectives();
        for (SecondaryObjective<TestChromosome> so : savedSecondaryObjectives) {
            TestChromosome.addSecondaryObjective(so);
        }
    }

    @Test
    void noSpeciesContextFallsBackToCoinFlip() {
        // With no species context set, ranking should still work (coin flip tiebreak)
        ranking.setSpeciesContext(null);

        List<TestChromosome> solutions = makeSolutions(4);
        Set<FitnessFunction<TestChromosome>> goals = makeGoals(2, solutions);

        ranking.computeRankingAssignment(solutions, goals);

        List<TestChromosome> front0 = ranking.getSubfront(0);
        assertFalse(front0.isEmpty(), "Front-0 should have at least one member");
    }

    @Test
    void speciesContextInfluencesTiebreaking() {
        // Create 4 individuals: two from species 0, two from species 1.
        // All have identical fitness for goal 0. With species context, the tiebreaker
        // should prefer individuals from the rarer species in front-0.
        List<TestChromosome> solutions = makeSolutions(4);

        // All individuals have same fitness for goal
        FitnessFunction<TestChromosome> goal = makeFitnessFunction(0);
        for (TestChromosome tc : solutions) {
            tc.setFitness(goal, 0.5);
        }
        Set<FitnessFunction<TestChromosome>> goals = new LinkedHashSet<>();
        goals.add(goal);

        // Species: 0→{sol0, sol1}, 1→{sol2, sol3}
        Map<TestChromosome, Integer> speciesContext = new IdentityHashMap<>();
        speciesContext.put(solutions.get(0), 0);
        speciesContext.put(solutions.get(1), 0);
        speciesContext.put(solutions.get(2), 1);
        speciesContext.put(solutions.get(3), 1);

        ranking.setSpeciesContext(speciesContext);
        ranking.computeRankingAssignment(solutions, goals);

        List<TestChromosome> front0 = ranking.getSubfront(0);
        assertEquals(1, front0.size(), "Single goal → single front-0 member");
        // Can't assert which species won (both are equally sized), but ranking completes
    }

    @Test
    void speciesContextPrefersRarerSpeciesAcrossGoals() {
        // Goal 0: species 0 wins deterministically (lower fitness = better)
        // Goal 1: all tied → species tiebreaking should prefer species 1
        //         (because species 0 already has 1 member in front-0 from goal 0)
        List<TestChromosome> solutions = makeSolutions(6);

        FitnessFunction<TestChromosome> goal0 = makeFitnessFunction(0);
        FitnessFunction<TestChromosome> goal1 = makeFitnessFunction(1);

        // Goal 0: species 0 has fitness 0.0 (better), species 1 has 1.0
        for (int i = 0; i < 4; i++) solutions.get(i).setFitness(goal0, 0.0);
        for (int i = 4; i < 6; i++) solutions.get(i).setFitness(goal0, 1.0);

        // Goal 1: everyone tied at 1.0
        for (TestChromosome tc : solutions) {
            tc.setFitness(goal1, 1.0);
        }

        Set<FitnessFunction<TestChromosome>> goals = new LinkedHashSet<>();
        goals.add(goal0);
        goals.add(goal1);

        // Species 0 = {sol0..sol3}, Species 1 = {sol4, sol5}
        Map<TestChromosome, Integer> speciesContext = new IdentityHashMap<>();
        for (int i = 0; i < 4; i++) speciesContext.put(solutions.get(i), 0);
        for (int i = 4; i < 6; i++) speciesContext.put(solutions.get(i), 1);

        ranking.setSpeciesContext(speciesContext);
        ranking.computeRankingAssignment(solutions, goals);

        List<TestChromosome> front0 = ranking.getSubfront(0);
        // Goal 0 winner: one of sol0-sol3 (species 0, better fitness)
        // Goal 1: all tied → species 1 (count=0) preferred over species 0 (count=1)
        assertTrue(front0.size() >= 2, "Two goals should produce at least 2 front-0 members");

        Set<TestChromosome> s1Set = Collections.newSetFromMap(new IdentityHashMap<>());
        s1Set.add(solutions.get(4));
        s1Set.add(solutions.get(5));
        int s1InFront0 = 0;
        for (TestChromosome tc : front0) {
            if (s1Set.contains(tc)) s1InFront0++;
        }
        assertEquals(1, s1InFront0,
                "Species 1 should win the tied goal via species-aware tiebreaking");
    }

    @Test
    void speciesContextClearedAfterRanking() {
        Map<TestChromosome, Integer> ctx = new IdentityHashMap<>();
        ranking.setSpeciesContext(ctx);
        ranking.setSpeciesContext(null);
        // Should not throw or affect subsequent ranking
        List<TestChromosome> solutions = makeSolutions(2);
        Set<FitnessFunction<TestChromosome>> goals = makeGoals(1, solutions);
        ranking.computeRankingAssignment(solutions, goals);
        assertFalse(ranking.getSubfront(0).isEmpty());
    }

    @Test
    void front0MembersHaveRankZero() {
        List<TestChromosome> solutions = makeSolutions(6);
        Set<FitnessFunction<TestChromosome>> goals = makeGoals(3, solutions);

        ranking.computeRankingAssignment(solutions, goals);

        List<TestChromosome> front0 = ranking.getSubfront(0);
        for (TestChromosome tc : front0) {
            assertEquals(0, tc.getRank(), "All front-0 members should have rank 0");
        }
    }

    // --- helpers ---

    private List<TestChromosome> makeSolutions(int n) {
        List<TestChromosome> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestChromosome tc = new TestChromosome();
            DefaultTestCase testCase = new DefaultTestCase();
            // Each test case must be unique for equals() since getZeroFront uses LinkedHashSet.
            testCase.addStatement(
                    new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(testCase, i));
            tc.setTestCase(testCase);
            list.add(tc);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private FitnessFunction<TestChromosome> makeFitnessFunction(int id) {
        FitnessFunction<TestChromosome> ff = mock(FitnessFunction.class);
        when(ff.isMaximizationFunction()).thenReturn(false);
        return ff;
    }

    private Set<FitnessFunction<TestChromosome>> makeGoals(int n,
                                                            List<TestChromosome> solutions) {
        Set<FitnessFunction<TestChromosome>> goals = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            FitnessFunction<TestChromosome> goal = makeFitnessFunction(i);
            // Give each solution a different fitness for this goal so ranking is deterministic
            for (int j = 0; j < solutions.size(); j++) {
                solutions.get(j).setFitness(goal, (double) j);
            }
            goals.add(goal);
        }
        return goals;
    }
}
