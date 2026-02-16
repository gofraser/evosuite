/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.net.http;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic replacement for {@link WebSocket} instance entry points.
 */
public class MockWebSocket implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return WebSocket.class.getName();
    }

    public static CompletableFuture<WebSocket> sendText(WebSocket webSocket, CharSequence data, boolean last) {
        Objects.requireNonNull(webSocket);
        Objects.requireNonNull(data);
        if (!MockFramework.isEnabled()) {
            return webSocket.sendText(data, last);
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.sendText(data, last);
        }
        return failedFuture(new IllegalStateException("Unsupported WebSocket implementation in mocked execution"));
    }

    public static CompletableFuture<WebSocket> sendBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        Objects.requireNonNull(webSocket);
        Objects.requireNonNull(data);
        if (!MockFramework.isEnabled()) {
            return webSocket.sendBinary(data, last);
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.sendBinary(data, last);
        }
        return failedFuture(new IllegalStateException("Unsupported WebSocket implementation in mocked execution"));
    }

    public static CompletableFuture<WebSocket> sendPing(WebSocket webSocket, ByteBuffer message) {
        Objects.requireNonNull(webSocket);
        Objects.requireNonNull(message);
        if (!MockFramework.isEnabled()) {
            return webSocket.sendPing(message);
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.sendPing(message);
        }
        return failedFuture(new IllegalStateException("Unsupported WebSocket implementation in mocked execution"));
    }

    public static CompletableFuture<WebSocket> sendPong(WebSocket webSocket, ByteBuffer message) {
        Objects.requireNonNull(webSocket);
        Objects.requireNonNull(message);
        if (!MockFramework.isEnabled()) {
            return webSocket.sendPong(message);
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.sendPong(message);
        }
        return failedFuture(new IllegalStateException("Unsupported WebSocket implementation in mocked execution"));
    }

    public static CompletableFuture<WebSocket> sendClose(WebSocket webSocket, int statusCode, String reason) {
        Objects.requireNonNull(webSocket);
        Objects.requireNonNull(reason);
        if (!MockFramework.isEnabled()) {
            return webSocket.sendClose(statusCode, reason);
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.sendClose(statusCode, reason);
        }
        return failedFuture(new IllegalStateException("Unsupported WebSocket implementation in mocked execution"));
    }

    public static void request(WebSocket webSocket, long n) {
        Objects.requireNonNull(webSocket);
        if (!MockFramework.isEnabled()) {
            webSocket.request(n);
            return;
        }
        if (isDeterministicWebSocket(webSocket)) {
            webSocket.request(n);
            return;
        }
        throw new IllegalStateException("Unsupported WebSocket implementation in mocked execution");
    }

    public static String getSubprotocol(WebSocket webSocket) {
        Objects.requireNonNull(webSocket);
        if (!MockFramework.isEnabled()) {
            return webSocket.getSubprotocol();
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.getSubprotocol();
        }
        throw new IllegalStateException("Unsupported WebSocket implementation in mocked execution");
    }

    public static boolean isOutputClosed(WebSocket webSocket) {
        Objects.requireNonNull(webSocket);
        if (!MockFramework.isEnabled()) {
            return webSocket.isOutputClosed();
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.isOutputClosed();
        }
        throw new IllegalStateException("Unsupported WebSocket implementation in mocked execution");
    }

    public static boolean isInputClosed(WebSocket webSocket) {
        Objects.requireNonNull(webSocket);
        if (!MockFramework.isEnabled()) {
            return webSocket.isInputClosed();
        }
        if (isDeterministicWebSocket(webSocket)) {
            return webSocket.isInputClosed();
        }
        throw new IllegalStateException("Unsupported WebSocket implementation in mocked execution");
    }

    public static void abort(WebSocket webSocket) {
        Objects.requireNonNull(webSocket);
        if (!MockFramework.isEnabled()) {
            webSocket.abort();
            return;
        }
        if (isDeterministicWebSocket(webSocket)) {
            webSocket.abort();
            return;
        }
        throw new IllegalStateException("Unsupported WebSocket implementation in mocked execution");
    }

    private static boolean isDeterministicWebSocket(WebSocket webSocket) {
        return webSocket.getClass().getName().contains("MockHttpClient$DeterministicWebSocket");
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(t);
        return failed;
    }
}
