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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reading what became of the existing tests from EvoSuite's output, as EvoSuite prints it. */
public class SeedingReportTest {

    private static SeedingReport read(String testClasses, String... lines) {
        SeedingReport report = new SeedingReport(testClasses);
        for (String line : lines) {
            report.accept(line);
        }
        return report;
    }

    @Test
    public void reportsTheParsedTestsAndTheGoalsTheyCover() {
        SeedingReport report = read("VaultTest",
                "* Generating tests for class demo.Vault",
                "* Using 2 parsed tests from JUnit source for seeding",
                "* Determining coverage of existing tests",
                "  Executing VaultTest",
                "* Executed 2 unit test(s)",
                "* Removing 92 goals already covered by JUnit (total: 154)");

        assertTrue(report.seeded());
        assertEquals(2, report.parsedTests());
        assertTrue(report.ranExistingTests());
        assertNull(report.seedingProblem());
        assertNull(report.runProblem());
        assertEquals("EvoSuite started from 2 tests of VaultTest. Running 2 tests covered 92 of 154 goals, "
                + "which the new tests leave out.", report.summary());
    }

    @Test
    public void addsUpTheGoalsOfSeveralTestClassesAndMentionsDroppedTests() {
        SeedingReport report = read("VaultTest and VaultIT",
                "* Dropped 1 of 4 parsed tests that do not reference demo.Vault",
                "* Using 3 parsed tests from JUnit source for seeding",
                "* Executed 5 unit test(s)",
                "* Removing 40 goals already covered by JUnit (total: 154)",
                "* Removing 12 goals already covered by JUnit (total: 114)");

        assertEquals("EvoSuite started from 3 tests of VaultTest and VaultIT (1 more do not use the class). "
                + "Running 5 tests covered 52 of 154 goals, which the new tests leave out.", report.summary());
    }

    @Test
    public void saysWhySeedingHadNoEffect() {
        SeedingReport report = read("VaultTest",
                "* Could not find source for demo.VaultTest",
                "* No test cases could be parsed from demo.VaultTest (0 empty test(s) skipped); parsed JUnit "
                        + "seeding has no effect",
                "* Executed 2 unit test(s)",
                "* Removing 92 goals already covered by JUnit (total: 154)");

        assertFalse(report.seeded());
        assertEquals("The search did not start from the tests of VaultTest: there is no source file for "
                + "demo.VaultTest; no test cases could be parsed from demo.VaultTest (0 empty test(s) skipped).",
                report.seedingProblem());
        assertTrue(report.summary().startsWith(report.seedingProblem() + " Running 2 tests covered"),
                report.summary());

        report = read("VaultTest", "* None of the 2 parsed tests reference the target class demo.Vault; parsed "
                + "JUnit seeding has no effect");
        assertTrue(report.seedingProblem().endsWith(": none of the 2 parsed tests reference the target class "
                + "demo.Vault."), report.seedingProblem());

        report = read("VaultTest", "* Error parsing test class demo.VaultTest: unexpected token");
        assertTrue(report.seedingProblem().contains("EvoSuite could not parse demo.VaultTest: unexpected token"),
                report.seedingProblem());
    }

    @Test
    public void warnsWhenTheExistingTestsDidNotRun() {
        SeedingReport report = read("VaultTest",
                "* Using 2 parsed tests from JUnit source for seeding",
                "* Determining coverage of existing tests",
                "* Failed to find JUnit test suite: demo.VaultTest");

        assertTrue(report.seeded());
        assertFalse(report.ranExistingTests());
        assertEquals("EvoSuite could not run demo.VaultTest, so some new tests may repeat existing ones.",
                report.runProblem());
        // No line at all, as when EvoSuite stops before minimising.
        assertEquals("EvoSuite could not run VaultTest, so some new tests may repeat existing ones.",
                read("VaultTest", "* Using 2 parsed tests from JUnit source for seeding").runProblem());
    }
}
