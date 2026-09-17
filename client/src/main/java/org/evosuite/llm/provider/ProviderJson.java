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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * The response-parsing rules every JSON chat provider shares: the body must
 * be a JSON object, and a provider-reported {@code error} object is surfaced
 * as an {@link LlmProviderErrorException} so {@link org.evosuite.llm.LlmService}
 * can tell a transient failure (rate limit, overload) apart from a permanent
 * one (invalid request, unknown model) instead of treating every error object
 * as retryable. Provider-specific shape checks stay in the provider classes.
 */
final class ProviderJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderJson() {
    }

    /**
     * Parses {@code responseBody} as a JSON object, throwing {@link
     * LlmProtocolException} for a malformed body and {@link
     * LlmProviderErrorException} when the body is the provider's own error report.
     *
     * @param providerName used in exception messages only
     */
    static JsonNode parseObject(String responseBody, String providerName) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmProtocolException("could not parse " + providerName + " response as JSON ("
                    + e.getMessage() + ")", responseBody);
        }
        if (root == null || !root.isObject()) {
            throw new LlmProtocolException(providerName + " response is not a JSON object", responseBody);
        }
        JsonNode error = root.path("error");
        if (error.isObject()) {
            throw new LlmProviderErrorException(textOrNull(error, "type"), textOrNull(error, "code"),
                    textOrNull(error, "message"), responseBody);
        }
        return root;
    }

    /** The numeric field {@code field} of {@code parent}, 0 when absent or null, protocol error when non-numeric. */
    static int requireNumericIfPresent(JsonNode parent, String field, String providerName, String responseBody)
            throws LlmProtocolException {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (!value.isNumber()) {
            throw new LlmProtocolException(providerName + " response field '" + field + "' is not numeric",
                    responseBody);
        }
        return value.asInt();
    }

    static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
