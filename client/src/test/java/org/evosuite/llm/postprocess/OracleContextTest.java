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
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.prompt.PromptResult;
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
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OracleContextTest {

    private int originalMaxLiteralChars;
    private int originalMaxObservationChars;
    private int originalMaxCandidateChars;
    private int originalMaxCandidateFacts;
    private int originalMaxCallableChars;
    private int originalMaxCollectionElements;
    private String originalTargetClass;
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
        Properties.LLM_POSTPROCESSING_CALLABLE_POLICY = originalCallablePolicy;
    }

    @Test
    void captureCreatesAnImmutableFactSnapshot() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        OracleContext snapshot = OracleContext.from(test, null,
                java.util.Collections.emptyList(), PostProcessingOptions.fromProperties());

        assertEquals(test.size(), snapshot.getStatements().size());
        assertEquals(test.size(), snapshot.getObservations().size());
        assertEquals(0, snapshot.getCandidateFacts().size());
        assertEquals(java.util.Collections.singleton("s0"),
                snapshot.getReferences().getStatementIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getStatements().clear());
    }

    @Test
    void productionRendererConsumesTheSnapshotBoundary() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        OracleContext snapshot = OracleContext.from(test, null,
                java.util.Collections.emptyList(), PostProcessingOptions.fromProperties());
        PostProcessingOptions options = PostProcessingOptions.fromProperties();

        PromptResult throughBuilder = PostProcessingPromptRenderer.build(
                snapshot, false, options);
        PromptResult throughSnapshot = PostProcessingPromptRenderer.build(
                snapshot, false, options);

        assertEquals(throughBuilder.getMessages().get(1).getContent(),
                throughSnapshot.getMessages().get(1).getContent());
    }

    @Test
    void capturePreservesTheDeterministicCandidateOrder() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(test, 7);
        test.addStatement(statement);

        NullAssertion nullAssertion = new NullAssertion();
        nullAssertion.setSource(statement.getReturnValue());
        nullAssertion.setValue(false);
        PrimitiveAssertion primitiveAssertion = new PrimitiveAssertion();
        primitiveAssertion.setSource(statement.getReturnValue());
        primitiveAssertion.setValue(7);

        OracleContext snapshot = OracleContext.from(
                test, null, Arrays.asList(nullAssertion, primitiveAssertion),
                PostProcessingOptions.fromProperties());

        assertEquals(Arrays.asList("c0", "c1"), candidateIds(snapshot));
        assertEquals(Arrays.asList("PrimitiveAssertion", "NullAssertion"), candidateKinds(snapshot));
    }

    @Test
    void captureDoesNotExposeStructuredValueSummariesAsSelectableCandidates() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement arrayStatement = new UninterpretedStatement(test, String[].class,
                "return values;", Collections.emptyMap(), "values");
        UninterpretedStatement collectionStatement = new UninterpretedStatement(test, List.class,
                "return values;", Collections.emptyMap(), "values");
        UninterpretedStatement mapStatement = new UninterpretedStatement(test, Map.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(arrayStatement);
        test.addStatement(collectionStatement);
        test.addStatement(mapStatement);

        PrimitiveAssertion arrayAssertion = new PrimitiveAssertion();
        arrayAssertion.setSource(arrayStatement.getReturnValue());
        arrayAssertion.setValue(new String[]{"a", "b"});
        PrimitiveAssertion collectionAssertion = new PrimitiveAssertion();
        collectionAssertion.setSource(collectionStatement.getReturnValue());
        collectionAssertion.setValue(Arrays.asList("a", "b"));
        PrimitiveAssertion mapAssertion = new PrimitiveAssertion();
        mapAssertion.setSource(mapStatement.getReturnValue());
        Map<String, Integer> values = new HashMap<>();
        values.put("a", 1);
        mapAssertion.setValue(values);

        OracleContext context = OracleContext.from(test, null,
                Arrays.asList(arrayAssertion, collectionAssertion, mapAssertion),
                PostProcessingOptions.fromProperties());

        assertEquals(3, context.getCandidateFacts().size());
        assertTrue(context.getCandidateFacts().stream()
                .allMatch(fact -> fact.getCandidateId() == null));
    }

    @Test
    void captureExposesStableStatementIdsTypesAndAnnotatedCode() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new UninterpretedStatement(test, void.class, "System.gc();"));
        test.addStatement(new StringPrimitiveStatement(test, "value"));
        OracleContext context = OracleContext.from(test, null, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        assertEquals("s0", context.getStatements().get(0).getStatementId());
        assertEquals("v0", context.getStatements().get(0).getVariableId());
        assertEquals("int", context.getStatements().get(0).getDeclaredType());
        assertEquals("s1", context.getStatements().get(1).getStatementId());
        assertEquals(null, context.getStatements().get(1).getVariableId());
        assertEquals("java.lang.String", context.getStatements().get(2).getDeclaredType());
        assertTrue(PostProcessingPromptRenderer.annotatedText(context).contains("s0 v0 : int |"));
        assertTrue(PostProcessingPromptRenderer.annotatedText(context).contains("System.gc();"));
    }

    @Test
    void captureRendersSpecialFloatingPointConstants() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new DoublePrimitiveStatement(test, Double.POSITIVE_INFINITY));
        test.addStatement(new DoublePrimitiveStatement(test, Double.NEGATIVE_INFINITY));
        test.addStatement(new DoublePrimitiveStatement(test, Double.NaN));
        test.addStatement(new FloatPrimitiveStatement(test, Float.POSITIVE_INFINITY));
        test.addStatement(new FloatPrimitiveStatement(test, Float.NEGATIVE_INFINITY));
        test.addStatement(new FloatPrimitiveStatement(test, Float.NaN));
        OracleContext context = OracleContext.from(test, null, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        String observations = PostProcessingPromptRenderer.observationText(
                context, null, PostProcessingOptions.fromProperties());
        assertTrue(observations.contains("Double.POSITIVE_INFINITY"));
        assertTrue(observations.contains("Double.NEGATIVE_INFINITY"));
        assertTrue(observations.contains("Double.NaN"));
        assertTrue(observations.contains("Float.POSITIVE_INFINITY"));
        assertTrue(observations.contains("Float.NEGATIVE_INFINITY"));
        assertTrue(observations.contains("Float.NaN"));
    }

    @Test
    void captureClassifiesConstructedArraysAsSetupInputs() {
        DefaultTestCase test = new DefaultTestCase();
        ArrayStatement statement = new ArrayStatement(test, String[].class, 0);
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new String[0]);
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        assertEquals(1, context.getObservations().size());
        assertEquals("INPUT", context.getObservations().get(0).getProvenance());
        assertTrue(context.toParseContext().isSetupInputVariableId("v0"));
    }

    @Test
    void captureUsesRuntimeValuesForSutObservationsAndCompileTimeCallables() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, String.class,
                "return value;", Collections.emptyMap(), "value");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), "done");
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        assertEquals("java.lang.String", context.getStatements().get(0).getRuntimeType());
        assertEquals("SUT_RETURN", context.getObservations().get(0).getProvenance());
        assertEquals("\"done\"", context.getObservations().get(0).getValue());
        String callables = PostProcessingPromptRenderer.callableMemberText(
                context, null, PostProcessingOptions.fromProperties());
        assertTrue(callables.contains("owner=java.lang.String"), callables);
        assertTrue(callables.contains("length()I->int"), callables);
        assertTrue(context.getObservations().get(0).getValue().contains("done"));
    }

    @Test
    void captureDoesNotAdvertiseBoxedMembersForPrimitiveVariables() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, boolean.class,
                "return flag;", Collections.emptyMap(), "flag");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), true);
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        assertEquals("none\n", PostProcessingPromptRenderer.callableMemberText(
                context, null, PostProcessingOptions.fromProperties()));
    }

    @Test
    void captureBoundsCollectionsAndToleratesUnavailableElements() {
        Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS = 2;
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, List.class,
                "return values;", Collections.emptyMap(), "values");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), Arrays.asList("a", "b", "c"));
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());

        assertEquals("collection size=3 elements=[\"a\", \"b\"]",
                context.getObservations().get(0).getValue());
        assertFalse(context.getObservations().get(0).isComplete());

        Scope unavailableScope = new Scope();
        unavailableScope.setObject(statement.getReturnValue(), new AbstractList<String>() {
            @Override public String get(int index) { return "value"; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<String> iterator() { return null; }
        });
        ExecutionResult unavailable = new ExecutionResult(test);
        unavailable.setFinalScope(unavailableScope);
        OracleContext unavailableContext = OracleContext.from(test, unavailable,
                Collections.emptyList(), PostProcessingOptions.fromProperties());
        assertEquals("collection size=1 elements unavailable",
                unavailableContext.getObservations().get(0).getValue());
        assertFalse(unavailableContext.getObservations().get(0).isComplete());
    }

    @Test
    void captureSummarizesMapsAndEnumsWithoutThrowing() {
        DefaultTestCase mapTest = new DefaultTestCase();
        UninterpretedStatement mapStatement = new UninterpretedStatement(mapTest, Map.class,
                "return values;", Collections.emptyMap(), "values");
        mapTest.addStatement(mapStatement);
        ExecutionResult mapResult = new ExecutionResult(mapTest);
        Scope mapScope = new Scope();
        Map<String, Integer> values = new HashMap<>();
        values.put("a", 1);
        mapScope.setObject(mapStatement.getReturnValue(), values);
        mapResult.setFinalScope(mapScope);
        OracleContext mapContext = OracleContext.from(mapTest, mapResult,
                Collections.emptyList(), PostProcessingOptions.fromProperties());
        assertTrue(mapContext.getObservations().get(0).getValue().startsWith("map size=1"));
        assertTrue(PostProcessingPromptRenderer.callableMemberText(
                mapContext, null, PostProcessingOptions.fromProperties())
                .contains("containsKey(Ljava/lang/Object;)Z"));

        DefaultTestCase enumTest = new DefaultTestCase();
        UninterpretedStatement enumStatement = new UninterpretedStatement(enumTest,
                java.util.concurrent.TimeUnit.class, "return unit;", Collections.emptyMap(), "unit");
        enumTest.addStatement(enumStatement);
        ExecutionResult enumResult = new ExecutionResult(enumTest);
        Scope enumScope = new Scope();
        enumScope.setObject(enumStatement.getReturnValue(), java.util.concurrent.TimeUnit.SECONDS);
        enumResult.setFinalScope(enumScope);
        OracleContext enumContext = OracleContext.from(enumTest, enumResult,
                Collections.emptyList(), PostProcessingOptions.fromProperties());
        assertEquals("java.util.concurrent.TimeUnit.SECONDS",
                enumContext.getObservations().get(0).getValue());
    }

    @Test
    void captureUsesCuratedImmutableCallables() {
        DefaultTestCase test = new DefaultTestCase();
        UninterpretedStatement statement = new UninterpretedStatement(test, BigDecimal.class,
                "return amount;", Collections.emptyMap(), "amount");
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), new BigDecimal("-3.50"));
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());
        String callables = PostProcessingPromptRenderer.callableMemberText(
                context, null, PostProcessingOptions.fromProperties());

        assertTrue(callables.contains("owner=java.math.BigDecimal"), callables);
        assertTrue(callables.contains("signum()I->int"), callables);
        assertTrue(callables.contains("compareTo(Ljava/math/BigDecimal;)I->int"), callables);
    }

    @Test
    void captureLimitsCallableMembersToDeclaredType() throws Exception {
        Properties.LLM_POSTPROCESSING_CALLABLE_POLICY =
                Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED;
        DefaultTestCase test = new DefaultTestCase();
        MethodStatement statement = new MethodStatement(test,
                new GenericMethod(OracleContextTest.class.getDeclaredMethod("numberValue"),
                        OracleContextTest.class), null, Collections.emptyList());
        test.addStatement(statement);
        ExecutionResult result = new ExecutionResult(test);
        Scope scope = new Scope();
        scope.setObject(statement.getReturnValue(), BigDecimal.ZERO);
        result.setFinalScope(scope);
        OracleContext context = OracleContext.from(test, result, Collections.emptyList(),
                PostProcessingOptions.fromProperties());
        String callables = PostProcessingPromptRenderer.callableMemberText(
                context, null, PostProcessingOptions.fromProperties());
        assertFalse(callables.contains("signum()"), callables);
        assertFalse(callables.contains("java.math.BigDecimal"), callables);
    }

    @Test
    void capturePublishesCandidateKeysAndHonorsCandidateLimits() {
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
        OracleContext context = OracleContext.from(test, null,
                Arrays.asList(nonNull, primitive), PostProcessingOptions.fromProperties());

        assertEquals(1, context.getCandidateFacts().size());
        assertEquals("c0", context.getCandidateFacts().get(0).getCandidateId());
        assertNotNull(context.getCandidateFacts().get(0).getAssertionKey());
        String selected = "{\"schemaVersion\":2,\"assertions\":["
                + "{\"assertionId\":\"a0\",\"candidateId\":\"c0\"}]}";
        LlmPostProcessingParseResult parsed = LlmPostProcessingResponseParser.parse(
                selected, context.toParseContext());
        assertEquals(1, parsed.getResponse().getAssertions().size());
    }

    @Test
    void captureMarksTruncatedPrimitiveAndObservationBudgets() {
        Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 5;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, "abcdef"));
        test.addStatement(new IntPrimitiveStatement(test, 7));
        OracleContext context = OracleContext.from(test, null, Collections.emptyList(),
                PostProcessingOptions.fromProperties());
        assertFalse(context.getObservations().get(0).isComplete());
        assertTrue(PostProcessingPromptRenderer.observationText(
                context, null, PostProcessingOptions.fromProperties()).contains("note=truncated"));

        String first = PostProcessingPromptRenderer.observationText(
                context, null, PostProcessingOptions.fromProperties());
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = first.indexOf('\n') + 1;
        String bounded = PostProcessingPromptRenderer.observationText(
                context, null, PostProcessingOptions.fromProperties());
        assertFalse(bounded.contains("s1"));
    }

    public static Number numberValue() {
        return BigDecimal.ZERO;
    }

    private static List<String> candidateIds(OracleContext context) {
        List<String> ids = new ArrayList<>();
        for (OracleContext.CandidateFact fact : context.getCandidateFacts()) {
            ids.add(fact.getCandidateId());
        }
        return ids;
    }

    private static List<String> candidateKinds(OracleContext context) {
        List<String> kinds = new ArrayList<>();
        for (OracleContext.CandidateFact fact : context.getCandidateFacts()) {
            kinds.add(fact.getKind());
        }
        return kinds;
    }
}
