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

import org.evosuite.Properties;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessingPromptContextTest {

    private int originalMaxLiteralChars;
    private int originalMaxObservationChars;
    private int originalMaxCollectionElements;

    @BeforeEach
    void saveProperties() {
        originalMaxLiteralChars = Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS;
        originalMaxObservationChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        originalMaxCollectionElements = Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = originalMaxLiteralChars;
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = originalMaxObservationChars;
        Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS = originalMaxCollectionElements;
    }

    @Test
    void from_exposesStatementContextWithStableIdsAndTypes() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new UninterpretedStatement(test, void.class, "System.gc();"));
        test.addStatement(new StringPrimitiveStatement(test, "value"));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        assertEquals(3, context.getStatements().size());
        assertFalse(context.getCallableMembers().isEmpty());
        assertEquals("s0", context.getStatements().get(0).getStatementId());
        assertEquals("v0", context.getStatements().get(0).getVariableId());
        assertEquals("int", context.getStatements().get(0).getDeclaredType());
        assertEquals("s1", context.getStatements().get(1).getStatementId());
        assertNull(context.getStatements().get(1).getVariableId());
        assertNull(context.getStatements().get(1).getDeclaredType());
        assertEquals("s2", context.getStatements().get(2).getStatementId());
        assertEquals("v2", context.getStatements().get(2).getVariableId());
        assertEquals("java.lang.String", context.getStatements().get(2).getDeclaredType());
        assertTrue(context.toCallableMemberText().contains("v2->java.lang.String"));
        assertTrue(context.toCallableMemberText().contains("owner=java.lang.String members=length()I->int"));
        assertTrue(context.toCallableMemberText().contains(", isEmpty()Z->boolean"));
    }

    @Test
    void toAnnotatedText_includesIdsAndRenderedCodeWithoutMutatingTest() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        String before = test.toCode();

        String annotated = LlmPostProcessingPromptContext.from(test).toAnnotatedText();

        assertTrue(annotated.contains("s0 v0 : int |"));
        assertTrue(annotated.contains("7"));
        assertEquals(before, test.toCode());
    }

    @Test
    void from_exposesPrimitiveInputsAsRelationalOnlyObservations() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new StringPrimitiveStatement(test, "value"));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        assertEquals(2, context.getObservations().size());
        assertEquals("s0", context.getObservations().get(0).getStatementId());
        assertEquals("v0", context.getObservations().get(0).getVariableId());
        assertEquals("INPUT", context.getObservations().get(0).getProvenance());
        assertEquals("7", context.getObservations().get(0).getValue());
        assertTrue(context.getObservations().get(0).isComplete());
        assertTrue(context.getObservations().get(0).isRelationalOnly());
        assertEquals("\"value\"", context.getObservations().get(1).getValue());
    }

    @Test
    void from_exposesFinalScopePrimitiveReturnValuesAsSutObservations() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, String.class,
                "return value;", Collections.emptyMap(), "value");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), "done");
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals("java.lang.String", context.getStatements().get(0).getRuntimeType());
        assertEquals(1, context.getObservations().size());
        assertEquals("SUT_RETURN", context.getObservations().get(0).getProvenance());
        assertEquals("\"done\"", context.getObservations().get(0).getValue());
        assertFalse(context.getObservations().get(0).isRelationalOnly());
        assertTrue(context.toCallableMemberText().contains("owner=java.lang.String members=length()I->int"));
        assertTrue(context.toCallableMemberText().contains("v0.length()I=4"));
        assertTrue(context.toCallableMemberText().contains("v0.isEmpty()Z=false"));
    }

    @Test
    void from_exposesBoundedStructuredFinalScopeValues() {
        Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS = 2;
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, List.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), Arrays.asList("a", "b", "c"));
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals(1, context.getObservations().size());
        assertEquals("collection size=3 elements=[\"a\", \"b\"]", context.getObservations().get(0).getValue());
        assertFalse(context.getObservations().get(0).isComplete());
        assertTrue(context.getObservations().get(0).isRelationalOnly());
        assertTrue(context.toCallableMemberText().contains("owner=java.util.Arrays$ArrayList members=size()I->int"));
        assertTrue(context.toCallableMemberText().contains("get(I)Ljava/lang/Object;->java.lang.String"));
        assertTrue(context.toCallableMemberText().contains("v0.size()I=3"));
        assertTrue(context.toCallableMemberText().contains("v0.isEmpty()Z=false"));
    }

    @Test
    void from_toleratesCollectionWithUnavailableElements() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, List.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new java.util.AbstractList<String>() {
            @Override
            public String get(int index) {
                return "value";
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<String> iterator() {
                return null;
            }
        });
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals(1, context.getObservations().size());
        assertEquals("collection size=1 elements unavailable", context.getObservations().get(0).getValue());
        assertFalse(context.getObservations().get(0).isComplete());
        assertTrue(context.getObservations().get(0).isRelationalOnly());
    }

    @Test
    void from_exposesEnumReturnValuesAsFullyQualifiedConstantObservations() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, java.util.concurrent.TimeUnit.class,
                "return unit;", Collections.emptyMap(), "unit");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), java.util.concurrent.TimeUnit.SECONDS);
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals(1, context.getObservations().size());
        assertEquals("SUT_RETURN", context.getObservations().get(0).getProvenance());
        assertEquals("java.util.concurrent.TimeUnit.SECONDS", context.getObservations().get(0).getValue());
        assertTrue(context.getObservations().get(0).isComplete());
    }

    @Test
    void from_exposesCuratedBigDecimalCallableMembers() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, BigDecimal.class,
                "return amount;", Collections.emptyMap(), "amount");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new BigDecimal("-3.50"));
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);
        String callableText = context.toCallableMemberText();

        assertTrue(callableText.contains("owner=java.math.BigDecimal members=abs()Ljava/math/BigDecimal;->java.math.BigDecimal"));
        assertTrue(callableText.contains("v0.signum()I=-1"));
        assertTrue(callableText.contains("compareTo(Ljava/math/BigDecimal;)I->int"));
    }

    @Test
    void from_exposesOnlyRepresentableMapCallableMembers() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, Map.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        Map<String, Integer> values = new HashMap<>();
        values.put("a", 1);
        scope.setObject(statement.getReturnValue(), values);
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);
        String callableText = context.toCallableMemberText();

        assertTrue(callableText.contains("owner=java.util.HashMap members=size()I->int"));
        assertTrue(callableText.contains("v0.size()I=1"));
        assertTrue(callableText.contains("v0.isEmpty()Z=false"));
        assertTrue(callableText.contains("containsKey(Ljava/lang/Object;)Z->boolean"));
    }

    @Test
    void from_toleratesMapWithUnavailableEntries() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, Map.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new HashMap<String, String>() {
            @Override
            public java.util.Set<Map.Entry<String, String>> entrySet() {
                return null;
            }
        });
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals(1, context.getObservations().size());
        assertEquals("map size=0 entries unavailable", context.getObservations().get(0).getValue());
        assertFalse(context.getObservations().get(0).isComplete());
        assertTrue(context.getObservations().get(0).isRelationalOnly());
    }

    @Test
    void from_exposesSanitizedExceptionContext() {
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 12;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new UninterpretedStatement(test, "throw new IllegalArgumentException();"));
        ExecutionResult result = new ExecutionResult(test);
        result.reportNewThrownException(0, new IllegalArgumentException("bad\ninput value"));
        result.getExplicitExceptions().put(0, true);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals(1, context.getExceptions().size());
        assertEquals("s0", context.getExceptions().get(0).getStatementId());
        assertEquals(IllegalArgumentException.class.getName(), context.getExceptions().get(0).getType());
        assertTrue(context.getExceptions().get(0).isExplicit());
        assertEquals("\"bad input va\"", context.getExceptions().get(0).getMessage());
        assertTrue(context.toExceptionText().contains("explicit=true"));
    }

    @Test
    void from_exposesEvoSuiteAssertionCandidatesAsStableFacts() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(statement.getReturnValue());
        assertion.setValue(7);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, Collections.singletonList(assertion));

        assertEquals(1, context.getCandidateFacts().size());
        assertEquals("s0", context.getCandidateFacts().get(0).getStatementId());
        assertEquals("v0", context.getCandidateFacts().get(0).getSourceId());
        assertEquals("PrimitiveAssertion", context.getCandidateFacts().get(0).getKind());
        assertEquals("7", context.getCandidateFacts().get(0).getObservedValue());
        assertTrue(context.toCandidateFactText().contains("source=v0"));
        assertTrue(context.toCandidateFactText().contains("refs=v0"));
        assertTrue(context.toCandidateFactText().contains("codeHint=\"assertEquals(7, v0);\""));
    }

    @Test
    void toParseContextCarriesObservedCandidateDuplicateKeys() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(statement.getReturnValue());
        assertion.setValue(7);
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, Collections.singletonList(assertion));
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                + "\"expected\":\"7\",\"actual\":\"v0\"}]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context.toParseContext());

        assertFalse(result.isInfrastructureFailure());
        assertTrue(result.getResponse().getAssertions().isEmpty());
        assertEquals(1, result.getDiagnostics().size());
        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.DUPLICATE,
                result.getDiagnostics().get(0).getCode());
    }

    @Test
    void toParseContextCarriesSetupInputIdsForDirectAssertionFiltering() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"assertions\":["
                + "{\"assertionId\":\"a0\",\"kind\":\"EQUALS\",\"expected\":\"7\",\"actual\":\"v0\"},"
                + "{\"assertionId\":\"a1\",\"kind\":\"TRUE\",\"actual\":\"v0 > 0\"}"
                + "]"
                + "}";

        LlmPostProcessingParseResult result = LlmPostProcessingResponseParser.parse(json, context.toParseContext());

        assertFalse(result.isInfrastructureFailure());
        assertEquals(1, result.getResponse().getAssertions().size());
        assertEquals("a1", result.getResponse().getAssertions().get(0).getAssertionId());
        assertEquals(1, result.getDiagnostics().size());
        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.DUPLICATE,
                result.getDiagnostics().get(0).getCode());
    }

    @Test
    void toCandidateFactText_prioritizesAndTruncatesWholeCandidateLines() {
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = 2000;
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);
        NullAssertion nullAssertion = new NullAssertion();
        nullAssertion.setSource(statement.getReturnValue());
        nullAssertion.setValue(false);
        PrimitiveAssertion primitiveAssertion = new PrimitiveAssertion();
        primitiveAssertion.setSource(statement.getReturnValue());
        primitiveAssertion.setValue(7);
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, Arrays.asList(nullAssertion, primitiveAssertion));
        String full = context.toCandidateFactText();
        String firstLine = full.substring(0, full.indexOf('\n') + 1);

        assertTrue(firstLine.contains("kind=PrimitiveAssertion"));

        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS =
                firstLine.length() + "truncatedCandidates=1\n".length();
        String bounded = context.toCandidateFactText();

        assertEquals(firstLine + "truncatedCandidates=1\n", bounded);
        assertFalse(bounded.contains("kind=NullAssertion"));
    }

    @Test
    void from_marksTruncatedPrimitiveInputsIncomplete() {
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 5;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, "abcdef"));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        assertEquals(1, context.getObservations().size());
        assertFalse(context.getObservations().get(0).isComplete());
        assertTrue(context.toObservationText().contains("complete=false"));
        assertTrue(context.toObservationText().contains("note=truncated"));
    }

    @Test
    void toObservationText_omitsWholeEntriesWhenObservationBudgetIsExhausted() {
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = 1000;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new StringPrimitiveStatement(test, "value"));
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);
        String firstLine = context.toObservationText().substring(0, context.toObservationText().indexOf('\n') + 1);

        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = firstLine.length();
        String bounded = context.toObservationText();

        assertEquals(firstLine, bounded);
        assertFalse(bounded.contains("s1"));
    }

    @Test
    void toCallableMemberText_isExplicitlyEmptyByDefault() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        assertEquals("none\n", context.toCallableMemberText());
    }
}
