/**
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Ranks candidate tests by relevance to a set of uncovered coverage goals.
 *
 * <p>Two signals are combined into a single score:
 * <ol>
 *   <li><b>Near-miss fitness</b> — {@code Σ 1/(1 + fitness(test, goal))} across
 *       uncovered goals.  Tests that nearly cover target goals score highest.
 *       Uses cached {@code Chromosome.fitnessValues} (O(1) per lookup).</li>
 *   <li><b>Method overlap</b> — count of uncovered goals whose target method was
 *       executed by the test (via {@code ExecutionTrace.getCoveredMethods()}).
 *       Weighted by {@link Properties#LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT}.</li>
 * </ol>
 *
 * <p>This class is stateless and thread-safe.
 */
public class TestRelevanceRanker {

    private static final Logger logger = LoggerFactory.getLogger(TestRelevanceRanker.class);

    /**
     * Selects the {@code maxTests} most relevant tests from {@code candidates}
     * for the given set of uncovered goals.
     *
     * <p>When {@link Properties#LLM_RELEVANCE_BASED_TEST_SELECTION} is false,
     * or when execution results are unavailable, falls back to returning the
     * first {@code maxTests} candidates in input order.
     *
     * @param candidates    pool of candidate tests (may be the GA population or
     *                      accumulated suite)
     * @param uncoveredGoals the goals the LLM is being asked to cover
     * @param maxTests      maximum number of tests to return
     * @return up to {@code maxTests} tests, most relevant first
     */
    public static List<TestChromosome> rankByRelevance(
            List<TestChromosome> candidates,
            Collection<TestFitnessFunction> uncoveredGoals,
            int maxTests) {

        if (candidates == null || candidates.isEmpty() || maxTests <= 0) {
            return Collections.emptyList();
        }
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return firstN(candidates, maxTests);
        }
        if (!Properties.LLM_RELEVANCE_BASED_TEST_SELECTION) {
            return firstN(candidates, maxTests);
        }

        // Pre-compute the set of target methods for method-overlap scoring.
        // getCoveredMethods() returns "com.example.Foo.bar" (class.methodName, no descriptor).
        // goal.getTargetClass() + "." + stripDescriptor(goal.getTargetMethod()) matches this.
        Set<String> goalMethodKeys = new HashSet<>();
        for (TestFitnessFunction goal : uncoveredGoals) {
            String key = goalMethodKey(goal);
            if (key != null) {
                goalMethodKeys.add(key);
            }
        }

        double methodOverlapWeight = Properties.LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT;

        // Score each candidate
        List<ScoredTest> scored = new ArrayList<>(candidates.size());
        boolean anyExecutionResult = false;

        for (TestChromosome candidate : candidates) {
            double nearMissScore = computeNearMissScore(candidate, uncoveredGoals);
            int methodOverlap = computeMethodOverlap(candidate, goalMethodKeys);
            if (candidate.getLastExecutionResult() != null) {
                anyExecutionResult = true;
            }
            double totalScore = nearMissScore + methodOverlapWeight * methodOverlap;
            scored.add(new ScoredTest(candidate, totalScore));
        }

        // If no execution results were available, fall back to input order
        if (!anyExecutionResult) {
            logger.debug("No execution results available; falling back to first-N selection");
            return firstN(candidates, maxTests);
        }

        Collections.sort(scored);
        List<TestChromosome> result = new ArrayList<>(Math.min(maxTests, scored.size()));
        for (int i = 0; i < Math.min(maxTests, scored.size()); i++) {
            result.add(scored.get(i).test);
        }
        return result;
    }

    /**
     * Computes the near-miss fitness score: sum of 1/(1+fitness) for each uncovered goal.
     * Higher = test is closer to covering the goals.
     */
    static double computeNearMissScore(TestChromosome candidate,
                                       Collection<TestFitnessFunction> uncoveredGoals) {
        double score = 0.0;
        for (TestFitnessFunction goal : uncoveredGoals) {
            try {
                double fitness = candidate.getFitness(goal);
                score += 1.0 / (1.0 + fitness);
            } catch (Exception e) {
                // Fitness computation failed; skip this goal
            }
        }
        return score;
    }

    /**
     * Counts how many goal-target methods were actually executed by this test.
     */
    static int computeMethodOverlap(TestChromosome candidate,
                                    Set<String> goalMethodKeys) {
        if (goalMethodKeys.isEmpty()) {
            return 0;
        }
        ExecutionResult execResult = candidate.getLastExecutionResult();
        if (execResult == null || execResult.getTrace() == null) {
            return 0;
        }
        Set<String> coveredMethods = execResult.getTrace().getCoveredMethods();
        if (coveredMethods == null || coveredMethods.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String goalKey : goalMethodKeys) {
            if (coveredMethods.contains(goalKey)) {
                overlap++;
            }
        }
        return overlap;
    }

    /**
     * Builds a method key from a goal in the same format as
     * {@code ExecutionTrace.getCoveredMethods()}: {@code "className.methodName"}
     * (no descriptor).
     */
    static String goalMethodKey(TestFitnessFunction goal) {
        String className = goal.getTargetClass();
        String method = goal.getTargetMethod();
        if (className == null || className.isEmpty()
                || method == null || method.isEmpty()) {
            return null;
        }
        String bareMethod = stripDescriptor(method);
        return className + "." + bareMethod;
    }

    /**
     * Strips a JVM method descriptor from a method name.
     * {@code "foo(I)V"} → {@code "foo"}, {@code "bar"} → {@code "bar"}.
     */
    static String stripDescriptor(String methodName) {
        if (methodName == null) {
            return "";
        }
        int parenIdx = methodName.indexOf('(');
        return parenIdx > 0 ? methodName.substring(0, parenIdx) : methodName;
    }

    private static List<TestChromosome> firstN(List<TestChromosome> candidates, int n) {
        if (candidates.size() <= n) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, n));
    }

    /** Test scored with its relevance score, for sorting (highest score first). */
    private static class ScoredTest implements Comparable<ScoredTest> {
        final TestChromosome test;
        final double score;

        ScoredTest(TestChromosome test, double score) {
            this.test = test;
            this.score = score;
        }

        @Override
        public int compareTo(ScoredTest other) {
            return Double.compare(other.score, this.score); // descending
        }
    }
}
