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
package org.evosuite.llm.search;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemCardFormatterTest {

    @Test
    void formatsInvocationGapIndirectBarrierWithDirectInvocationActionHint() {
        String formatted = ProblemCardFormatter.format(Collections.singletonList(card(
                ProblemCardType.INDIRECT_REACHABILITY_BARRIER,
                "Indirect reachability barrier before direct target invocation: com.example.Target")));

        assertTrue(formatted.contains("append the missing direct target invocation"),
                "INDIRECT_REACHABILITY_BARRIER prompt hints should now mention the missing direct invocation");
    }

    @Test
    void formatsStateDiversificationGapWithDiversificationActionHint() {
        String formatted = ProblemCardFormatter.format(Collections.singletonList(card(
                ProblemCardType.STATE_DIVERSIFICATION_GAP,
                "Direct executions stay in one regime: com.example.Target.work()")));

        assertTrue(formatted.contains("explore orthogonal regimes"),
                "STATE_DIVERSIFICATION_GAP should steer the prompt toward regime diversification");
        assertTrue(formatted.contains("assertions are unnecessary"),
                "STATE_DIVERSIFICATION_GAP should follow stagnation-mode no-assertion guidance");
    }

    @Test
    void everyCardTypeExposesAStableActionHint() {
        for (ProblemCardType type : ProblemCardType.values()) {
            String hint = type.getActionHint();
            assertTrue(hint != null && !hint.isEmpty(),
                    "Every card type must define an action hint so new types don't silently "
                            + "ship without LLM guidance: " + type);
        }
    }

    @Test
    void resolvesConcreteExampleToNumberedExistingTest() {
        TestCase example = testCase("working-prefix");
        ProblemCard anchored = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("Method not reached")
                .evidence(Collections.singletonList("zero executions"))
                .concreteExample(example, "Reusable successful prefix")
                .build();

        String formatted = ProblemCardFormatter.format(
                Collections.singletonList(anchored), Collections.singletonList(example));

        assertTrue(formatted.contains("Reusable successful prefix: see Existing test #1."));
        assertFalse(formatted.contains("(inline, first 8 statements)"));
    }

    @Test
    void inlinesAtMostEightStatementsWhenConcreteExampleIsNotSelected() {
        DefaultTestCase example = new DefaultTestCase();
        for (int i = 0; i < 10; i++) {
            example.addStatement(new StringPrimitiveStatement(example, "statement-" + i));
        }
        ProblemCard anchored = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("Method not reached")
                .evidence(Collections.singletonList("zero executions"))
                .concreteExample(example, "Reusable successful prefix")
                .build();

        String formatted = ProblemCardFormatter.format(
                Collections.singletonList(anchored), Collections.<TestCase>emptyList());

        assertTrue(formatted.contains("(inline, first 8 statements)"));
        assertTrue(formatted.contains("statement-7"));
        assertFalse(formatted.contains("statement-8"));
        assertTrue(formatted.contains("// ... (truncated)"));
    }

    private static ProblemCard card(ProblemCardType type, String title) {
        return ProblemCard.builder(type)
                .title(title)
                .evidence(Collections.singletonList("evidence"))
                .relatedGoals(Collections.emptyList())
                .impact(0.7)
                .blockage(0.8)
                .confidence(0.9)
                .build();
    }

    private static TestCase testCase(String marker) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, marker));
        return test;
    }
}
