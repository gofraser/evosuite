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

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Formats uncovered goals into readable lines for prompts.
 */
public class CoverageGoalFormatter {

    /** Formats the given collection of uncovered goals into a numbered list. */
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
            lines.add(goal.toString());
            count++;
            if (count >= maxGoals) {
                break;
            }
        }
        return String.join(System.lineSeparator(), lines);
    }
}
