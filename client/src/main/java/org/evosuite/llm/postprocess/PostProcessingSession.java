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

import org.evosuite.testcase.TestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit lifecycle state handed from the phase to final suite reconciliation. */
final class PostProcessingSession {
    final List<LlmPostProcessor.AppliedAssertionTrace> appliedAssertions = new ArrayList<>();
    final Map<String, Integer> compileRemovedAssertions = new HashMap<>();

    void clear() {
        appliedAssertions.clear();
        compileRemovedAssertions.clear();
    }

    void recordCompileRemoved(Collection<TestCase> tests) {
        if (tests == null) {
            return;
        }
        for (TestCase test : tests) {
            if (test == null) {
                continue;
            }
            for (org.evosuite.assertion.Assertion assertion : test.getAssertions()) {
                if (assertion instanceof org.evosuite.assertion.TemplateCodeAssertion) {
                    org.evosuite.assertion.TemplateCodeAssertion template =
                            (org.evosuite.assertion.TemplateCodeAssertion) assertion;
                    String signature = LlmPostProcessor.assertionSignatureForSession(template);
                    Integer count = compileRemovedAssertions.get(signature);
                    compileRemovedAssertions.put(signature, count == null ? 1 : count + 1);
                }
            }
        }
    }

}
