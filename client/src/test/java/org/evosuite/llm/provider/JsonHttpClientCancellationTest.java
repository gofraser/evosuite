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
import org.evosuite.Properties;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bounded-size and cancellable-read behaviour of
 * {@link JsonHttpClient}: a response over the configured cap must fail with
 * {@link LlmProtocolException} instead of buffering without bound, and a
 * blocked read must be abortable from another thread since
 * {@code Future.cancel(true)} alone cannot interrupt {@link
 * java.net.HttpURLConnection} I/O.
 */
class JsonHttpClientCancellationTest {

    @TempDir
    Path tempDir;

    private final CountDownLatch releaseHandler = new CountDownLatch(1);

    @AfterEach
    void releaseAnyBlockedHandler() {
        releaseHandler.countDown();
    }

    @Test
    void abortInFlightEndsABlockedReadWithoutWaitingForTheReadTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked: body length not known up front
            OutputStream body = exchange.getResponseBody();
            try {
                body.write('a');
                body.flush();
                while (!releaseHandler.await(100L, TimeUnit.MILLISECONDS)) {
                    // Keep the connection open until the test releases it (or
                    // the client aborts and this write starts failing).
                    body.write('a');
                    body.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // Client aborted the connection, or the test is tearing down.
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JsonHttpClient client = new JsonHttpClient();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    // A generous read timeout: if abortInFlight() did nothing,
                    // this call would block for the full 30 seconds instead of
                    // failing almost immediately below.
                    client.postJson(url, Collections.emptyMap(), "{}", 5_000, 30_000);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    done.countDown();
                }
            });
            worker.start();
            // Give the server time to send headers and the first byte so the
            // client is parked inside the blocking read this is testing.
            Thread.sleep(300L);

            client.abortInFlight();

            assertTrue(done.await(2_000L, TimeUnit.MILLISECONDS),
                    "abortInFlight() should end the blocked read well before the 30s read timeout");
            assertNotNull(thrown.get());
            assertInstanceOf(IOException.class, thrown.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseOverTheCapIsRejectedInsteadOfBufferedWithoutBound() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = "0123456789".getBytes(StandardCharsets.UTF_8);
            try (OutputStream body = exchange.getResponseBody()) {
                // Comfortably over the small cap this test injects, but tiny
                // in absolute terms — no need to write anywhere near 8MB.
                for (int i = 0; i < 5; i++) {
                    body.write(chunk);
                }
            }
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JsonHttpClient client = new JsonHttpClient(16);
            assertThrows(LlmProtocolException.class,
                    () -> client.postJson(url, Collections.emptyMap(), "{}", 5_000, 5_000));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serviceLevelTimeoutAbortsTheBlockedProviderReadPromptly() throws Exception {
        CountDownLatch serverObservedDisconnect = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (int i = 0; i < 600; i++) {
                    body.write('a');
                    body.flush();
                    Thread.sleep(10L);
                }
            } catch (IOException e) {
                // Expected once the client aborts the in-flight connection.
                serverObservedDisconnect.countDown();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            // The provider's own read timeout is generous (30s) so that the
            // phase deadline below — not the socket's natural read timeout —
            // is what ends the call; only cancelInFlight() can make that true.
            OpenAiCompatibleChatModel model = new OpenAiCompatibleChatModel(
                    Properties.LlmProvider.OPENAI, "local-model", "local-token", baseUrl, 0.0, 1024, 30);
            LlmConfiguration configuration = new LlmConfiguration(
                    Properties.LlmProvider.OPENAI, "local-model", "local-token", baseUrl,
                    0.0, 1024, 1, 0, 1, false, tempDir, "trickle-run");
            LlmStatistics statistics = new LlmStatistics();
            LlmService service = new LlmService(model, new LlmBudgetCoordinator.Local(1), configuration,
                    statistics, new LlmTraceRecorder(configuration));

            try {
                long start = System.currentTimeMillis();
                LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
                        () -> service.query(Collections.singletonList(LlmMessage.user("generate a test")),
                                LlmFeature.TEST_REPAIR));
                long elapsedMs = System.currentTimeMillis() - start;

                assertInstanceOf(TimeoutException.class, failure.getCause());
                // Comfortably below the 30s read timeout: only the abort, not
                // the socket's own timeout, could have ended this so quickly.
                // The bound is loose because a loaded surefire JVM has been
                // seen to take several seconds past the deadline to unwind.
                assertTrue(elapsedMs < 15_000L,
                        "call should fail shortly after the 1s phase deadline, not the 30s read timeout: took "
                                + elapsedMs + "ms");
                assertTrue(serverObservedDisconnect.await(3_000L, TimeUnit.MILLISECONDS),
                        "server should observe the client disconnect once cancelInFlight() runs");
            } finally {
                service.close();
            }
        } finally {
            releaseHandler.countDown();
            server.stop(0);
        }
    }
}
