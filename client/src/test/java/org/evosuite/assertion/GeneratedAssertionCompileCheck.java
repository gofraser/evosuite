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
package org.evosuite.assertion;

import org.evosuite.utils.generic.JavacScratchCompiler;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles a single rendered assertion statement, so assertion-rendering tests
 * fail the way a generated test suite actually would -- at javac time -- rather
 * than merely string-matching rendered code that is never compiled and so could
 * be invalid Java. Delegates the actual compile (and, since it used to leak one
 * scratch directory per call, the lack of any cleanup to delegate) to
 * {@link JavacScratchCompiler}, which compiles entirely in memory.
 */
final class GeneratedAssertionCompileCheck {

    private GeneratedAssertionCompileCheck() {
    }

    /**
     * Compiles {@code declaration} followed by {@code assertionStatement} as the
     * body of a synthetic method, in the given package (an empty string for the
     * default package), against this test run's own classpath. Fails the calling
     * test if compilation does not succeed.
     *
     * @param packageName       the package the synthetic class is compiled in
     *                          (needed so package-private fixture members stay
     *                          accessible), or {@code ""} for the default package
     * @param declaration       a local-variable declaration making the rendered
     *                          assertion's actual expression available
     * @param assertionStatement the statement under test, e.g. the result of
     *                          {@code Assertion#getCode()}
     */
    static void assertCompiles(String packageName, String declaration, String assertionStatement) {
        assertCompiles(packageName, "", declaration, assertionStatement);
    }

    /**
     * As {@link #assertCompiles(String, String, String)}, with an additional
     * block of import statements (e.g. for a rendered unqualified class name
     * like {@code PureEnumFixture.ONE}, which relies on an import rather than a
     * fully-qualified reference).
     *
     * @param imports extra {@code import ...;} lines, or {@code ""} for none
     */
    static void assertCompiles(String packageName, String imports, String declaration, String assertionStatement) {
        String className = "GeneratedAssertionSnippet" + System.nanoTime();
        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n");
        }
        source.append("import static org.junit.jupiter.api.Assertions.assertEquals;\n")
                .append("import static org.junit.jupiter.api.Assertions.assertNull;\n");
        if (!imports.isEmpty()) {
            source.append(imports).append("\n");
        }
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() {\n")
                .append("        ").append(declaration).append("\n")
                .append("        ").append(assertionStatement).append("\n")
                .append("    }\n")
                .append("}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-enum-assertion-compile", className, source.toString());

        assertTrue(result.success, "Generated assertion did not compile:\n" + source
                + "\n" + result.stderr);
    }
}
