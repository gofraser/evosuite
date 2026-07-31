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

import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OracleContextTest {

    @Test
    void captureCreatesAnImmutableFactSnapshot() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        OracleContext snapshot = OracleContext.from(test, null, null,
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
        OracleContext snapshot = OracleContext.from(test, null, null,
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
                test, null, null, Arrays.asList(nullAssertion, primitiveAssertion),
                PostProcessingOptions.fromProperties());

        assertEquals(Arrays.asList("c0", "c1"), candidateIds(snapshot));
        assertEquals(Arrays.asList("PrimitiveAssertion", "NullAssertion"), candidateKinds(snapshot));
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
