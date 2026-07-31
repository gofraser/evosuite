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
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.DoublePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.FloatPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericMethod;
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
    private int originalMaxCandidateChars;
    private int originalMaxCandidateFacts;
    private int originalMaxCallableChars;
    private int originalMaxCollectionElements;
    private String originalTargetClass;
    private Properties.LlmPostProcessingPromptVariant originalPromptVariant;
    private Properties.LlmPostProcessingCallablePolicy originalCallablePolicy;

    @BeforeEach
    void saveProperties() {
        originalMaxLiteralChars = Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS;
        originalMaxObservationChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        originalMaxCandidateChars = Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS;
        originalMaxCandidateFacts = Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_FACTS;
        originalMaxCallableChars = Properties.LLM_POSTPROCESSING_MAX_CALLABLE_CHARS;
        originalMaxCollectionElements = Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS;
        originalTargetClass = Properties.TARGET_CLASS;
        originalPromptVariant = Properties.LLM_POSTPROCESSING_PROMPT_VARIANT;
        originalCallablePolicy = Properties.LLM_POSTPROCESSING_CALLABLE_POLICY;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = originalMaxLiteralChars;
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = originalMaxObservationChars;
        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS = originalMaxCandidateChars;
        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_FACTS = originalMaxCandidateFacts;
        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_CHARS = originalMaxCallableChars;
        Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS = originalMaxCollectionElements;
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT = originalPromptVariant;
        Properties.LLM_POSTPROCESSING_CALLABLE_POLICY = originalCallablePolicy;
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
    void from_rendersSpecialFloatingInputsAsJavaConstants() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new DoublePrimitiveStatement(test, Double.POSITIVE_INFINITY));
        test.addStatement(new DoublePrimitiveStatement(test, Double.NEGATIVE_INFINITY));
        test.addStatement(new DoublePrimitiveStatement(test, Double.NaN));
        test.addStatement(new FloatPrimitiveStatement(test, Float.POSITIVE_INFINITY));
        test.addStatement(new FloatPrimitiveStatement(test, Float.NEGATIVE_INFINITY));
        test.addStatement(new FloatPrimitiveStatement(test, Float.NaN));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test);

        assertEquals("Double.POSITIVE_INFINITY", context.getObservations().get(0).getValue());
        assertEquals("Double.NEGATIVE_INFINITY", context.getObservations().get(1).getValue());
        assertEquals("Double.NaN", context.getObservations().get(2).getValue());
        assertEquals("Float.POSITIVE_INFINITY", context.getObservations().get(3).getValue());
        assertEquals("Float.NEGATIVE_INFINITY", context.getObservations().get(4).getValue());
        assertEquals("Float.NaN", context.getObservations().get(5).getValue());
    }

    @Test
    void from_classifiesConstructedArraysAsSetupInputs() {
        DefaultTestCase test = new DefaultTestCase();
        ArrayStatement statement = new ArrayStatement(test, String[].class, 0);
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new String[0]);
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals("SETUP", context.getStatements().get(0).getPhase());
        assertEquals(1, context.getObservations().size());
        assertEquals("INPUT", context.getObservations().get(0).getProvenance());
        assertTrue(context.getObservations().get(0).isRelationalOnly());
        assertTrue(context.toParseContext().isSetupInputVariableId("v0"));
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
    void from_doesNotAdvertiseBoxedInstanceMethodsForPrimitiveVariables() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, boolean.class,
                "return flag;", Collections.emptyMap(), "flag");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), true);
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);

        assertEquals("none\n", context.toCallableMemberText());
    }

    @Test
    void from_limitsCallableMembersToTheCompileTimeDeclaredType() throws Exception {
        Properties.LLM_POSTPROCESSING_CALLABLE_POLICY =
                Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED;
        DefaultTestCase test = new DefaultTestCase();
        MethodStatement statement = new MethodStatement(test,
                new GenericMethod(LlmPostProcessingPromptContextTest.class
                        .getDeclaredMethod("numberValue"), LlmPostProcessingPromptContextTest.class),
                null, Collections.emptyList());
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), BigDecimal.ZERO);
        result.setFinalScope(scope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);
        String callables = context.toCallableMemberText();

        assertFalse(callables.contains("signum()"), callables);
        assertFalse(callables.contains("java.math.BigDecimal"), callables);
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":1,\"assertions\":[{\"assertionId\":\"a0\","
                        + "\"kind\":\"EQUALS\",\"expected\":\"0\",\"actual\":\"v0.signum()\"}]}",
                context.toParseContext());
        assertTrue(parsed.getResponse().getAssertions().isEmpty());
        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD,
                parsed.getDiagnostics().get(0).getCode());
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
        assertTrue(context.toCallableMemberText().contains("members=size()I->int"),
                context.toCallableMemberText());
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

        assertTrue(callableText.contains("members=size()I->int"), callableText);
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
        assertEquals("c0", context.getCandidateFacts().get(0).getCandidateId());
        assertTrue(context.toCandidateFactText().contains("candidateId=c0"));
        assertTrue(context.toCandidateFactText().contains("source=v0"));
        assertTrue(context.toCandidateFactText().contains("refs=v0"));
        assertFalse(context.toCandidateFactText().contains("codeHint="));
        String canonicalFacts = context.toCandidateFactText(
                true, true, false, false, false);
        assertTrue(canonicalFacts.contains(
                "select={\"assertionId\":\"aN\",\"candidateId\":\"c0\"}"));
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

        LlmPostProcessingParseResult selected = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":1,\"assertions\":["
                        + "{\"assertionId\":\"a1\",\"candidateId\":\"c0\"}]}",
                context.toParseContext());
        assertEquals(1, selected.getResponse().getAssertions().size());
        assertEquals("7", selected.getResponse().getAssertions().get(0).getExpected());
    }

    @Test
    void toParseContextAssignsImplicitPreThrowPlacementToCandidate() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement value = new IntPrimitiveStatement(test, 7);
        test.addStatement(value);
        test.addStatement(new UninterpretedStatement(test,
                "throw new IllegalArgumentException();"));
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(value.getReturnValue());
        assertion.setValue(7);
        ExecutionResult executionResult = new ExecutionResult(test);
        executionResult.reportNewThrownException(1, new IllegalArgumentException("boom"));

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, executionResult, null, Collections.singletonList(assertion));
        LlmPostProcessingParseResult selected = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":3,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}",
                context.toParseContext());

        assertTrue(selected.getDiagnostics().isEmpty(), selected.getDiagnostics().toString());
        assertEquals(1, selected.getResponse().getAssertions().size());
        assertEquals(LlmPostProcessingResponse.AssertionSite.BEFORE_TRY,
                selected.getResponse().getAssertions().get(0).getSite());
        assertEquals("s0", selected.getResponse().getAssertions().get(0).getAfterStatementId());
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
        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS = 2000;
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

        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS =
                firstLine.length() + "truncatedCandidates=1\n".length();
        LlmPostProcessingPromptContext boundedContext = LlmPostProcessingPromptContext.from(
                test, null, Arrays.asList(nullAssertion, primitiveAssertion));
        String bounded = boundedContext.toCandidateFactText();

        assertEquals(firstLine + "truncatedCandidates=1\n", bounded);
        assertFalse(bounded.contains("kind=NullAssertion"));
    }

    @Test
    void candidateFactLimitKeepsAStableDiversePrefix() {
        Properties.LLM_POSTPROCESSING_MAX_CANDIDATE_FACTS = 1;
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);
        PrimitiveAssertion primitive = new PrimitiveAssertion();
        primitive.setSource(statement.getReturnValue());
        primitive.setValue(7);
        NullAssertion nonNull = new NullAssertion();
        nonNull.setSource(statement.getReturnValue());
        nonNull.setValue(false);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, Arrays.asList(nonNull, primitive));

        assertEquals(1, context.getCandidateFacts().size());
        assertEquals("c0", context.getCandidateFacts().get(0).getCandidateId());
    }

    @Test
    void unstableCandidateIsNotAdvertisedWhenStabilityLabelsAreActive() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);
        PrimitiveAssertion unstableAssertion = new PrimitiveAssertion();
        unstableAssertion.setSource(statement.getReturnValue());
        unstableAssertion.setValue(7);
        PrimitiveAssertion stableAssertion = new PrimitiveAssertion();
        stableAssertion.setSource(statement.getReturnValue());
        stableAssertion.setValue(8);
        ExecutionResult stabilityResult = new ExecutionResult(test);
        Scope stabilityScope = new Scope();
        stabilityScope.setObject(statement.getReturnValue(), 8);
        stabilityResult.setFinalScope(stabilityScope);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, stabilityResult,
                Arrays.asList(unstableAssertion, stableAssertion));
        String facts = context.toCandidateFactText(true, true, true, true, true);

        assertTrue(facts.contains("candidateId=c1"));
        assertTrue(facts.contains("expected=\"8\""));
        assertTrue(facts.contains("stability=STABLE"));
        assertTrue(facts.contains(
                "select={\"assertionId\":\"aN\",\"candidateId\":\"c1\"}"));
        assertTrue(facts.endsWith("unstableCandidatesOmitted=1\n"));
        assertFalse(facts.contains("expected=\"7\""));
        assertFalse(facts.contains("candidateId=c0"));

        LlmPostProcessingParseResult selected = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":3,\"assertions\":["
                        + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\","
                        + "\"placement\":{\"site\":\"END_OF_TEST\"}}]}",
                context.toParseContext());
        assertTrue(selected.getResponse().getAssertions().isEmpty());
        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.UNKNOWN_ID,
                selected.getDiagnostics().get(0).getCode());

        LlmPostProcessingParseResult stableSelected = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":3,\"assertions\":["
                        + "{\"assertionId\":\"a1\",\"candidateId\":\"c1\","
                        + "\"placement\":{\"site\":\"END_OF_TEST\"}}]}",
                context.toParseContext());
        assertEquals(1, stableSelected.getResponse().getAssertions().size());
    }

    @Test
    void candidateUsingPublicNestedTypeInNonPublicOuterIsNotSnippetAssertable() {
        Properties.LLM_POSTPROCESSING_PROMPT_VARIANT =
                Properties.LlmPostProcessingPromptVariant.P12_ORACLE_CONTEXT_V2;
        Properties.TARGET_CLASS = "org.evosuite.llm.postprocess.ExampleTarget";
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(
                test, PackagePrivateOuter.PublicNestedType.class,
                "return nested;", Collections.emptyMap(), "nested");
        test.addStatement(statement);
        NullAssertion assertion = new NullAssertion();
        assertion.setSource(statement.getReturnValue());
        assertion.setValue(false);

        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                test, null, null, Collections.singletonList(assertion));
        String facts = context.toCandidateFactText(true, true, true, true, true);

        assertTrue(facts.contains("assertable=false"));
        assertTrue(facts.contains("reason=INACCESSIBLE_TYPE"));
        assertTrue(facts.contains("selectable=false"));
        assertFalse(facts.contains("candidateId="));
        assertFalse(facts.contains("select={"));
    }

    @Test
    void callableCapPrioritizesReceiverBindingsAndObservedValues() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, String.class,
                "return value;", Collections.emptyMap(), "value");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), "text");
        result.setFinalScope(scope);
        LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(test, result);
        String full = context.toCallableMemberText();
        int firstOwner = full.indexOf("owner=");
        assertTrue(firstOwner > 0);

        Properties.LLM_POSTPROCESSING_MAX_CALLABLE_CHARS = firstOwner;
        LlmPostProcessingPromptContext boundedContext = LlmPostProcessingPromptContext.from(test, result);
        String bounded = boundedContext.toCallableMemberText();

        assertTrue(bounded.contains("receivers:"));
        assertTrue(bounded.contains("observed:"));
        assertFalse(bounded.contains("owner="));
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
        LlmPostProcessingPromptContext boundedContext = LlmPostProcessingPromptContext.from(test);
        String bounded = boundedContext.toObservationText();

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

    static class PackagePrivateOuter {
        public static class PublicNestedType {
            // Public itself, but inaccessible through its non-public outer type.
        }
    }

    public static Number numberValue() {
        return BigDecimal.ZERO;
    }
}
