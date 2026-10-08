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

    /**
     * Replacement for {@link SSLSocketFactory#getDefault()}.
     *
     * @return the delegating mock factory while mocks are enabled, otherwise the real default factory
     */
    public static SocketFactory getDefault() {
        if (!MockFramework.isEnabled()) {
            return SSLSocketFactory.getDefault();
        }
        return FACTORY;
    }

    /**
     * Replacement for {@link SSLSocketFactory#getDefaultCipherSuites()}.
     *
     * @param factory the factory the call was made on
     * @return a fake cipher suite while mocks are enabled, otherwise the real default cipher suites
     */
    public static String[] getDefaultCipherSuites(SSLSocketFactory factory) {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).getDefaultCipherSuites();
        }
        return DEFAULT_CIPHER_SUITES.clone();
    }

    /**
     * Replacement for {@link SSLSocketFactory#getSupportedCipherSuites()}.
     *
     * @param factory the factory the call was made on
     * @return a fake cipher suite while mocks are enabled, otherwise the real supported cipher suites
     */
    public static String[] getSupportedCipherSuites(SSLSocketFactory factory) {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).getSupportedCipherSuites();
        }
        return DEFAULT_CIPHER_SUITES.clone();
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket()}.
     *
     * @param factory the factory the call was made on
     * @return a mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the real factory fails to create the socket
     */
    public static Socket createSocket(SSLSocketFactory factory) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket();
        }
        return new MockSSLSocket();
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(Socket, String, int, boolean)}.
     *
     * @param factory the factory the call was made on
     * @param socket the existing socket
     * @param host the server host
     * @param port the server port
     * @param autoClose whether closing the returned socket closes the underlying socket
     * @return a connected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the connection fails
     */
    public static Socket createSocket(SSLSocketFactory factory, Socket socket,
                                      String host, int port, boolean autoClose) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(socket, host, port, autoClose);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(host, port));
        return ssl;
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(Socket, InputStream, boolean)}.
     *
     * @param factory the factory the call was made on
     * @param socket the existing socket
     * @param consumed the bytes already read from the socket
     * @param autoClose whether closing the returned socket closes the underlying socket
     * @return an unconnected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the real factory fails to create the socket
     */
    public static Socket createSocket(SSLSocketFactory factory, Socket socket,
                                      InputStream consumed, boolean autoClose) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(socket, consumed, autoClose);
        }
        // Keep behavior deterministic but simple for this less-common overload.
        return new MockSSLSocket();
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(String, int)}.
     *
     * @param factory the factory the call was made on
     * @param host the server host
     * @param port the server port
     * @return a connected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the connection fails
     * @throws UnknownHostException if the host cannot be resolved
     */
    public static Socket createSocket(SSLSocketFactory factory, String host, int port)
            throws IOException, UnknownHostException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(host, port);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(host, port));
        return ssl;
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(String, int, InetAddress, int)}.
     *
     * @param factory the factory the call was made on
     * @param host the server host
     * @param port the server port
     * @param localAddress the local address to bind to, or null
     * @param localPort the local port to bind to, or 0 or less for any
     * @return a bound and connected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the connection fails
     * @throws UnknownHostException if the host cannot be resolved
     */
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

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(InetAddress, int)}.
     *
     * @param factory the factory the call was made on
     * @param address the server address
     * @param port the server port
     * @return a connected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the connection fails
     */
    public static Socket createSocket(SSLSocketFactory factory, InetAddress address, int port) throws IOException {
        if (!MockFramework.isEnabled()) {
            return realFactory(factory).createSocket(address, port);
        }
        Socket ssl = new MockSSLSocket();
        ssl.connect(new org.evosuite.runtime.mock.java.net.MockInetSocketAddress(address, port));
        return ssl;
    }

    /**
     * Replacement for {@link SSLSocketFactory#createSocket(InetAddress, int, InetAddress, int)}.
     *
     * @param factory the factory the call was made on
     * @param address the server address
     * @param port the server port
     * @param localAddress the local address to bind to, or null
     * @param localPort the local port to bind to, or 0 or less for any
     * @return a bound and connected mock SSL socket while mocks are enabled, otherwise a real socket
     * @throws IOException if the connection fails
     */
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
