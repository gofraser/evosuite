/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.net.http;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Deterministic replacement for JDK 11+ HttpClient APIs.
 */
public class MockHttpClient implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return HttpClient.class.getName();
    }

    public static HttpClient newHttpClient() {
        if (!MockFramework.isEnabled()) {
            return HttpClient.newHttpClient();
        }
        return new DeterministicBuilder().build();
    }

    public static HttpClient.Builder newBuilder() {
        if (!MockFramework.isEnabled()) {
            return HttpClient.newBuilder();
        }
        return new DeterministicBuilder();
    }

    private static final class DeterministicHttpClient extends HttpClient {

        private final Optional<CookieHandler> cookieHandler;
        private final Optional<Duration> connectTimeout;
        private final Redirect followRedirects;
        private final Optional<ProxySelector> proxy;
        private final Optional<Authenticator> authenticator;
        private final Version version;
        private final Optional<Executor> executor;
        private final SSLContext sslContext;
        private final SSLParameters sslParameters;

        private DeterministicHttpClient(
                Optional<CookieHandler> cookieHandler,
                Optional<Duration> connectTimeout,
                Redirect followRedirects,
                Optional<ProxySelector> proxy,
                Optional<Authenticator> authenticator,
                Version version,
                Optional<Executor> executor,
                SSLContext sslContext,
                SSLParameters sslParameters) {
            this.cookieHandler = cookieHandler;
            this.connectTimeout = connectTimeout;
            this.followRedirects = followRedirects;
            this.proxy = proxy;
            this.authenticator = authenticator;
            this.version = version;
            this.executor = executor;
            this.sslContext = sslContext;
            this.sslParameters = sslParameters;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return cookieHandler;
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return connectTimeout;
        }

        @Override
        public Redirect followRedirects() {
            return followRedirects;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return proxy;
        }

        @Override
        public SSLContext sslContext() {
            return sslContext;
        }

        @Override
        public SSLParameters sslParameters() {
            return sslParameters;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return authenticator;
        }

        @Override
        public Version version() {
            return version;
        }

        @Override
        public Optional<Executor> executor() {
            return executor;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            Objects.requireNonNull(request);
            Objects.requireNonNull(responseBodyHandler);
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(new DeterministicResponseInfo());
            subscriber.onSubscribe(new NoopSubscription());
            subscriber.onComplete();
            T body = subscriber.getBody().toCompletableFuture().join();
            return new DeterministicHttpResponse<>(request, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(responseBodyHandler);
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException e) {
                CompletableFuture<HttpResponse<T>> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            Objects.requireNonNull(pushPromiseHandler);
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            return new DeterministicWebSocketBuilder();
        }
    }

    private static final class DeterministicBuilder implements HttpClient.Builder {

        private CookieHandler cookieHandler;
        private Duration connectTimeout;
        private SSLContext sslContext;
        private SSLParameters sslParameters = new SSLParameters();
        private Executor executor;
        private HttpClient.Redirect followRedirects = HttpClient.Redirect.NEVER;
        private HttpClient.Version version = HttpClient.Version.HTTP_1_1;
        private int priority = 0;
        private ProxySelector proxySelector;
        private Authenticator authenticator;

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            Objects.requireNonNull(cookieHandler);
            this.cookieHandler = cookieHandler;
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("duration must be positive");
            }
            this.connectTimeout = duration;
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            Objects.requireNonNull(sslContext);
            this.sslContext = sslContext;
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            Objects.requireNonNull(sslParameters);
            this.sslParameters = copyOf(sslParameters);
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            Objects.requireNonNull(executor);
            this.executor = executor;
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
            Objects.requireNonNull(policy);
            this.followRedirects = policy;
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version version) {
            Objects.requireNonNull(version);
            this.version = version;
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            if (priority < 1 || priority > 256) {
                throw new IllegalArgumentException("priority out of range");
            }
            this.priority = priority;
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            Objects.requireNonNull(proxySelector);
            this.proxySelector = proxySelector;
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            Objects.requireNonNull(authenticator);
            this.authenticator = authenticator;
            return this;
        }

        @Override
        public HttpClient build() {
            // priority is validated/stored for API parity, but does not alter deterministic behavior.
            return new DeterministicHttpClient(
                    Optional.ofNullable(cookieHandler),
                    Optional.ofNullable(connectTimeout),
                    followRedirects,
                    Optional.ofNullable(proxySelector),
                    Optional.ofNullable(authenticator),
                    version,
                    Optional.ofNullable(executor),
                    sslContext,
                    copyOf(sslParameters));
        }

        private static SSLParameters copyOf(SSLParameters source) {
            SSLParameters copy = new SSLParameters();
            copy.setCipherSuites(source.getCipherSuites());
            copy.setProtocols(source.getProtocols());
            copy.setNeedClientAuth(source.getNeedClientAuth());
            copy.setWantClientAuth(source.getWantClientAuth());
            copy.setEndpointIdentificationAlgorithm(source.getEndpointIdentificationAlgorithm());
            return copy;
        }
    }

    private static final class DeterministicWebSocketBuilder implements WebSocket.Builder {
        private final List<String[]> headers = new ArrayList<>();
        private Duration connectTimeout;
        private String subprotocol = "";

        @Override
        public WebSocket.Builder header(String name, String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            headers.add(new String[]{name, value});
            return this;
        }

        @Override
        public WebSocket.Builder connectTimeout(Duration timeout) {
            Objects.requireNonNull(timeout);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
            Objects.requireNonNull(mostPreferred);
            Objects.requireNonNull(lesserPreferred);
            if (mostPreferred.isEmpty()) {
                throw new IllegalArgumentException("mostPreferred must not be empty");
            }
            for (String p : lesserPreferred) {
                if (p == null || p.isEmpty()) {
                    throw new IllegalArgumentException("subprotocol must not be null/empty");
                }
            }
            this.subprotocol = mostPreferred;
            return this;
        }

        @Override
        public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
            Objects.requireNonNull(uri);
            Objects.requireNonNull(listener);
            DeterministicWebSocket webSocket = new DeterministicWebSocket(subprotocol);
            try {
                listener.onOpen(webSocket);
            } catch (Throwable t) {
                return failedFuture(t);
            }
            return CompletableFuture.completedFuture(webSocket);
        }
    }

    private static final class DeterministicWebSocket implements WebSocket {
        private final String subprotocol;
        private volatile boolean outputClosed;
        private volatile boolean inputClosed;

        private DeterministicWebSocket(String subprotocol) {
            this.subprotocol = subprotocol == null ? "" : subprotocol;
            this.outputClosed = false;
            this.inputClosed = false;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            Objects.requireNonNull(data);
            if (outputClosed) {
                return failedFuture(new IllegalStateException("WebSocket output is closed"));
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            Objects.requireNonNull(data);
            if (outputClosed) {
                return failedFuture(new IllegalStateException("WebSocket output is closed"));
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            Objects.requireNonNull(message);
            if (outputClosed) {
                return failedFuture(new IllegalStateException("WebSocket output is closed"));
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            Objects.requireNonNull(message);
            if (outputClosed) {
                return failedFuture(new IllegalStateException("WebSocket output is closed"));
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            Objects.requireNonNull(reason);
            if (statusCode < 1000 || statusCode > 4999) {
                throw new IllegalArgumentException("invalid close status code: " + statusCode);
            }
            outputClosed = true;
            inputClosed = true;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                throw new IllegalArgumentException("request count must be positive");
            }
        }

        @Override
        public String getSubprotocol() {
            return subprotocol;
        }

        @Override
        public boolean isOutputClosed() {
            return outputClosed;
        }

        @Override
        public boolean isInputClosed() {
            return inputClosed;
        }

        @Override
        public void abort() {
            outputClosed = true;
            inputClosed = true;
        }
    }

    private static final class DeterministicHttpResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final T body;

        private DeterministicHttpResponse(HttpRequest request, T body) {
            this.request = request;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Collections.emptyMap(), (x, y) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request != null ? request.uri() : URI.create("http://localhost/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
            // No streamed data is emitted in deterministic mode.
        }

        @Override
        public void cancel() {
            // Nothing to cancel for an empty deterministic response.
        }
    }

    private static final class DeterministicResponseInfo implements HttpResponse.ResponseInfo {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Collections.emptyMap(), (x, y) -> true);
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(t);
        return failed;
    }
}
