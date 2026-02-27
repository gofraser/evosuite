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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.junit.examples.JUnit4Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JUnitRunnerTest {

    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;

    @AfterEach
    public void restoreProperties() {
        Properties.TEST_FORMAT = defaultOutputFormat;
    }

    @Test
    public void testJUnit5RunnerExecutesSelectedClass() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        JUnitRunner runner = new JUnitRunner(JUnit4Test.class);
        runner.run();

        Assertions.assertFalse(runner.getTestResults().isEmpty());
        Assertions.assertTrue(runner.getTestResults().stream()
                .anyMatch(result -> result.getName().contains("foo")));
        Assertions.assertTrue(runner.getTestResults().stream()
                .allMatch(result -> result.getJUnitClass().equals(JUnit4Test.class)));
    }
}
