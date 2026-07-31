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

import org.evosuite.junit.naming.methods.TestNameGenerationStrategy;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestPresentationMetadata;

/**
 * Gives accepted unified LLM test names precedence over the configured fallback.
 */
public final class LlmPostProcessingTestNameStrategy implements TestNameGenerationStrategy {

    private final TestNameGenerationStrategy fallback;

    public LlmPostProcessingTestNameStrategy(TestNameGenerationStrategy fallback) {
        this.fallback = fallback;
    }

    @Override
    public String getName(TestCase test) {
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        if (metadata != null && metadata.getTestName() != null) {
            return metadata.getTestName();
        }
        return fallback.getName(test);
    }
}
