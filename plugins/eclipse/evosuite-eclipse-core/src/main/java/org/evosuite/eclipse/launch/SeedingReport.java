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
package org.evosuite.eclipse.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What became of the existing tests in a run that extends them, read from EvoSuite's output:
 * how many of their tests EvoSuite parsed to start the search from, and how many coverage goals
 * they cover when run, which the new tests then leave out.
 */
public final class SeedingReport {

    private static final Pattern USING = Pattern.compile("\\* Using (\\d+) parsed tests from JUnit source for seeding");
    private static final Pattern DROPPED = Pattern.compile("\\* Dropped (\\d+) of (\\d+) parsed tests that do not reference \\S+");
    private static final Pattern NO_EFFECT = Pattern.compile("\\* (.*?);? parsed JUnit seeding has no effect");
    private static final Pattern NOT_FOUND = Pattern.compile("\\* Could not find source for (\\S+)");
    private static final Pattern PARSE_ERROR = Pattern.compile("\\* Error parsing test class (\\S+): (.*)");
    private static final Pattern EXECUTED = Pattern.compile("\\* Executed (\\d+) unit test\\(s\\)");
    private static final Pattern REMOVING =
            Pattern.compile("\\* Removing (\\d+) goals already covered by JUnit \\(total: (\\d+)\\)");
    private static final Pattern NOT_RUN = Pattern.compile("\\* Failed to find JUnit test suite: (\\S+)");

    private final String testClasses;
    private int parsed;
    private int dropped;
    private int executed = -1;
    private int coveredGoals = -1;
    private int totalGoals;
    private final List<String> parseProblems = new ArrayList<>();
    private final List<String> notRun = new ArrayList<>();

    /** @param testClasses the existing test classes, as the summary names them */
    public SeedingReport(String testClasses) {
        this.testClasses = testClasses;
    }

    /** Takes in one line of EvoSuite's output. */
    public void accept(String line) {
        String text = line.strip();
        Matcher matcher;
        if ((matcher = USING.matcher(text)).matches()) {
            parsed = Integer.parseInt(matcher.group(1));
        } else if ((matcher = DROPPED.matcher(text)).matches()) {
            dropped = Integer.parseInt(matcher.group(1));
        } else if ((matcher = NO_EFFECT.matcher(text)).matches()) {
            parseProblems.add(Character.toLowerCase(matcher.group(1).charAt(0)) + matcher.group(1).substring(1));
        } else if ((matcher = NOT_FOUND.matcher(text)).matches()) {
            parseProblems.add("there is no source file for " + matcher.group(1));
        } else if ((matcher = PARSE_ERROR.matcher(text)).matches()) {
            parseProblems.add("EvoSuite could not parse " + matcher.group(1) + ": " + matcher.group(2));
        } else if ((matcher = EXECUTED.matcher(text)).matches()) {
            executed = Integer.parseInt(matcher.group(1));
        } else if ((matcher = REMOVING.matcher(text)).matches()) {
            // Once per test class, each time out of what the previous ones left.
            if (coveredGoals < 0) {
                coveredGoals = 0;
                totalGoals = Integer.parseInt(matcher.group(2));
            }
            coveredGoals += Integer.parseInt(matcher.group(1));
        } else if ((matcher = NOT_RUN.matcher(text)).matches()) {
            notRun.add(matcher.group(1));
        }
    }

    /** How many existing tests the search started from. */
    public int parsedTests() {
        return parsed;
    }

    /** Whether the search started from existing tests. */
    public boolean seeded() {
        return parsed > 0;
    }

    /**
     * Whether EvoSuite ran the existing tests to leave out what they cover. If not, the new
     * tests may repeat them.
     */
    public boolean ranExistingTests() {
        return coveredGoals >= 0 && notRun.isEmpty();
    }

    /** What happened, in a sentence or two, for the console's summary. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (seeded()) {
            text.append("EvoSuite started from ").append(count(parsed, "test")).append(" of ").append(testClasses);
            if (dropped > 0) {
                text.append(" (").append(dropped).append(" more do not use the class)");
            }
            text.append('.');
        } else {
            text.append(seedingProblem());
        }
        if (ranExistingTests()) {
            text.append(" Running ").append(executed < 0 ? "them" : count(executed, "test"))
                    .append(" covered ").append(coveredGoals).append(" of ").append(totalGoals)
                    .append(" goals, which the new tests leave out.");
        } else {
            text.append(' ').append(runProblem());
        }
        return text.toString();
    }

    /**
     * Why the search did not start from the existing tests, or {@code null} if it did; for the
     * job's status.
     */
    public String seedingProblem() {
        if (seeded()) {
            return null;
        }
        String reason = parseProblems.isEmpty() ? "EvoSuite reported no parsed tests" : String.join("; ", parseProblems);
        return "The search did not start from the tests of " + testClasses + ": " + reason + ".";
    }

    /**
     * Why EvoSuite did not run the existing tests, or {@code null} if it did; for the job's
     * status.
     */
    public String runProblem() {
        if (ranExistingTests()) {
            return null;
        }
        String which = notRun.isEmpty() ? testClasses : String.join(", ", notRun);
        return "EvoSuite could not run " + which + ", so some new tests may repeat existing ones.";
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
