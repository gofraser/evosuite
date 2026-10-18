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
import java.util.Locale;

/**
 * Raised when an LLM provider answers with a 2xx HTTP status but the body
 * itself carries an {@code error} object — e.g. OpenAI's
 * {@code {"error":{"type":"invalid_request_error",...}}} or Anthropic's
 * {@code {"type":"error","error":{"type":"overloaded_error",...}}}.
 *
 * <p>Unlike {@link LlmProtocolException} (a body that does not conform to
 * the expected shape at all, most likely a proxy/gateway hiccup), this is the
 * provider itself explicitly reporting a failure. Some of those failures are
 * transient (rate limiting, the model being overloaded) while others are
 * permanent (an invalid request, an unknown model, a bad API key) and would
 * just fail again identically on retry. {@link #isRetryable()} distinguishes
 * the two so {@code LlmService} does not burn its retry budget on a request
 * that can never succeed.
 */
public class LlmProviderErrorException extends IOException {

    private static final int MAX_BODY_CHARS = 2048;

    /**
     * Substrings (case-insensitive) of a provider's error {@code type} or
     * {@code code} that mark the failure as transient. Keep this the only
     * place this list is defined; anything not matching here means the
     * request itself needs to change before retrying could help.
     */
    private static final String[] RETRYABLE_KEYWORDS = {
            "rate_limit", "overloaded", "server_error", "api_error", "timeout", "temporarily", "unavailable"
    };

    private final String type;
    private final String code;
    private final String providerMessage;
    private final String responseBody;

    public LlmProviderErrorException(String type, String code, String providerMessage, String responseBody) {
        super(buildMessage(type, code, providerMessage, responseBody));
        this.type = type;
        this.code = code;
        this.providerMessage = providerMessage;
        this.responseBody = truncate(responseBody);
    }

    private static String buildMessage(String type, String code, String providerMessage, String responseBody) {
        StringBuilder message = new StringBuilder("provider reported an error");
        if (type != null) {
            message.append(" type=").append(type);
        }
        if (code != null) {
            message.append(" code=").append(code);
        }
        if (providerMessage != null) {
            message.append(": ").append(providerMessage);
        }
        String truncated = truncate(responseBody);
        if (truncated != null && !truncated.isEmpty()) {
            message.append(" [").append(truncated).append(']');
        }
        return message.toString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public String getProviderMessage() {
        return providerMessage;
    }

    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Returns true only if the provider's {@code type} or {@code code} names
     * a transient condition (see {@link #RETRYABLE_KEYWORDS}); false for a
     * permanent error (or one with neither field set), which retrying cannot
     * fix.
     */
    public boolean isRetryable() {
        return containsRetryableKeyword(type) || containsRetryableKeyword(code);
    }

    private static boolean containsRetryableKeyword(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String keyword : RETRYABLE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
