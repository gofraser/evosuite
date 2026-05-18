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
package org.evosuite.llm.response;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccessViolationDiagnosticParserTest {

    @Test
    void parsesProtectedAccessDiagnostic() {
        String diagnostic =
                "error: getMap() has protected access in NodeHookAdapter";
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        AccessViolationDiagnosticParser.AccessViolation v = violations.get(0);
        assertEquals("getMap", v.getMemberName());
        assertEquals("protected", v.getAccessLevel());
        assertEquals("NodeHookAdapter", v.getDeclaringClass());
    }

    @Test
    void parsesPrivateAccessDiagnostic() {
        String diagnostic =
                "error: select(String,List) has private access in ChangeNodeLevelAction";
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        AccessViolationDiagnosticParser.AccessViolation v = violations.get(0);
        assertEquals("select", v.getMemberName());
        assertEquals("private", v.getAccessLevel());
        assertEquals("ChangeNodeLevelAction", v.getDeclaringClass());
    }

    @Test
    void parsesFieldProtectedAccessDiagnostic() {
        String diagnostic =
                "error: state has protected access in BaseRecognizer";
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        AccessViolationDiagnosticParser.AccessViolation v = violations.get(0);
        assertEquals("state", v.getMemberName());
        assertEquals("protected", v.getAccessLevel());
        assertEquals("BaseRecognizer", v.getDeclaringClass());
    }

    @Test
    void parsesCannotBeAccessedFromOutsidePackage() {
        String diagnostic =
                "error: InternalHelper is not public in com.example.internal; "
                + "cannot be accessed from outside package";
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        AccessViolationDiagnosticParser.AccessViolation v = violations.get(0);
        assertEquals("InternalHelper", v.getMemberName());
        assertEquals("package-private", v.getAccessLevel());
        assertEquals("com.example.internal", v.getDeclaringClass());
    }

    /**
     * Regression test modeled after the ChangeNodeLevelAction failure pattern
     * where multiple inaccessible members appear in the same diagnostic output.
     */
    @Test
    void parsesMultipleViolationsFromChangeNodeLevelActionDiagnostic() {
        String diagnostic =
                "__ParseCompileProbe_123.java:15: error: getMap() has protected access in NodeHookAdapter\n"
                + "        changeNodeLevelAction0.getMap();\n"
                + "                              ^\n"
                + "__ParseCompileProbe_123.java:18: error: select(String,List) has private access in ChangeNodeLevelAction\n"
                + "        changeNodeLevelAction0.select(\"key\", list0);\n"
                + "                              ^\n"
                + "__ParseCompileProbe_123.java:22: error: obtainFocusForSelected() has protected access in NodeHookAdapter\n"
                + "        changeNodeLevelAction0.obtainFocusForSelected();\n"
                + "                              ^\n"
                + "3 errors";

        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(3, violations.size());

        assertEquals("getMap", violations.get(0).getMemberName());
        assertEquals("protected", violations.get(0).getAccessLevel());
        assertEquals("NodeHookAdapter", violations.get(0).getDeclaringClass());

        assertEquals("select", violations.get(1).getMemberName());
        assertEquals("private", violations.get(1).getAccessLevel());
        assertEquals("ChangeNodeLevelAction", violations.get(1).getDeclaringClass());

        assertEquals("obtainFocusForSelected", violations.get(2).getMemberName());
        assertEquals("protected", violations.get(2).getAccessLevel());
        assertEquals("NodeHookAdapter", violations.get(2).getDeclaringClass());
    }

    @Test
    void parsesMixedDiagnosticsExtractsOnlyAccessViolations() {
        String diagnostic =
                "error: cannot find symbol\n"
                + "  symbol: variable foo\n"
                + "error: getMap() has protected access in NodeHookAdapter\n"
                + "error: incompatible types: int cannot be converted to String\n";

        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        assertEquals("getMap", violations.get(0).getMemberName());
    }

    @Test
    void parsesNoDiagnosticsReturnsEmptyList() {
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse("error: cannot find symbol");

        assertTrue(violations.isEmpty());
    }

    @Test
    void parsesNullReturnsEmptyList() {
        assertTrue(AccessViolationDiagnosticParser.parse(null).isEmpty());
    }

    @Test
    void parsesEmptyStringReturnsEmptyList() {
        assertTrue(AccessViolationDiagnosticParser.parse("").isEmpty());
    }

    @Test
    void deduplicatesIdenticalViolations() {
        String diagnostic =
                "error: getMap() has protected access in NodeHookAdapter\n"
                + "error: getMap() has protected access in NodeHookAdapter\n";

        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
    }

    @Test
    void containsAccessViolationReturnsTrueForPrivateAccess() {
        assertTrue(AccessViolationDiagnosticParser.containsAccessViolation(
                "error: select(String,List) has private access in Foo"));
    }

    @Test
    void containsAccessViolationReturnsTrueForProtectedAccess() {
        assertTrue(AccessViolationDiagnosticParser.containsAccessViolation(
                "error: getMap() has protected access in Bar"));
    }

    @Test
    void containsAccessViolationReturnsTrueForOutsidePackage() {
        assertTrue(AccessViolationDiagnosticParser.containsAccessViolation(
                "InternalHelper is not public in com.example; cannot be accessed from outside package"));
    }

    @Test
    void containsAccessViolationReturnsFalseForUnrelatedError() {
        assertFalse(AccessViolationDiagnosticParser.containsAccessViolation(
                "error: cannot find symbol"));
    }

    @Test
    void containsAccessViolationReturnsFalseForNull() {
        assertFalse(AccessViolationDiagnosticParser.containsAccessViolation(null));
    }

    @Test
    void extractTrackingKeysReturnsCorrectKeys() {
        String diagnostic =
                "error: getMap() has protected access in NodeHookAdapter\n"
                + "error: select(String,List) has private access in ChangeNodeLevelAction";

        Set<String> keys = AccessViolationDiagnosticParser.extractTrackingKeys(diagnostic);

        assertEquals(2, keys.size());
        assertTrue(keys.contains("getMap:NodeHookAdapter"));
        assertTrue(keys.contains("select:ChangeNodeLevelAction"));
    }

    @Test
    void extractTrackingKeysReturnsEmptyForNoViolations() {
        Set<String> keys = AccessViolationDiagnosticParser.extractTrackingKeys(
                "error: cannot find symbol");

        assertTrue(keys.isEmpty());
    }

    @Test
    void trackingKeyFormatIsStable() {
        AccessViolationDiagnosticParser.AccessViolation v =
                new AccessViolationDiagnosticParser.AccessViolation(
                        "getMap", "protected", "NodeHookAdapter");
        assertEquals("getMap:NodeHookAdapter", v.toTrackingKey());
    }

    @Test
    void toStringIncludesAllFields() {
        AccessViolationDiagnosticParser.AccessViolation v =
                new AccessViolationDiagnosticParser.AccessViolation(
                        "select", "private", "ChangeNodeLevelAction");
        assertEquals("select has private access in ChangeNodeLevelAction", v.toString());
    }

    @Test
    void parsesFullyQualifiedDeclaringClass() {
        String diagnostic =
                "error: doIt() has protected access in com.example.foo.AbstractBase";
        List<AccessViolationDiagnosticParser.AccessViolation> violations =
                AccessViolationDiagnosticParser.parse(diagnostic);

        assertEquals(1, violations.size());
        // simpleName strips the package
        assertEquals("AbstractBase", violations.get(0).getDeclaringClass());
    }
}
