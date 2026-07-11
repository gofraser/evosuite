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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessingReferencesTest {

    @Test
    void from_assignsStatementIdsForEveryPositionAndVariableIdsForNameableReturns() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new UninterpretedStatement(test, void.class, "System.gc();"));
        test.addStatement(new StringPrimitiveStatement(test, "value"));

        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);

        assertTrue(references.hasStatementId("s0"));
        assertTrue(references.hasStatementId("s1"));
        assertTrue(references.hasStatementId("s2"));
        assertTrue(references.hasVariableId("v0"));
        assertFalse(references.hasVariableId("v1"));
        assertTrue(references.hasVariableId("v2"));
        assertEquals(0, references.getVariablePosition("v0"));
        assertEquals(2, references.getVariablePosition("v2"));
    }

    @Test
    void resolveVariable_usesPositionAgainstClonedTest() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new StringPrimitiveStatement(test, "value"));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);

        TestCase clone = test.clone();
        VariableReference resolved = references.resolveVariable(clone, "v1");

        assertSame(clone.getStatement(1).getReturnValue(), resolved);
        assertNotSame(test.getStatement(1).getReturnValue(), resolved);
    }

    @Test
    void resolveVariable_rejectsUnknownOrStructurallyChangedTargets() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);

        DefaultTestCase changed = new DefaultTestCase();
        changed.addStatement(new UninterpretedStatement(changed, void.class, "System.gc();"));

        assertThrows(IllegalArgumentException.class, () -> references.resolveVariable(test, "v9"));
        assertThrows(IllegalArgumentException.class, () -> references.resolveVariable(changed, "v0"));
    }

    @Test
    void toParseContextFeedsResponseParserUnknownIdValidation() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        String response = "{\"schemaVersion\":1,"
                + "\"variableNames\":{\"v0\":\"number\",\"v1\":\"missing\"},"
                + "\"comments\":[{\"afterStatementId\":\"s1\",\"text\":\"missing\"}]}";

        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                response, references.toParseContext());

        assertFalse(parsed.isInfrastructureFailure());
        assertEquals("number", parsed.getResponse().getVariableNames().get("v0"));
        assertFalse(parsed.getResponse().getVariableNames().containsKey("v1"));
        assertTrue(parsed.getResponse().getComments().isEmpty());
        assertEquals(2, parsed.getDiagnostics().size());
    }
}
