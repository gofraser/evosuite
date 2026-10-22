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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order-sensitivity probe reads the failures of a re-run with the same policy as the
 * instability check: a loop-cap or watchdog timeout is not evidence that the order matters, and a
 * test that hung is not an expected failure.
 */
public class JUnitAnalyzerOrderSensitivityPolicyTest {

    private static final String TIMEOUT_EXCEEDED =
            "org.evosuite.testcase.execution.TestCaseExecutor$TimeoutExceeded";

    private static final Properties.Criterion[] defaultCriterion = Properties.CRITERION;
    private static final String defaultTargetClass = Properties.TARGET_CLASS;
    private static final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private static final boolean defaultScaffolding = Properties.TEST_SCAFFOLDING;

    @BeforeEach
    public void setUp() {
        JUnitCheckState.resetForPhase();
        TimeController.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        JUnitCheckState.resetForPhase();
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

    private static List<TestCase> twoTests(boolean firstFailing) throws Exception {
        TestCase first = compilingTestCase();
        if (firstFailing) {
            first.setFailing();
        }
        List<TestCase> tests = new ArrayList<>();
        tests.add(first);
        tests.add(compilingTestCase());
        return tests;
    }

    /** Probes the tests with a runner that reports the same failures for every run. */
    private static JUnitAnalyzer.OrderSensitivityAnalysis probe(List<TestCase> tests, boolean watchdogFired,
                                                                String... exceptionClassOfTestN)
            throws Exception {
        Field override = JUnitCheckRunner.class.getDeclaredField("VERSION_DEPENDENT_ANALYZER_FOR_TESTING");
        override.setAccessible(true);
        Class<?> analyzerType = Class.forName("org.evosuite.junit.JUnitCheckRunner$VersionDependentAnalyzing");
        JUnitResult result = new JUnitResult(false, exceptionClassOfTestN.length, tests.size());
        for (int i = 0; i < exceptionClassOfTestN.length; i++) {
            result.addFailure(new JUnitFailure("message", exceptionClassOfTestN[i], "test" + i, false, "trace"));
        }
        result.setWatchdogFired(watchdogFired);
        Object analyzer = Mockito.mock(analyzerType, invocation -> result);
        override.set(null, analyzer);
        try {
            return JUnitAnalyzer.analyzeOrderSensitivity(tests);
        } finally {
            override.set(null, null);
        }
    }

    @Test
    public void aGenuineFailureIsReported() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), false, "java.lang.IllegalStateException");

        assertEquals(Collections.singleton("test0"), analysis.getForwardFailures());
    }

    @Test
    public void aLoopCapTerminationIsNotOrderSensitivity() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), false, "org.evosuite.runtime.TooManyResourcesException");

        assertTrue(analysis.getForwardFailures().isEmpty(), "" + analysis.getForwardFailures());
    }

    @Test
    public void aTimeoutIsNotOrderSensitivity() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), false, "java.util.concurrent.TimeoutException");

        assertTrue(analysis.getForwardFailures().isEmpty(), "" + analysis.getForwardFailures());
    }

    @Test
    public void aCasualtyOfAStoppedRunIsNotOrderSensitivity() throws Exception {
        // Only the first timeout of a run the watchdog stopped is the test that hung.
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), true, TIMEOUT_EXCEEDED, TIMEOUT_EXCEEDED);

        assertEquals(Collections.singleton("test0"), analysis.getForwardFailures());
    }

    @Test
    public void aTestThatHungIsNotAnExpectedFailure() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(true), false, JUnitCheckProgress.HUNG_TEST_EXCEPTION);

        assertEquals(Collections.singleton("test0"), analysis.getForwardFailures());
    }

    @Test
    public void aClassInitializerFailureIsNotOrderSensitivity() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), false, "java.lang.ExceptionInInitializerError");

        assertTrue(analysis.getForwardFailures().isEmpty(), "" + analysis.getForwardFailures());
    }

    @Test
    public void aHeadlessEnvironmentFailureIsNotOrderSensitivity() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(false), false, "java.awt.HeadlessException");

        assertTrue(analysis.getForwardFailures().isEmpty(), "" + analysis.getForwardFailures());
    }

    @Test
    public void theExpectedFailureOfAFailingTestIsIgnored() throws Exception {
        JUnitAnalyzer.OrderSensitivityAnalysis analysis =
                probe(twoTests(true), false, "java.lang.IllegalStateException");

        assertTrue(analysis.getForwardFailures().isEmpty(), "" + analysis.getForwardFailures());
    }
}
