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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the specific race {@link JsonHttpClient#postJson} used to lose: a
 * {@link RequestCancellation} aborted before (or right at) attach time used
 * to have no way to tell {@code postJson} that {@code disconnect()} had just
 * run on an unopened connection and therefore done nothing, so the request
 * went out anyway. {@link RequestCancellation#attach(Runnable)} now reports
 * that directly, and {@code postJson} must refuse to send in that case.
 *
 * <p>The remaining gap — an abort landing between the post-attach
 * {@code isAborted()} check and the {@code getOutputStream()} call right
 * after it — is only a handful of bytecode instructions wide and has no test
 * seam in {@code postJson} to pause inside deliberately, so it cannot be hit
 * deterministically from a test; by that point in the method the connection
 * is live, so an abort landing there is already covered by the existing
 * blocked-read cancellation test in {@code JsonHttpClientCancellationTest}
 * (disconnecting a connecting/connected connection turns its blocked I/O
 * into an {@link IOException}).
 */
class JsonHttpClientEarlyCancellationTest {

    @Test
    void abortBeforePostJsonPreventsTheRequestFromBeingSent() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JsonHttpClient client = new JsonHttpClient();
            RequestCancellation cancellation = new RequestCancellation();
            cancellation.abort();

            long start = System.currentTimeMillis();
            assertThrows(IOException.class,
                    () -> client.postJson(url, Collections.emptyMap(), "{}", 5_000, 5_000, cancellation));
            long elapsedMs = System.currentTimeMillis() - start;

            assertTrue(elapsedMs < 1_000L,
                    "an already-aborted call must fail promptly without attempting any I/O: took "
                            + elapsedMs + "ms");
            // Give a slow, misbehaving implementation a moment to reach the
            // server before asserting it never did.
            Thread.sleep(200L);
            assertEquals(0, requestCount.get(),
                    "the server must never see a request for a call cancelled before it was sent");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void abortBeforePostJsonReportsTheDedicatedCancellationMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JsonHttpClient client = new JsonHttpClient();
            RequestCancellation cancellation = new RequestCancellation();
            cancellation.abort();

            RequestCancelledBeforeSendException thrown = assertThrows(RequestCancelledBeforeSendException.class,
                    () -> client.postJson(url, Collections.emptyMap(), "{}", 5_000, 5_000, cancellation));
            assertEquals(RequestCancelledBeforeSendException.MESSAGE, thrown.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
