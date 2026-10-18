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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Test names are positional ({@code test0}, {@code test1}, ...). When the instability check
 * removes a failing test it must not re-derive the names of the remaining failures from the
 * shrunken list, or a later failure name points at a different test.
 */
public class JUnitAnalyzerUnstableRemovalByNameTest {

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

    @Test
    public void removesExactlyTheTestsThatFailed() throws Exception {
        TestCase first = compilingTestCase();
        TestCase second = compilingTestCase();
        TestCase third = compilingTestCase();
        List<TestCase> tests = new ArrayList<>();
        tests.add(first);
        tests.add(second);
        tests.add(third);

        // test0 and test1 fail with an uncaught exception; test2 passes.
        int numUnstable = checkWith(tests, "test0", "test1");

        assertEquals(2, numUnstable);
        assertEquals(1, tests.size(), "both failing tests must be removed");
        assertSame(third, tests.get(0),
                "the passing test2 must be kept; the second failure was matched against the"
                        + " renumbered list and removed the wrong test");
    }

    private static TestCase compilingTestCase() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();
        TestCaseBuilder builder = new TestCaseBuilder();
        builder.appendConstructor(DowncastExample.class.getConstructor());
        return builder.getDefaultTestCase();
    }

    private static int checkWith(List<TestCase> tests, String... failingTestNames) throws Exception {
        Field override = JUnitAnalyzer.class.getDeclaredField("VERSION_DEPENDENT_ANALYZER_FOR_TESTING");
        override.setAccessible(true);
        Class<?> analyzerType = Class.forName("org.evosuite.junit.JUnitAnalyzer$VersionDependentAnalyzing");
        JUnitResult result = new JUnitResult(false, failingTestNames.length, tests.size());
        for (String name : failingTestNames) {
            result.addFailure(new JUnitFailure("message", "java.lang.IllegalStateException", name,
                    false, "trace"));
        }
        Object analyzer = Mockito.mock(analyzerType, invocation -> result);
        override.set(null, analyzer);
        try {
            return JUnitAnalyzer.handleTestsThatAreUnstable(tests);
        } finally {
            override.set(null, null);
        }
    }
}
