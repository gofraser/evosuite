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

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckedExceptionStubTest {

    /** Mock target with overloads that differ in whether they declare a checked exception. */
    public interface Source {
        void plain(String value);

        void declared(String value) throws java.io.IOException;

        void mixed(String value) throws java.io.IOException;

        void mixed(Integer value);
    }

    private static final List<String> IMPORTS = Arrays.asList(
            "import java.io.*;",
            "import static org.mockito.Mockito.*;",
            "import org.evosuite.runtime.ViolatedAssumptionAnswer;");

    private static final String SOURCE = Source.class.getCanonicalName();

    private static final String MOCK = SOURCE + " source0 = mock(" + SOURCE
            + ".class, new ViolatedAssumptionAnswer());\n";

    private String originalTargetClass;
    private boolean originalSandbox;

    @BeforeEach
    void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalSandbox = Properties.SANDBOX;
        Properties.SANDBOX = false;
        Properties.TARGET_CLASS = CheckedExceptionStubTest.class.getName();
        Properties.resetTargetClass();
        assertNotNull(Properties.getTargetClassAndDontInitialise());
    }

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.SANDBOX = originalSandbox;
        Properties.resetTargetClass();
    }

    private static ParseResult parse(String stub) {
        TestParser parser = new TestParser(CheckedExceptionStubTest.class.getClassLoader());
        parser.setMarkParsedFromLlm(true);
        return parser.parseTestMethodBody(MOCK + stub, IMPORTS);
    }

    private static List<ParseDiagnostic> stubErrors(ParseResult result) {
        List<ParseDiagnostic> found = new java.util.ArrayList<>();
        for (ParseDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getKind() == DiagnosticKind.UNDECLARED_CHECKED_EXCEPTION_STUB) {
                found.add(diagnostic);
            }
        }
        return found;
    }

    private static void assertReported(String stub) {
        ParseResult result = parse(stub);
        List<ParseDiagnostic> errors = stubErrors(result);
        assertEquals(1, errors.size(), result.getDiagnostics().toString());
        ParseDiagnostic error = errors.get(0);
        assertEquals(ParseDiagnostic.Severity.ERROR, error.getSeverity());
        assertTrue(error.getMessage().contains(
                        "plain() of Source cannot throw java.io.IOException, which it does not declare "
                                + "(declared: none)"),
                error.getMessage());
        assertNotNull(error.getRepairAction());
        assertTrue(error.getRepairAction().contains("unchecked exception"), error.getRepairAction());
    }

    private static void assertNotReported(String stub) {
        ParseResult result = parse(stub);
        assertTrue(stubErrors(result).isEmpty(), result.getDiagnostics().toString());
    }

    @Test
    void whenThenThrowOfUndeclaredCheckedExceptionIsAnError() {
        assertReported("when(source0.plain(\"a\")).thenThrow(new IOException(\"boom\"));");
    }

    @Test
    void doThrowOfUndeclaredCheckedExceptionIsAnError() {
        assertReported("doThrow(new IOException(\"boom\")).when(source0).plain(anyString());");
    }

    @Test
    void declaredCheckedExceptionIsAccepted() {
        assertNotReported("doThrow(new IOException(\"boom\")).when(source0).declared(anyString());");
    }

    @Test
    void subclassOfDeclaredExceptionIsAccepted() {
        assertNotReported("doThrow(new FileNotFoundException(\"boom\")).when(source0).declared(anyString());");
    }

    @Test
    void uncheckedExceptionIsAccepted() {
        assertNotReported("doThrow(new IllegalStateException(\"boom\")).when(source0).plain(anyString());");
    }

    @Test
    void stubIsValidWhenAnyMatchingOverloadDeclaresTheException() {
        assertNotReported("doThrow(new IOException(\"boom\")).when(source0).mixed(any());");
    }

    @Test
    void unresolvableMethodIsNotReported() {
        assertNotReported("doThrow(new IOException(\"boom\")).when(source0).noSuchMethod(anyString());");
    }

    @Test
    void unresolvableExceptionIsNotReported() {
        assertNotReported("doThrow(new NoSuchCheckedException(\"boom\")).when(source0).plain(anyString());");
    }
}
