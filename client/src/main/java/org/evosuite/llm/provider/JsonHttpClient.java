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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal JSON-over-HTTP client built on {@link HttpURLConnection}. Kept
 * deliberately small: it knows nothing about any particular provider's
 * request/response shape, only how to POST a JSON body and hand back the
 * response text (or fail with {@link LlmHttpException} on a non-2xx status).
 */
public class JsonHttpClient {

    /**
     * Hard cap on how much of a response body is buffered. A response this
     * large is not a usable chat completion regardless of provider, so
     * reading stops and {@link LlmProtocolException} is thrown rather than
     * growing the buffer without bound.
     */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final int maxResponseBytes;

    public JsonHttpClient() {
        this(MAX_RESPONSE_BYTES);
    }

    JsonHttpClient(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Returns the read timeout to use for an LLM call, in milliseconds, given
     * the configured {@code llm_timeout_seconds}. Bounded to at least one
     * second so a misconfigured value of zero (or less) cannot make every
     * call fail immediately.
     */
    public static int boundedReadTimeoutMillis(int configuredTimeoutSeconds) {
        return Math.max(1, configuredTimeoutSeconds) * 1000;
    }

    /**
     * Returns the connect timeout to use, given the read timeout: never more
     * than 10 seconds, and never more than the read timeout itself.
     */
    public static int connectTimeoutMillis(int readTimeoutMillis) {
        return Math.min(10_000, readTimeoutMillis);
    }

    /**
     * Sends {@code body} as a JSON POST to {@code url} and returns the
     * response body as a UTF-8 string. Equivalent to calling the 6-argument
     * overload with a {@code null} cancellation handle, i.e. the request
     * cannot be aborted before its read timeout elapses.
     *
     * @throws LlmHttpException if the server responds with a non-2xx status
     * @throws IOException      on any other transport failure (including a
     *                          timeout or an interruption of the underlying
     *                          socket read, which is left to propagate as-is)
     */
    public String postJson(String url, Map<String, String> headers, String body,
                           int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return postJson(url, headers, body, connectTimeoutMs, readTimeoutMs, null);
    }

    /**
     * Sends {@code body} as a JSON POST to {@code url} and returns the
     * response body as a UTF-8 string.
     *
     * <p>If {@code cancellation} is non-null, it is {@link
     * RequestCancellation#attach(Runnable) attached} to this specific
     * connection right after it is opened, so a caller that gives up on this
     * one request (a phase deadline, or the caller thread being interrupted)
     * can force just this connection closed — turning a blocked socket read
     * into an {@link IOException} immediately instead of waiting for the
     * read timeout, without touching any other request the client may have
     * in flight concurrently.
     *
     * @throws LlmHttpException if the server responds with a non-2xx status
     * @throws IOException      on any other transport failure (including a
     *                          timeout or an interruption of the underlying
     *                          socket read, which is left to propagate as-is)
     */
    public String postJson(String url, Map<String, String> headers, String body,
                           int connectTimeoutMs, int readTimeoutMs,
                           RequestCancellation cancellation) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        boolean attachedBeforeAbort = cancellation == null || cancellation.attach(connection::disconnect);
        boolean completed = false;
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            // An abort() that landed before attach(), or between attach() and
            // here, ran disconnect() on a connection that had not started
            // connecting — a no-op that would let the request go out anyway.
            // Fail before any I/O in both cases. From getOutputStream() on the
            // connection is live, so a later abort() tears down a real socket
            // and the blocked write/read fails with an IOException (covered by
            // the blocked-read test in JsonHttpClientCancellationTest).
            if (!attachedBeforeAbort || (cancellation != null && cancellation.isAborted())) {
                throw new RequestCancelledBeforeSendException();
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new LlmHttpException(status, readStream(connection, connection.getErrorStream()));
            }
            String response = readStream(connection, connection.getInputStream());
            completed = true;
            return response;
        } finally {
            // A fully drained and closed response stream returns the socket to the JDK's
            // keep-alive cache, saving a TCP (and TLS) handshake on the next call; only an
            // aborted or failed exchange needs the connection torn down.
            if (!completed) {
                connection.disconnect();
            }
        }
    }

    private String readStream(HttpURLConnection connection, InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        int total = 0;
        try (InputStream stream = input) {
            while ((count = stream.read(chunk)) >= 0) {
                total += count;
                if (total > maxResponseBytes) {
                    connection.disconnect();
                    throw new LlmProtocolException("response exceeded " + maxResponseBytes + " bytes");
                }
                buffer.write(chunk, 0, count);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
