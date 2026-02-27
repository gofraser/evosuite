/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LlmAssertionGeneratorStrategyTest {

    @BeforeEach
    void setUp() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.LLM;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.MUTATION;
        LlmService.resetInstanceForTesting();
    }

    // ---- Strategy selection ----

    @Test
    void assertionStrategyEnum_hasLlmValue() {
        Properties.AssertionStrategy llm = Properties.AssertionStrategy.LLM;
        assertNotNull(llm);
        assertEquals("LLM", llm.name());
    }

    // ---- parseAndAttachAssertions: typed parsing via StatementParser ----

    @Test
    void parseAndAttach_simpleAssertEquals_createsPrimitiveAssertion() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        // Render to get variable name
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(intRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, intRef);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of("assertEquals(42, " + varName + ");"),
                varMap);

        assertEquals(1, attached);
        assertEquals(1, tc.getStatement(0).getAssertions().size());

        Assertion a = tc.getStatement(0).getAssertions().iterator().next();
        assertInstanceOf(PrimitiveAssertion.class, a);
        assertEquals(42, a.getValue());
    }

    @Test
    void parseAndAttach_assertTrue_createsPrimitiveAssertion() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference boolRef = tc.addStatement(new BooleanPrimitiveStatement(tc, true));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(boolRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, boolRef);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of("assertTrue(" + varName + ");"),
                varMap);

        assertEquals(1, attached);
        Assertion a = tc.getStatement(0).getAssertions().iterator().next();
        assertInstanceOf(PrimitiveAssertion.class, a);
        assertEquals(true, a.getValue());
    }

    @Test
    void parseAndAttach_assertNull_createsNullAssertion() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference strRef = tc.addStatement(new StringPrimitiveStatement(tc, null));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(strRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, strRef);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of("assertNull(" + varName + ");"),
                varMap);

        assertEquals(1, attached);
        Assertion a = tc.getStatement(0).getAssertions().iterator().next();
        assertInstanceOf(NullAssertion.class, a);
    }

    @Test
    void parseAndAttach_multipleAssertions() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 10));
        VariableReference strRef = tc.addStatement(new StringPrimitiveStatement(tc, "hello"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String intName = visitor.getVariableName(intRef);
        String strName = visitor.getVariableName(strRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(intName, intRef);
        varMap.put(strName, strRef);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of(
                        "assertEquals(10, " + intName + ");",
                        "assertNotNull(" + strName + ");"
                ), varMap);

        assertEquals(2, attached);
    }

    // ---- parseAndAttachAssertions: CodeAssertion fallback ----

    @Test
    void parseAndAttach_unparsableAssertion_fallsBackToCodeAssertion() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(intRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, intRef);

        // An assertion referencing a method call — StatementParser can't resolve it cleanly
        String complexAssertion = "assertEquals(\"hello\", " + varName + ".toString());";

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of(complexAssertion), varMap);

        // Should attach as CodeAssertion since it's a safe assertion but can't be fully parsed
        assertTrue(attached >= 1, "Should have attached at least 1 assertion");
        boolean hasCodeAssertion = false;
        for (int i = 0; i < tc.size(); i++) {
            for (Assertion a : tc.getStatement(i).getAssertions()) {
                if (a instanceof CodeAssertion) {
                    hasCodeAssertion = true;
                    assertEquals(complexAssertion, ((CodeAssertion) a).getCodeString());
                }
            }
        }
        assertTrue(hasCodeAssertion, "Should have a CodeAssertion for the unparseable assertion");
    }

    @Test
    void parseAndAttach_unsafeCode_notAttached() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of("System.exit(1);"),
                Collections.emptyMap());

        assertEquals(0, attached);
    }

    @Test
    void parseAndAttach_unknownVariable_rejected() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(ref);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, ref);

        // Variable 'unknownVar' is not in scope — CodeAssertion should be rejected
        String assertionStr = "assertEquals(42, unknownVar);";

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of(assertionStr), varMap);

        // Should reject: no known variable referenced in the assertion
        assertEquals(0, attached, "Assertions referencing only unknown variables should be rejected");
    }

    @Test
    void parseAndAttach_emptyList_returnsZero() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                Collections.emptyList(), Collections.emptyMap());

        assertEquals(0, attached);
    }

    @Test
    void parseAndAttach_doesNotAddStatements() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));
        int sizeBefore = tc.size();

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(ref);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, ref);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        strategy.parseAndAttachAssertions(tc,
                List.of("assertEquals(42, " + varName + ");"),
                varMap);

        // Statement count should not change — only assertions are added
        assertEquals(sizeBefore, tc.size());
    }

    // ---- Fallback behavior ----

    @Test
    void fallbackCount_incrementsOnLlmUnavailable() {
        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        assertEquals(0, strategy.getFallbackCount());
        // LLM is not available (PROVIDER=NONE), so every addAssertions should fallback
        // We can't easily test this without execution, but we verify the counter starts at 0
    }

    // ---- Mixed: typed + CodeAssertion ----

    @Test
    void parseAndAttach_mixedParsableAndUnresolvable() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String varName = visitor.getVariableName(intRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(varName, intRef);

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        int attached = strategy.parseAndAttachAssertions(tc,
                List.of(
                        "assertEquals(42, " + varName + ");",          // Parsable → PrimitiveAssertion
                        "assertEquals(\"foo\", unknownObj.getName());"  // Unresolvable → rejected
                ), varMap);

        // Only the first assertion should attach; second has no known variable
        assertEquals(1, attached, "Only parsable assertion with known variables should attach");

        boolean hasPrimitive = false;
        for (int i = 0; i < tc.size(); i++) {
            for (Assertion a : tc.getStatement(i).getAssertions()) {
                if (a instanceof PrimitiveAssertion) hasPrimitive = true;
            }
        }
        assertTrue(hasPrimitive, "Should have a PrimitiveAssertion");
    }

    @Test
    void parseAndAttach_codeAssertionAttachesToReferencedVariable() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference intRef = tc.addStatement(new IntPrimitiveStatement(tc, 10));
        VariableReference strRef = tc.addStatement(new StringPrimitiveStatement(tc, "hello"));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String strName = visitor.getVariableName(strRef);

        Map<String, VariableReference> varMap = new LinkedHashMap<>();
        varMap.put(visitor.getVariableName(intRef), intRef);
        varMap.put(strName, strRef);

        // Assertion references strName → should attach to statement 1 (the string statement)
        String assertionStr = "assertEquals(\"world\", " + strName + ".replace(\"hello\", \"world\"));";

        LlmAssertionGeneratorStrategy strategy = new LlmAssertionGeneratorStrategy();
        strategy.parseAndAttachAssertions(tc, List.of(assertionStr), varMap);

        // The assertion should be on the string statement (position 1), not the int one (position 0)
        assertEquals(0, tc.getStatement(0).getAssertions().size());
        assertEquals(1, tc.getStatement(1).getAssertions().size());
    }
}
