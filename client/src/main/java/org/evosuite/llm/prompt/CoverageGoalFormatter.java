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
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Formats uncovered goals into readable lines for prompts.
 *
 * <p>When {@code LLM_GOAL_FORMAT} is {@code LLM_FRIENDLY}, goals are rendered
 * using {@link GoalDescriptionMapper}, grouped by class-qualified target method
 * when available, optionally annotated with fitness distance, and capped with
 * overflow indicators.
 * When {@code RAW}, the original {@code goal.toString()} output is used.
 */
public class CoverageGoalFormatter {

    /** Default maximum goals to display per target-method group before overflow. */
    static final int DEFAULT_MAX_GOALS_PER_METHOD = 20;

    private static final double ALMOST_COVERED_THRESHOLD = 0.1;

    private final GoalDescriptionMapper mapper;
    private final int maxGoalsPerMethod;

    /**
     * Creates a formatter that respects the current {@code LLM_GOAL_FORMAT} property.
     */
    public CoverageGoalFormatter() {
        this(new GoalDescriptionMapper(), DEFAULT_MAX_GOALS_PER_METHOD);
    }

    /** Creates a formatter with an explicit mapper and overflow cap. */
    public CoverageGoalFormatter(GoalDescriptionMapper mapper, int maxGoalsPerMethod) {
        this.mapper = mapper;
        this.maxGoalsPerMethod = Math.max(1, maxGoalsPerMethod);
    }

    /** Formats goals into a numbered list (raw or LLM-friendly, per property). */
    public String format(Collection<TestFitnessFunction> goals) {
        return format(goals, null);
    }

    /**
     * Formats goals with optional fitness-distance annotations.
     *
     * @param goals the uncovered goals
     * @param fitnessDistances optional map of goal → best fitness distance
     *                         (0 = covered, lower = closer); may be null
     * @return formatted goal list
     */
    public String format(Collection<TestFitnessFunction> goals,
                         Map<TestFitnessFunction, Double> fitnessDistances) {
        if (goals == null || goals.isEmpty()) {
            return "No uncovered goals available.";
        }
        if (Properties.LLM_GOAL_FORMAT == Properties.LlmGoalFormat.RAW) {
            return formatRaw(goals);
        }
        return formatLlmFriendly(goals, fitnessDistances);
    }

    /**
     * Formats up to {@code maxGoals} closest uncovered goals for the given test.
     */
    public String formatClosestGoals(TestChromosome test,
                                     Collection<TestFitnessFunction> goals,
                                     int maxGoals) {
        if (goals == null || goals.isEmpty() || maxGoals <= 0) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (TestFitnessFunction goal : goals) {
            lines.add(describeGoal(goal));
            count++;
            if (count >= maxGoals) {
                break;
            }
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * Original flat numbered list using {@code goal.toString()}.
     */
    private String formatRaw(Collection<TestFitnessFunction> goals) {
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (TestFitnessFunction goal : goals) {
            lines.add(index++ + ". " + goal.toString());
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * LLM-friendly format: goals grouped by target method, sorted by fitness
     * distance within each group, with "almost covered" annotations and overflow caps.
     */
    private String formatLlmFriendly(Collection<TestFitnessFunction> goals,
                                     Map<TestFitnessFunction, Double> fitnessDistances) {
        Map<String, List<TestFitnessFunction>> grouped = groupByMethod(goals);

        StringBuilder sb = new StringBuilder();
        int globalIndex = 1;
        boolean first = true;

        for (Map.Entry<String, List<TestFitnessFunction>> entry : grouped.entrySet()) {
            String methodLabel = entry.getKey();
            List<TestFitnessFunction> methodGoals = entry.getValue();

            if (fitnessDistances != null && !fitnessDistances.isEmpty()) {
                methodGoals = sortByFitness(methodGoals, fitnessDistances);
            }

            if (!first) {
                sb.append(System.lineSeparator());
            }
            first = false;
            sb.append("## Method: ").append(methodLabel).append(System.lineSeparator());

            int shown = 0;
            for (TestFitnessFunction goal : methodGoals) {
                if (shown >= maxGoalsPerMethod) {
                    int remaining = methodGoals.size() - shown;
                    sb.append(globalIndex++).append(". ... and ").append(remaining)
                            .append(" more").append(System.lineSeparator());
                    break;
                }
                sb.append(globalIndex++).append(". ").append(describeGoal(goal));
                if (fitnessDistances != null) {
                    Double dist = fitnessDistances.get(goal);
                    if (dist != null && dist > 0 && dist < ALMOST_COVERED_THRESHOLD) {
                        sb.append(String.format(" [fitness: %.3f, almost covered]", dist));
                    }
                }
                sb.append(System.lineSeparator());
                shown++;
            }
        }
        return sb.toString().trim();
    }

    private String describeGoal(TestFitnessFunction goal) {
        if (Properties.LLM_GOAL_FORMAT == Properties.LlmGoalFormat.RAW) {
            return goal.toString();
        }
        return mapper.describe(goal);
    }

    /**
     * Groups goals by their target method name. Goals without an extractable
     * method name are placed in an "(other)" group.
     */
    Map<String, List<TestFitnessFunction>> groupByMethod(
            Collection<TestFitnessFunction> goals) {
        Map<String, List<TestFitnessFunction>> grouped = new LinkedHashMap<>();
        for (TestFitnessFunction goal : goals) {
            String methodLabel = mapper.extractQualifiedMethodLabel(goal);
            if (methodLabel.isEmpty()) {
                methodLabel = mapper.extractMethodName(goal);
            }
            if (methodLabel.isEmpty()) {
                methodLabel = "(other)";
            }
            grouped.computeIfAbsent(methodLabel, k -> new ArrayList<>()).add(goal);
        }
        return grouped;
    }

    /** Sorts goals within a group by fitness distance (ascending = closest first). */
    private List<TestFitnessFunction> sortByFitness(
            List<TestFitnessFunction> goals,
            Map<TestFitnessFunction, Double> fitnessDistances) {
        return goals.stream()
                .sorted(Comparator.comparingDouble(
                        g -> fitnessDistances.getOrDefault(g, Double.MAX_VALUE)))
                .collect(Collectors.toList());
    }
}
