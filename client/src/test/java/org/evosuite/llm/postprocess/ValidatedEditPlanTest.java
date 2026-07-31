/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidatedEditPlanTest {

    @Test
    void planOwnsAnIndependentResponseSnapshot() {
        LlmPostProcessingResponse decoded = new LlmPostProcessingResponse(2);
        decoded.setTestName("beforeValidation");
        decoded.addAssertion(new LlmPostProcessingResponse.AssertionProposal(
                "a0", LlmPostProcessingResponse.AssertionKind.TRUE,
                null, "v0", null, "purpose"));

        ValidatedEditPlan plan = ValidatedEditPlan.create(decoded, Collections.emptyList());
        decoded.setTestName("changedAfterValidation");
        LlmPostProcessingResponse exposed = plan.getResponse();
        exposed.setTestName("changedThroughGetter");

        assertEquals("beforeValidation", plan.getResponse().getTestName());
        assertEquals(1, plan.getResponse().getAssertions().size());
    }
}
