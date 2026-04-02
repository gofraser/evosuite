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

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * Static replacement for {@link SSLSocketFactory} that returns deterministic VNET-backed
 * SSL sockets when EvoSuite mocks are enabled.
 */
public class MockSSLSocketFactory implements StaticReplacementMock {

    private static final String[] DEFAULT_CIPHER_SUITES = new String[]{"TLS_FAKE_WITH_NULL_NULL"};
    private static final DelegatingFactory FACTORY = new DelegatingFactory();

    @Override
    public String getMockedClassName() {
        return SSLSocketFactory.class.getName();
    }

    public static SocketFactory getDefault() {
        if (!MockFramework.isEnabled()) {
            return SSLSocketFactory.getDefault();
        }
        return FACTORY;
    }

    public static String[] getDefaultCipherSuites(SSLSocketFactory factory) {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).getDefaultCipherSuites();
        }
        return DEFAULT_CIPHER_SUITES.clone();
    }

    public static String[] getSupportedCipherSuites(SSLSocketFactory factory) {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).getSupportedCipherSuites();
        }
        return DEFAULT_CIPHER_SUITES.clone();
    }

    public static Socket createSocket(SSLSocketFactory factory) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket();
        }
        return new MockSSLSocket();
    }

    public static Socket createSocket(SSLSocketFactory factory, Socket socket,
                                      String host, int port, boolean autoClose) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(socket, host, port, autoClose);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(host, port));
        return ssl;
    }

    public static Socket createSocket(SSLSocketFactory factory, Socket socket,
                                      InputStream consumed, boolean autoClose) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(socket, consumed, autoClose);
        }
        // Keep behavior deterministic but simple for this less-common overload.
        return new MockSSLSocket();
    }

    public static Socket createSocket(SSLSocketFactory factory, String host, int port)
            throws IOException, UnknownHostException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(host, port);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(host, port));
        return ssl;
    }

    public static Socket createSocket(SSLSocketFactory factory, String host, int port,
                                      InetAddress localAddress, int localPort)
            throws IOException, UnknownHostException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(host, port, localAddress, localPort);
        }
        Socket ssl = new MockSSLSocket();
        if (localAddress != null || localPort > 0) {
            ssl.bind(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(localAddress, localPort));
        }
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(host, port));
        return ssl;
    }

    public static Socket createSocket(SSLSocketFactory factory, InetAddress address, int port) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(address, port);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(address, port));
        return ssl;
    }

    public static Socket createSocket(SSLSocketFactory factory, InetAddress address, int port,
                                      InetAddress localAddress, int localPort) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(address, port, localAddress, localPort);
        }
        Socket ssl = new MockSSLSocket();
        if (localAddress != null || localPort > 0) {
            ssl.bind(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(localAddress, localPort));
        }
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(address, port));
        return ssl;
    }

    private static SSLSocketFactory realFactory(SSLSocketFactory factory) {
        if (factory == FACTORY) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        return factory;
    }

    private static class DelegatingFactory extends SSLSocketFactory {

        @Override
        public String[] getDefaultCipherSuites() {
            return DEFAULT_CIPHER_SUITES.clone();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return DEFAULT_CIPHER_SUITES.clone();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return MockSSLSocketFactory.createSocket(this, socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            return MockSSLSocketFactory.createSocket(this);
        }

        @Override
        public Socket createSocket(Socket socket, InputStream consumed, boolean autoClose) throws IOException {
            return MockSSLSocketFactory.createSocket(this, socket, consumed, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return MockSSLSocketFactory.createSocket(this, host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return MockSSLSocketFactory.createSocket(this, host, port, localAddress, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return MockSSLSocketFactory.createSocket(this, host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return MockSSLSocketFactory.createSocket(this, address, port, localAddress, localPort);
        }
    }
}
