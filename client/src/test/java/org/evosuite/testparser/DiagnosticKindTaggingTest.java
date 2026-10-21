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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the kind attached to the diagnostics that the repair code recognises, so that it can
 * rely on the kind instead of the message text.
 */
class DiagnosticKindTaggingTest {

    private static final List<String> IMPORTS = List.of("import java.lang.Object;");

    private static ParseResult parse(String body, boolean llm) {
        TestParser parser = new TestParser(DiagnosticKindTaggingTest.class.getClassLoader());
        parser.setMarkParsedFromLlm(llm);
        return parser.parseTestMethodBody(body, IMPORTS);
    }

    private static void assertKind(ParseResult result, DiagnosticKind kind, ParseDiagnostic.Severity severity) {
        assertTrue(result.getDiagnostics().stream().anyMatch(d ->
                        d.getKind() == kind && d.getSeverity() == severity),
                "Expected " + severity + " diagnostic of kind " + kind + " but got: " + result.getDiagnostics());
    }

    @Test
    void strictUnresolvedTypeIsTagged() {
        assertKind(parse("InventedType value = null;", false),
                DiagnosticKind.UNRESOLVED_TYPE, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void bestEffortUnresolvedClassIsTagged() {
        assertKind(parse("new Target();", true),
                DiagnosticKind.UNRESOLVED_TYPE, ParseDiagnostic.Severity.WARNING);
    }

    @Test
    void bestEffortDeclarationWithoutInitializerOfUnknownTypeIsTagged() {
        ParseResult result = parse("InventedType value;", true);

        ParseDiagnostic diagnostic = result.getDiagnostics().stream()
                .filter(d -> d.getMessage().contains("invented/unknown type 'InventedType'"))
                .findFirst().orElse(null);
        assertNotNull(diagnostic, "Expected the downgrade diagnostic: " + result.getDiagnostics());
        assertEquals(ParseDiagnostic.Severity.WARNING, diagnostic.getSeverity());
        assertEquals(DiagnosticKind.UNRESOLVED_TYPE, diagnostic.getKind());
        assertEquals("UNRESOLVED_TYPE", diagnostic.getKindName());
    }

    @Test
    void unresolvedVariableIsTagged() {
        assertKind(parse("int x = missingVar;", true),
                DiagnosticKind.UNRESOLVED_VARIABLE, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void unknownArrayVariableIsTagged() {
        assertKind(parse("arr[0] = 1;", false),
                DiagnosticKind.UNKNOWN_ARRAY_VAR, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void unresolvedMethodScopeIsTagged() {
        assertKind(parse("helper.run();", false),
                DiagnosticKind.NO_METHOD_SCOPE, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void missingMethodIsTagged() {
        assertKind(parse("String s = \"a\";\ns.noSuchMethod();", false),
                DiagnosticKind.NO_MATCHING_METHOD, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void missingConstructorIsTagged() {
        assertKind(parse("String s = new String(1, 2, 3, 4, 5);", false),
                DiagnosticKind.NO_MATCHING_CONSTRUCTOR, ParseDiagnostic.Severity.ERROR);
    }

    @Test
    void diagnosticWithoutKindReportsNone() {
        ParseDiagnostic diagnostic = new ParseDiagnostic(ParseDiagnostic.Severity.ERROR, "free form", 1, "x");

        assertNull(diagnostic.getKind());
        assertNull(diagnostic.getKindName());
    }
}
