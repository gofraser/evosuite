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

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidatedEditPlanTest {

    @Test
    void planOwnsAnIndependentResponseSnapshot() {
        LlmPostProcessingResponse decoded = new LlmPostProcessingResponse();
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
