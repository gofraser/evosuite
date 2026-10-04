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
import org.evosuite.junit.UnitTestAdapter;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated test class's own scaffolding (the {@code @Timeout} annotation) must not claim the
 * simple name {@code TimeUnit}: a class under test with that simple name, referenced unqualified
 * from the same package, would otherwise be shadowed by the single-type import.
 */
public class TimeoutAnnotationCompileTest {

    private final Properties.OutputFormat originalFormat = Properties.TEST_FORMAT;

    @AfterEach
    public void restore() {
        Properties.TEST_FORMAT = originalFormat;
    }

    @Test
    public void testTimeoutAnnotationDoesNotShadowSamePackageTimeUnit() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        UnitTestAdapter adapter = TestSuiteWriterUtils.getAdapter();

        String className = "TimeoutProbe" + System.nanoTime();
        String source = "package org.evosuite.junit.writer.collision;\n"
                + adapter.getImports()
                + "public class " + className + " {\n"
                + adapter.getMethodDefinition("test0") + " throws Throwable {\n"
                + "    TimeUnit unit = TimeUnit.DAY;\n"
                + "  }\n"
                + "}\n";

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-timeout-annotation", className, source);
        assertTrue(result.success, "Generated header did not compile:\n" + source + "\n" + result.stderr);
    }
}
