/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.ga.diversity.FitnessSpaceSnapshotRecorder;
import org.evosuite.ga.diversity.JaccardSpeciesDistance;
import org.evosuite.ga.diversity.ObjectiveCoverageRecorder;
import org.evosuite.ga.diversity.PopulationShapeRecorder;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Adapts search-process sidecars to iterative LLM rounds.
 */
public final class IterativeLlmSidecarRecorder {

    private final List<TestFitnessFunction> goals;
    private final ObjectiveCoverageRecorder objectiveRecorder;
    private final FitnessSpaceSnapshotRecorder fitnessRecorder;
    private final PopulationShapeRecorder shapeRecorder;

    public IterativeLlmSidecarRecorder(List<TestFitnessFunction> goals) {
        this.goals = goals == null
                ? Collections.<TestFitnessFunction>emptyList()
                : new ArrayList<>(goals);
        objectiveRecorder = Properties.OBJECTIVE_COVERAGE_TIMELINE_ENABLED
                ? new ObjectiveCoverageRecorder() : null;
        fitnessRecorder = Properties.FITNESS_SPACE_SNAPSHOT_ENABLED
                ? new FitnessSpaceSnapshotRecorder() : null;
        shapeRecorder = Properties.POPULATION_SHAPE_SNAPSHOT_ENABLED
                ? new PopulationShapeRecorder() : null;
        initializeGoalIndex();
    }

    public void record(int round, long elapsedMs, TestSuiteChromosome suite) {
        if (objectiveRecorder != null
                && sampled(round,
                Properties.OBJECTIVE_COVERAGE_TIMELINE_SAMPLE_INTERVAL)) {
            objectiveRecorder.record(round, bestFitnessVector(suite));
        }
        if (fitnessRecorder != null
                && sampled(round, Properties.FITNESS_SPACE_SNAPSHOT_INTERVAL)) {
            recordFitnessSpace(round, suite);
        }
        if (shapeRecorder != null
                && sampled(round, Properties.POPULATION_SHAPE_SNAPSHOT_INTERVAL)) {
            recordPopulationShape(round, elapsedMs, suite);
        }
    }

    public void flush() {
        if (objectiveRecorder != null) {
            objectiveRecorder.flush();
        }
        if (fitnessRecorder != null) {
            fitnessRecorder.flush();
        }
        if (shapeRecorder != null) {
            shapeRecorder.flush();
        }
    }

    private void initializeGoalIndex() {
        if (objectiveRecorder == null) {
            return;
        }
        String[] classes = new String[goals.size()];
        String[] methods = new String[goals.size()];
        String[] descriptions = new String[goals.size()];
        for (int i = 0; i < goals.size(); i++) {
            TestFitnessFunction goal = goals.get(i);
            classes[i] = goal.getTargetClass();
            methods[i] = goal.getTargetMethod();
            descriptions[i] = goal.toString();
        }
        objectiveRecorder.setGoalIndex(classes, methods, descriptions);
    }

    private double[] bestFitnessVector(TestSuiteChromosome suite) {
        double[] best = new double[goals.size()];
        Arrays.fill(best, Double.NaN);
        Set<TestFitnessFunction> covered = suite.getCoveredGoals();
        for (int i = 0; i < goals.size(); i++) {
            TestFitnessFunction goal = goals.get(i);
            if (covered.contains(goal)) {
                best[i] = 0.0;
                continue;
            }
            for (TestChromosome test : suite.getTestChromosomes()) {
                Double value = test.getFitnessValues().get(goal);
                if (value != null && (Double.isNaN(best[i]) || value < best[i])) {
                    best[i] = value;
                }
            }
        }
        return best;
    }

    private void recordFitnessSpace(int round, TestSuiteChromosome suite) {
        List<double[]> vectors = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        int limit = Math.min(suite.size(),
                Properties.FITNESS_SPACE_SNAPSHOT_MAX_INDIVIDUALS);
        for (int i = 0; i < limit; i++) {
            TestChromosome test = suite.getTestChromosomes().get(i);
            double[] vector = new double[goals.size()];
            Arrays.fill(vector, 1.0);
            for (int g = 0; g < goals.size(); g++) {
                Double value = test.getFitnessValues().get(goals.get(g));
                if (value != null) {
                    vector[g] = value;
                }
            }
            vectors.add(vector);
            ranks.add(-1);
        }
        fitnessRecorder.record(round, vectors, ranks);
    }

    private void recordPopulationShape(int round, long elapsedMs,
                                       TestSuiteChromosome suite) {
        int limit = Math.min(suite.size(),
                Properties.POPULATION_SHAPE_MAX_INDIVIDUALS);
        for (int i = 0; i < limit; i++) {
            TestChromosome test = suite.getTestChromosomes().get(i);
            int[] branches = JaccardSpeciesDistance.getCoveredBranches(test)
                    .stream().mapToInt(Integer::intValue).sorted().toArray();
            String[] methods = sortedSanitized(
                    JaccardSpeciesDistance.getMethodSignatures(test));
            String[] features = sortedSanitized(
                    JaccardSpeciesDistance.getStructuralFeatures(test));
            int coveredGoals = JaccardSpeciesDistance.getCoveredGoals(test).size();
            double sumFitness = test.getFitnessValues().values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            shapeRecorder.record(round, elapsedMs, i, -1, -1,
                    "LLM_STRATEGY", coveredGoals, sumFitness,
                    branches, methods, features);
        }
    }

    private static boolean sampled(int round, int interval) {
        return round == 1 || round % Math.max(1, interval) == 0;
    }

    private static String[] sortedSanitized(Set<String> values) {
        return values.stream()
                .map(v -> v.replace(',', '_').replace('|', '_')
                        .replace(';', '_'))
                .sorted()
                .toArray(String[]::new);
    }
}
