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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.llm.postprocess.LlmPostProcessingResponse;

/**
 * Renders canonical unified LLM assertion kinds for the configured test format.
 */
public interface CanonicalAssertionRenderer {

    String render(LlmPostProcessingResponse.AssertionKind kind,
                  String expected,
                  String actual,
                  String delta);

    String render(LlmPostProcessingResponse.AssertionKind kind,
                  String expected,
                  String actual,
                  String delta,
                  boolean arrayEquality);

    static CanonicalAssertionRenderer forConfiguredFormat() {
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT3) {
            return JUnit3CanonicalAssertionRenderer.INSTANCE;
        }
        return JUnit4Or5CanonicalAssertionRenderer.INSTANCE;
    }
}
