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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.Set;

/**
 * Converts EvoSuite test cases into prompt-friendly text.
 */
public class TestCaseFormatter {

    private final GoalDescriptionMapper goalMapper;

    public TestCaseFormatter() {
        this(new GoalDescriptionMapper());
    }

    public TestCaseFormatter(GoalDescriptionMapper goalMapper) {
        this.goalMapper = goalMapper;
    }

    /** Formats the given test case as a code string for inclusion in a prompt. */
    public String format(TestCase testCase) {
        if (testCase == null) {
            return "";
        }
        return testCase.toCode();
    }

    /**
     * Formats the test case with a leading comment block listing the
     * goals it covers, using {@link GoalDescriptionMapper} for readable
     * descriptions. Falls back to {@link #format(TestCase)} if no
     * coverage info is available.
     */
    public String formatWithCoverage(TestCase testCase) {
        return formatWithCoverage(testCase, goalMapper);
    }

    /**
     * Formats the test case with coverage annotations using the given mapper.
     */
    public String formatWithCoverage(TestCase testCase, GoalDescriptionMapper mapper) {
        if (testCase == null) {
            return "";
        }
        Set<TestFitnessFunction> covered = testCase.getCoveredGoals();
        if (covered == null || covered.isEmpty()) {
            return testCase.toCode();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// Covers: ");
        int count = 0;
        int maxInline = 5;
        for (TestFitnessFunction goal : covered) {
            if (count >= maxInline) {
                sb.append("\n//   ... and ").append(covered.size() - count).append(" more");
                break;
            }
            if (count > 0) {
                sb.append(",\n//   ");
            }
            sb.append(mapper.describe(goal));
            count++;
        }
        sb.append('\n');
        sb.append(testCase.toCode());
        return sb.toString();
    }
}
