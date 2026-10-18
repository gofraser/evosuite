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
package org.evosuite.llm.provider;

import java.io.IOException;

/**
 * Raised when an LLM provider answers with a 2xx HTTP status but a body that
 * does not conform to the shape a chat completion response is expected to
 * have: missing or non-textual message content, an embedded error object, a
 * usage block with the wrong type, and so on. Also raised when the body
 * cannot be parsed as JSON at all despite the 2xx status.
 *
 * <p>A malformed 2xx response is most often the symptom of a proxy or
 * gateway sitting in front of the real provider — returning a truncated or
 * placeholder body while still reporting success — rather than a genuine
 * response from the model, so {@code LlmService} treats it as retryable in
 * the same way it treats a transport failure.
 */
public class LlmProtocolException extends IOException {

    private static final int MAX_BODY_CHARS = 2048;

    public LlmProtocolException(String message) {
        super(message);
    }

    public LlmProtocolException(String message, String offendingBody) {
        super(withBody(message, offendingBody));
    }

    private static String withBody(String message, String offendingBody) {
        if (offendingBody == null || offendingBody.isEmpty()) {
            return message;
        }
        String truncated = offendingBody.length() > MAX_BODY_CHARS
                ? offendingBody.substring(0, MAX_BODY_CHARS) : offendingBody;
        return message + ": " + truncated;
    }
}
