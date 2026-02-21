package org.evosuite.basic;

import com.examples.with.different.packagename.coverage.GenericScannerLike;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GenericScannerLikeSystemTest extends SystemTestBase {

    @Test
    public void testGenericScannerLikeShouldReachCoverage() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = GenericScannerLike.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.RANDOM_SEED = 1771247411472L;
        Properties.SEARCH_BUDGET = 2000;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);

        Assertions.assertNotNull(result);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size();
        System.out.println("GENERIC_SCANNER_LIKE_TEST: goals=" + goals + " coverage=" + best.getCoverage());

        Assertions.assertTrue(goals > 0, "Expected at least one branch goal");
        Assertions.assertTrue(best.getCoverage() > 0.0d, "Expected non-zero branch coverage for reproduction target");
    }
}
