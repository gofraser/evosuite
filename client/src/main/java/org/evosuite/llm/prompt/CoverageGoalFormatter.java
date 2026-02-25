package org.evosuite.llm.prompt;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Formats uncovered goals into readable lines for prompts.
 */
public class CoverageGoalFormatter {

    public String format(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return "No uncovered goals available.";
        }

        List<String> lines = new ArrayList<>();
        int index = 1;
        for (TestFitnessFunction goal : goals) {
            lines.add(index++ + ". " + goal.toString());
        }
        return String.join(System.lineSeparator(), lines);
    }

    public String formatClosestGoals(TestChromosome test,
                                     Collection<TestFitnessFunction> goals,
                                     int maxGoals) {
        if (goals == null || goals.isEmpty() || maxGoals <= 0) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        int count = 0;
        for (TestFitnessFunction goal : goals) {
            lines.add(goal.toString());
            count++;
            if (count >= maxGoals) {
                break;
            }
        }
        return String.join(System.lineSeparator(), lines);
    }
}
