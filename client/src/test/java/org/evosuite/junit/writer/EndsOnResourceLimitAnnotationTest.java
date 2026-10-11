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
package org.evosuite.junit.writer;

import org.evosuite.Properties;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test that ends on a resource limit is marked, so that the JUnit rule also accepts the stack
 * overflow the same unbounded recursion can end on; no other test is marked.
 */
public class EndsOnResourceLimitAnnotationTest {

    private static final String MARKER = "@org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit";

    private final Properties.OutputFormat originalFormat = Properties.TEST_FORMAT;
    private final boolean originalScaffolding = Properties.TEST_SCAFFOLDING;
    private final Properties.TestNamingStrategy originalNaming = Properties.TEST_NAMING_STRATEGY;

    private static volatile Throwable toThrow;

    public static void probe() {
        Throwable t = toThrow;
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
    }

    @AfterEach
    public void restore() {
        Properties.TEST_FORMAT = originalFormat;
        Properties.TEST_SCAFFOLDING = originalScaffolding;
        Properties.TEST_NAMING_STRATEGY = originalNaming;
        toThrow = null;
    }

    private static DefaultTestCase testThrowing(Throwable t) throws Exception {
        toThrow = t;
        DefaultTestCase test = new DefaultTestCase();
        Method probe = EndsOnResourceLimitAnnotationTest.class.getMethod("probe");
        test.addStatement(new MethodStatement(test, new GenericMethod(probe,
                EndsOnResourceLimitAnnotationTest.class), null, Collections.emptyList()));
        return test;
    }

    private static String write(Properties.OutputFormat format, DefaultTestCase test) throws Exception {
        Properties.TEST_FORMAT = format;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
        Path dir = Files.createTempDirectory("evosuite-ends-on-limit-");
        TestSuiteWriter writer = new TestSuiteWriter();
        writer.insertTest(test);
        writer.writeTestSuite("LimitMarkerTest", dir.toString(), Collections.emptyList());
        return new String(Files.readAllBytes(dir.resolve("LimitMarkerTest.java")), StandardCharsets.UTF_8);
    }

    @Test
    public void markedWhenTheStatementEndsOnTheLimit() throws Exception {
        for (Properties.OutputFormat format : new Properties.OutputFormat[]{
                Properties.OutputFormat.JUNIT4, Properties.OutputFormat.JUNIT5}) {
            String code = write(format, testThrowing(new TooManyResourcesException("Loop")));
            assertTrue(code.contains(MARKER), format + ":\n" + code);
            assertTrue(code.indexOf(MARKER) < code.indexOf("public void test0"), code);
        }
    }

    @Test
    public void markedWhenTheLimitIsWrapped() throws Exception {
        String code = write(Properties.OutputFormat.JUNIT4, testThrowing(
                new RuntimeException("outer", new TooManyResourcesException("Loop"))));
        assertTrue(code.contains(MARKER), code);
    }

    @Test
    public void notMarkedForOtherOutcomes() throws Exception {
        assertFalse(write(Properties.OutputFormat.JUNIT4, testThrowing(null)).contains(MARKER));
        assertFalse(write(Properties.OutputFormat.JUNIT4, testThrowing(new IllegalStateException("x")))
                .contains(MARKER));
        assertFalse(write(Properties.OutputFormat.JUNIT4, testThrowing(new StackOverflowError()))
                .contains(MARKER));
    }

    @Test
    public void junit3HasNoRuleToHookIntoSoItIsNotMarked() throws Exception {
        String code = write(Properties.OutputFormat.JUNIT3, testThrowing(new TooManyResourcesException("Loop")));
        assertFalse(code.contains(MARKER), code);
    }
}
