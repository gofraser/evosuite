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
package org.evosuite.runtime.mock.javax.net.ssl;

import org.evosuite.runtime.mock.java.net.MockSocket;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A lightweight SSLSocket mock that reuses EvoSuite's {@link MockSocket} VNET behavior.
 * TLS semantics are intentionally minimal and deterministic.
 */
public class MockSSLSocket extends SSLSocket {

    private static final String[] DEFAULT_CIPHER_SUITES = new String[]{"TLS_FAKE_WITH_NULL_NULL"};
    private static final String[] DEFAULT_PROTOCOLS = new String[]{"TLSv1.2"};

    private final MockSocket delegate;
    private final List<HandshakeCompletedListener> listeners = new ArrayList<>();

    private boolean useClientMode = true;
    private boolean needClientAuth = false;
    private boolean wantClientAuth = false;
    private boolean enableSessionCreation = true;
    private String[] enabledCipherSuites = DEFAULT_CIPHER_SUITES.clone();
    private String[] enabledProtocols = DEFAULT_PROTOCOLS.clone();
    private BiFunction<SSLSocket, List<String>, String> handshakeSelector;

    public MockSSLSocket() {
        delegate = new MockSocket();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return DEFAULT_CIPHER_SUITES.clone();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites.clone();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        enabledCipherSuites = suites == null ? new String[0] : suites.clone();
    }

    @Override
    public String[] getSupportedProtocols() {
        return DEFAULT_PROTOCOLS.clone();
    }

    @Override
    public String[] getEnabledProtocols() {
        return enabledProtocols.clone();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        enabledProtocols = protocols == null ? new String[0] : protocols.clone();
    }

    @Override
    public SSLSession getSession() {
        return null;
    }

    @Override
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void startHandshake() throws IOException {
        if (!delegate.isConnected()) {
            throw new IOException("Socket is not connected");
        }
    }

    @Override
    public void setUseClientMode(boolean mode) {
        useClientMode = mode;
    }

    @Override
    public boolean getUseClientMode() {
        return useClientMode;
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        needClientAuth = need;
        if (need) {
            wantClientAuth = false;
        }
    }

    @Override
    public boolean getNeedClientAuth() {
        return needClientAuth;
    }

    @Override
    public void setWantClientAuth(boolean want) {
        wantClientAuth = want;
        if (want) {
            needClientAuth = false;
        }
    }

    @Override
    public boolean getWantClientAuth() {
        return wantClientAuth;
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        enableSessionCreation = flag;
    }

    @Override
    public boolean getEnableSessionCreation() {
        return enableSessionCreation;
    }

    @Override
    public void setHandshakeApplicationProtocolSelector(
            BiFunction<SSLSocket, List<String>, String> selector) {
        handshakeSelector = selector;
    }

    @Override
    public BiFunction<SSLSocket, List<String>, String> getHandshakeApplicationProtocolSelector() {
        return handshakeSelector;
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        delegate.connect(endpoint);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        delegate.connect(endpoint, timeout);
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {
        delegate.bind(bindpoint);
    }

    @Override
    public InetAddress getInetAddress() {
        return delegate.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public int getPort() {
        return delegate.getPort();
    }

    @Override
    public int getLocalPort() {
        return delegate.getLocalPort();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return delegate.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return delegate.getOutputStream();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        delegate.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return delegate.getSoTimeout();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public boolean isBound() {
        return delegate.isBound();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void shutdownInput() throws IOException {
        delegate.shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        delegate.shutdownOutput();
    }

    @Override
    public String toString() {
        return "MockSSLSocket{" + delegate + "}";
    }
}
