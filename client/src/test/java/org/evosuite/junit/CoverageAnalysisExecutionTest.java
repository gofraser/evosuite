/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

import java.lang.reflect.Method;
import java.util.List;

public class CoverageAnalysisExecutionTest {

    private final Properties.OutputFormat defaultOutputFormat = Properties.TEST_FORMAT;

    @AfterEach
    public void restoreProperties() {
        Properties.TEST_FORMAT = defaultOutputFormat;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCoverageAnalysisExecuteTestsRunsJUnit5Suite() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        Method executeTests = CoverageAnalysis.class.getDeclaredMethod("executeTests", Class[].class);
        executeTests.setAccessible(true);

        List<JUnitResult> results = (List<JUnitResult>) executeTests.invoke(null, (Object) new Class<?>[]{JUnit4Test.class});

        Assertions.assertFalse(results.isEmpty());
        Assertions.assertTrue(results.stream().anyMatch(result -> result.getName().contains("foo")));
        Assertions.assertTrue(results.stream().anyMatch(result -> result.getName().contains("bar")));
    }
}
