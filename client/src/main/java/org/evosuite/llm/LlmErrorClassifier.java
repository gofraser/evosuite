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
package org.evosuite.llm;

import org.evosuite.llm.provider.LlmHttpException;
import org.evosuite.llm.provider.LlmProtocolException;
import org.evosuite.llm.provider.LlmProviderErrorException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the failures of an LLM provider call: whether a retry can help,
 * whether the configured model was rejected, and a readable message for the log.
 */
final class LlmErrorClassifier {

    private static final Map<Integer, Pattern> HTTP_CODE_PATTERNS;

    static {
        Map<Integer, Pattern> map = new HashMap<>();
        for (int code : new int[]{400, 401, 403, 404, 422, 429, 500, 502, 503, 504}) {
            map.put(code, Pattern.compile(".*\\b" + code + "\\b.*", Pattern.DOTALL));
        }
        HTTP_CODE_PATTERNS = Collections.unmodifiableMap(map);
    }

    private static final Pattern JSON_ERROR_MESSAGE =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern NESTED_ERROR_VALUE =
            Pattern.compile("'error'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)'");

    private static final Pattern INVALID_MODEL_PATTERN =
            Pattern.compile("(?i)invalid model|model.{0,20}not found|does not exist");

    private LlmErrorClassifier() {
    }

    /** Strips the executor wrappers off a failure, so that the provider's own exception is classified. */
    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }

        // Typed provider failures decide first, before any text is inspected:
        // their messages carry the response body, and so do the messages of
        // wrappers built from them, so the text heuristics below would
        // otherwise let a provider-specific body override the real signal.
        for (Throwable cause : causalChain(error)) {
            if (cause instanceof LlmHttpException) {
                // The status code is authoritative for an HTTP failure.
                return isRetryableHttpStatus(((LlmHttpException) cause).getStatusCode());
            }
            if (cause instanceof LlmProviderErrorException) {
                // The provider explicitly reported an error object in a 2xx
                // body; its own type/code decide whether this is transient.
                return ((LlmProviderErrorException) cause).isRetryable();
            }
            if (cause instanceof LlmProtocolException) {
                // A 2xx response with a malformed body is most likely a
                // proxy/gateway hiccup rather than a genuine model failure.
                return true;
            }
        }

        boolean retryableSignal = false;
        for (Throwable cause : causalChain(error)) {
            if (isExplicitlyNonRetryable(cause)) {
                return false;
            }
            if (isExplicitlyRetryable(cause)) {
                retryableSignal = true;
            }
        }
        return retryableSignal;
    }

    /**
     * OpenAI documents 408 (request timeout), 409 (conflict), and 429 (rate
     * limit) as retryable in addition to every 5xx; Anthropic's 529
     * ("overloaded") falls under that same 5xx umbrella. 425 (Too Early) is
     * included alongside 409 since both simply mean "resend this request
     * later". Every other 4xx means the request itself needs to change
     * before retrying can help.
     */
    private static boolean isRetryableHttpStatus(int status) {
        if (status == 408 || status == 409 || status == 425 || status == 429) {
            return true;
        }
        return status >= 500 && status < 600;
    }

    /**
     * Text-based fallback for failures that did not arrive as an
     * {@link LlmHttpException}, e.g. transport failures from the underlying
     * {@code HttpURLConnection} or a provider client rejecting the response
     * before an exception type carrying a status code is available.
     */
    private static boolean isExplicitlyRetryable(Throwable cause) {
        if (cause instanceof TimeoutException
                || cause instanceof SocketTimeoutException
                || cause instanceof ConnectException
                || cause instanceof SocketException
                || cause instanceof InterruptedIOException) {
            return true;
        }

        String text = combinedText(cause);
        return containsHttpCode(text, 429)
                || containsHttpCode(text, 500)
                || containsHttpCode(text, 502)
                || containsHttpCode(text, 503)
                || containsHttpCode(text, 504)
                || text.contains("rate limit")
                || text.contains("too many requests")
                || text.contains("service unavailable")
                || text.contains("gateway timeout")
                || text.contains("temporarily unavailable")
                || text.contains("timeout")
                || text.contains("timed out")
                || text.contains("transient");
    }

    private static boolean isExplicitlyNonRetryable(Throwable cause) {
        String text = combinedText(cause);
        return containsHttpCode(text, 400)
                || containsHttpCode(text, 401)
                || containsHttpCode(text, 403)
                || containsHttpCode(text, 404)
                || containsHttpCode(text, 422)
                || text.contains("invalid api key")
                || text.contains("unauthorized")
                || text.contains("forbidden")
                || text.contains("authentication")
                || text.contains("model not found")
                || text.contains("bad request")
                || text.contains("invalid request")
                || text.contains("malformed");
    }

    private static String combinedText(Throwable throwable) {
        String className = throwable.getClass().getName();
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        return (className + " " + message).toLowerCase();
    }

    private static boolean containsHttpCode(String text, int code) {
        Pattern pattern = HTTP_CODE_PATTERNS.get(code);
        if (pattern == null) {
            return text.matches(".*\\b" + code + "\\b.*");
        }
        return pattern.matcher(text).matches();
    }

    private static List<Throwable> causalChain(Throwable root) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = new HashSet<>();
        Throwable current = root;
        while (current != null && visited.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    /**
     * Extracts a human-readable message from an LLM error, stripping away raw
     * JSON and exception class names that are not useful in log output.
     */
    static String friendlyMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String raw = error.getMessage();
        if (raw == null || raw.isEmpty()) {
            return error.getClass().getSimpleName();
        }

        // Try to extract the inner "message" field from JSON error bodies
        // e.g. {"error":{"message":"...","type":"...","code":"..."}}
        Matcher m = JSON_ERROR_MESSAGE.matcher(raw);
        if (m.find()) {
            String inner = m.group(1).trim();
            // The inner message may itself be a Python-style dict string;
            // try to pull the value of 'error' from it.
            Matcher nested = NESTED_ERROR_VALUE.matcher(inner);
            if (nested.find()) {
                return nested.group(1).trim();
            }
            return inner;
        }

        return raw;
    }

    /**
     * Returns true if the error message indicates the configured model name
     * was rejected by the server.
     */
    static boolean isInvalidModelError(String message) {
        return message != null && INVALID_MODEL_PATTERN.matcher(message).find();
    }
}
