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
 * Raised when an LLM provider's HTTP endpoint returns a non-2xx status code.
 * Carries the status code and a truncated copy of the response body so retry
 * classification and error logging in {@code LlmService} can inspect them
 * without holding onto a possibly large payload.
 */
public class LlmHttpException extends IOException {

    private static final int MAX_BODY_CHARS = 2048;

    private final int statusCode;
    private final String responseBody;

    public LlmHttpException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + truncate(responseBody));
        this.statusCode = statusCode;
        this.responseBody = truncate(responseBody);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
