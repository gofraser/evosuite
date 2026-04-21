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
package org.evosuite.testparser;

import org.evosuite.testcase.TestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parsing a single test method.
 *
 * <p><b>Caller contract:</b> the returned {@link TestCase} is only guaranteed to be
 * well-formed when {@link #hasErrors()} returns {@code false}. When errors are
 * present, the parser records them as ERROR diagnostics but still returns a
 * best-effort test case (possibly containing {@code UninterpretedStatement}s or
 * orphaned variable references) rather than throwing. Consumers that plan to
 * compile, execute, or mutate the test case <b>must</b> gate on
 * {@code hasErrors()}; passing an error-tainted test case to downstream
 * components is a bug.
 */
public class ParseResult {

    private final TestCase testCase;
    private final List<ParseDiagnostic> diagnostics;
    private final String originalMethodName;

    /** Expected exception class name from JUnit 4 @Test(expected=...) or JUnit 5 assertThrows */
    private String expectedExceptionClass;

    /**
     * Create a new ParseResult.
     *
     * @param testCase the test case.
     * @param originalMethodName the original method name.
     */
    public ParseResult(TestCase testCase, String originalMethodName) {
        this.testCase = testCase;
        this.diagnostics = new ArrayList<>();
        this.originalMethodName = originalMethodName;
    }

    /**
     * Create a new ParseResult with existing diagnostics.
     *
     * @param testCase the test case.
     * @param originalMethodName the original method name.
     * @param diagnostics the diagnostics.
     */
    public ParseResult(TestCase testCase, String originalMethodName, List<ParseDiagnostic> diagnostics) {
        this.testCase = testCase;
        this.originalMethodName = originalMethodName;
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public String getOriginalMethodName() {
        return originalMethodName;
    }

    public List<ParseDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public void addDiagnostic(ParseDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    /**
     * Returns {@code true} if any ERROR-severity diagnostic was recorded during parsing.
     *
     * <p>Callers <b>must</b> check this before using the returned {@link TestCase} —
     * when it is {@code true}, the test case is only a best-effort reconstruction
     * and is not guaranteed to compile, execute, or round-trip cleanly.
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.getSeverity() == ParseDiagnostic.Severity.ERROR);
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(d -> d.getSeverity() == ParseDiagnostic.Severity.WARNING);
    }

    public String getExpectedExceptionClass() {
        return expectedExceptionClass;
    }

    public void setExpectedExceptionClass(String expectedExceptionClass) {
        this.expectedExceptionClass = expectedExceptionClass;
    }
}
