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
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserLdapFallbackCharacterizationTest {

    public interface RenameTarget {
        void rename(String oldDn, String newDn);
    }

    public static class LdapAllowedIpsGroup {
        public List<Object> getAllowedips() {
            return new ArrayList<>();
        }
    }

    private ParseResult parseLlm(String body, List<String> imports) {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        return parser.parseTestMethodBody(body, imports);
    }

    private ParseResult parseLlmWithMockitoImports(String body) {
        return parseLlm(body, List.of(
                "import java.util.*;",
                "import static org.mockito.Mockito.*;",
                "import org.evosuite.testparser.StatementParserLdapFallbackCharacterizationTest.RenameTarget;",
                "import org.evosuite.runtime.ViolatedAssumptionAnswer;"
        ));
    }

    @Test
    void llmWhenThenReturnNullOnVoidMethodIsIgnoredWithoutVoidNullFallback() {
        ParseResult r = parseLlmWithMockitoImports(
                "RenameTarget target0 = mock(RenameTarget.class, new ViolatedAssumptionAnswer());\n"
                        + "when(target0.rename(eq(\"dnOld\"), eq(\"dnNew\"))).thenReturn(null);");
        TestCase tc = r.getTestCase();

        assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        boolean hasVoidTypedNull = false;
        for (int i = 0; i < tc.size(); i++) {
            Statement st = tc.getStatement(i);
            if (!(st instanceof NullStatement)) {
                continue;
            }
            Class<?> variableClass = st.getReturnValue().getVariableClass();
            if (variableClass == void.class || variableClass == Void.class || variableClass == Void.TYPE) {
                hasVoidTypedNull = true;
                break;
            }
        }
        assertFalse(hasVoidTypedNull,
                "Invalid void stubbing should not synthesize void-typed null fallbacks:\n" + tc.toCode());
        assertTrue(r.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage() != null
                                && d.getMessage().contains("Ignored invalid Mockito when(...).thenReturn(...) stubbing on void method")),
                "Expected warning for ignored invalid void stubbing: " + r.getDiagnostics());
    }

    @Test
    void llmVerifyNeverChainIsPreservedAtomicallyWhenVerifyReturnTypeCollapsesToObject() {
        ParseResult r = parseLlmWithMockitoImports(
                "RenameTarget target0 = mock(RenameTarget.class, new ViolatedAssumptionAnswer());\n"
                        + "verify(target0, never()).rename(eq(\"dnOld\"), eq(\"dnNew\"));");
        TestCase tc = r.getTestCase();

        assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        assertTrue(r.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage() != null
                                && d.getMessage().contains("No method named rename in Object")),
                "Expected collapsed verify-return receiver diagnostic: " + r.getDiagnostics());

        Statement last = tc.getStatement(tc.size() - 1);
        assertInstanceOf(UninterpretedStatement.class, last);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertTrue(code.contains("verify(") && code.contains(".rename("),
                "Full verify(...).rename(...) chain should be preserved atomically:\n" + code);
    }

    @Test
    void llmPreservesUnsupportedForEachLoopForLdapAllowedIpsPattern() {
        ParseResult r = parseLlm(
                "LdapAllowedIpsGroup g = new LdapAllowedIpsGroup();\n"
                        + "java.util.ArrayList<String> allowed = new java.util.ArrayList<String>();\n"
                        + "for (Object v : g.getAllowedips()) {\n"
                        + "    allowed.add((String) v);\n"
                        + "}",
                List.of(
                        "import java.util.ArrayList;",
                        "import org.evosuite.testparser.StatementParserLdapFallbackCharacterizationTest.LdapAllowedIpsGroup;"
                ));
        TestCase tc = r.getTestCase();

        assertFalse(r.hasErrors(), "Errors: " + r.getDiagnostics());
        assertTrue(r.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage() != null
                                && d.getMessage().contains("Unsupported statement type, preserved")),
                "Expected unsupported-loop preservation warning: " + r.getDiagnostics());
        assertInstanceOf(UninterpretedStatement.class, tc.getStatement(tc.size() - 1));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertTrue(code.contains("for (Object v :")
                        && code.contains(".getAllowedips())"),
                "Ldap-style loop should keep getAllowedips()-based for-each shape:\n" + code);
        assertTrue(code.contains(".add((String) v);"),
                "Loop body should be preserved in emitted code:\n" + code);
    }
}
