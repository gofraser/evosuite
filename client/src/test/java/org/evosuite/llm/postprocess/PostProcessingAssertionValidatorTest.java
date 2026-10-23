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
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostProcessingAssertionValidatorTest {

    private static LlmPostProcessor.EvaluationOutcome evaluate(
            LlmPostProcessingResponse.AssertionKind kind, String actual, Object value) {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference result = test.addStatement(new UninterpretedStatement(test, String.class,
                "String __tmp = null;", Collections.<String, VariableReference>emptyMap(), "__tmp"));
        Scope scope = new Scope();
        scope.setObject(result, value);
        LlmPostProcessingResponse.AssertionProposal proposal =
                new LlmPostProcessingResponse.AssertionProposal(
                        "a0", kind, null, actual, null, null);
        return PostProcessingAssertionValidator.defaultEvaluationRunner().evaluate(proposal, test,
                LlmPostProcessingReferences.from(test), scope, PostProcessingOptions.fromProperties());
    }

    @Test
    void assertionThatThrowsWhenEvaluatedIsAnObservedFailure() {
        LlmPostProcessor.EvaluationOutcome outcome = evaluate(
                LlmPostProcessingResponse.AssertionKind.TRUE, "v0.isEmpty()", null);

        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                outcome.diagnosticCode());
    }

    @Test
    void assertionThatDoesNotCompileIsACompileFailure() {
        LlmPostProcessor.EvaluationOutcome outcome = evaluate(
                LlmPostProcessingResponse.AssertionKind.TRUE, "v0.isEmpty( +", "x");

        assertEquals(LlmPostProcessingParseResult.DiagnosticCode.COMPILE, outcome.diagnosticCode());
    }

    @Test
    void acceptsAnAssertionThatHolds() {
        assertTrue(evaluate(LlmPostProcessingResponse.AssertionKind.TRUE, "v0.isEmpty()", "")
                .isAccepted());
    }
}
