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
import org.evosuite.junit.naming.methods.NumberedTestNameGenerationStrategy;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test body rendered inside the {@code Runnable} of the thread wrapper (used for security
 * exceptions and timeouts) cannot throw checked exceptions, yet constructs of the body that are not
 * statement-declared exceptions throw them: the reflective constructor fallback throws
 * {@code NoSuchMethodException} and friends. The rendered method must still compile.
 */
public class ThreadWrapperCheckedExceptionCompileTest {

    private String originalTargetClass;
    private Properties.OutputFormat originalFormat;

    /** A class whose only constructor is not source-accessible, forcing the reflective fallback. */
    public static class HiddenConstructor {
        private HiddenConstructor() {
        }
    }

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalFormat = Properties.TEST_FORMAT;
        Properties.TARGET_CLASS = ThreadWrapperCheckedExceptionCompileTest.class.getName();
        Properties.resetTargetClass();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.TEST_FORMAT = originalFormat;
        Properties.resetTargetClass();
    }

    @Test
    public void testReflectiveConstructorInsideThreadWrapperCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(HiddenConstructor.class.getDeclaredConstructor(),
                        HiddenConstructor.class),
                0, 0);

        ExecutionResult result = new ExecutionResult(tc);
        result.setSecurityException(true);
        result.setTrace(new ExecutionTraceImpl());

        TestSuiteWriter writer = new TestSuiteWriter();
        writer.insertTest(tc);
        Field nameGenerator = TestSuiteWriter.class.getDeclaredField("nameGenerator");
        nameGenerator.setAccessible(true);
        nameGenerator.set(writer, new NumberedTestNameGenerationStrategy(
                writer.getTestCases(), Collections.singletonList(result)));

        String method = writer.testToString(0, 0, result);
        assertTrue(method.contains("getDeclaredConstructor"),
                "fixture must exercise the reflective fallback:\n" + method);

        String className = "ThreadWrapperProbe" + System.nanoTime();
        String source = "package org.evosuite.junit.writer;\n"
                + "import java.util.concurrent.ExecutorService;\n"
                + "import java.util.concurrent.Executors;\n"
                + "import java.util.concurrent.Future;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import org.junit.jupiter.api.Timeout;\n"
                + "public class " + className + " {\n"
                + method
                + "}\n";

        JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                "evosuite-thread-wrapper", className, source);
        assertTrue(compiled.success, "Rendered method did not compile:\n" + source + "\n" + compiled.stderr);
    }
}
