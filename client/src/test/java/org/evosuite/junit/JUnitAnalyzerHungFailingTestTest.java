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
package org.evosuite.junit;

import com.examples.with.different.packagename.test.DowncastExample;
import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test the search recorded as failing, such as one that was killed there, has its failure in
 * the check ignored as expected. A test that hung in the check is not such a failure: it has to be
 * removed like any other test that fails the check.
 */
public class JUnitAnalyzerHungFailingTestTest {

    private static final Properties.Criterion[] defaultCriterion = Properties.CRITERION;
    private static final String defaultTargetClass = Properties.TARGET_CLASS;
    private static final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private static final boolean defaultScaffolding = Properties.TEST_SCAFFOLDING;

    @BeforeEach
    public void setUp() {
        JUnitAnalyzer.resetMemoryExhaustion();
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        JUnitAnalyzer.resetMemoryExhaustion();
        Properties.CRITERION = defaultCriterion;
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.TEST_FORMAT = defaultTestFormat;
        Properties.TEST_SCAFFOLDING = defaultScaffolding;
        TimeController.resetSingleton();
    }

    private static TestCase compilingTestCase() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();
        TestCaseBuilder builder = new TestCaseBuilder();
        builder.appendConstructor(DowncastExample.class.getConstructor());
        return builder.getDefaultTestCase();
    }

    private int checkWith(String exceptionClass, List<TestCase> tests) throws Exception {
        Field override = JUnitAnalyzer.class.getDeclaredField("VERSION_DEPENDENT_ANALYZER_FOR_TESTING");
        override.setAccessible(true);
        Class<?> analyzerType = Class.forName("org.evosuite.junit.JUnitAnalyzer$VersionDependentAnalyzing");
        JUnitResult result = new JUnitResult(false, 1, 1);
        result.addFailure(new JUnitFailure("message", exceptionClass, "test0", false, "trace"));
        Object analyzer = Mockito.mock(analyzerType, invocation -> result);
        override.set(null, analyzer);
        try {
            return JUnitAnalyzer.handleTestsThatAreUnstable(tests);
        } finally {
            override.set(null, null);
        }
    }

    @Test
    public void hungTestIsRemovedEvenIfItsFailureWasExpected() throws Exception {
        TestCase test = compilingTestCase();
        test.setFailing();
        List<TestCase> tests = new ArrayList<>();
        tests.add(test);

        int numUnstable = checkWith(JUnitCheckProgress.HUNG_TEST_EXCEPTION, tests);

        assertEquals(1, numUnstable);
        assertTrue(tests.isEmpty(), "the test that hung must not stay in the suite");
    }

    @Test
    public void expectedFailureOfAFailingTestIsStillKept() throws Exception {
        TestCase test = compilingTestCase();
        test.setFailing();
        List<TestCase> tests = new ArrayList<>();
        tests.add(test);

        int numUnstable = checkWith("java.lang.IllegalStateException", tests);

        assertEquals(0, numUnstable);
        assertEquals(1, tests.size());
    }
}
