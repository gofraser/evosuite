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
package org.evosuite.runtime.mock.java.net;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.javax.net.ssl.MockSSLSocket;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * Dispatch helper for {@link Socket#connect(SocketAddress)} and
 * {@link Socket#connect(SocketAddress, int)} that preserves normal behavior when
 * mocks are disabled, but prevents real-network calls in mock mode for socket
 * types that are not EvoSuite mock sockets (eg SSL sockets).
 */
public final class SocketConnectDispatch {

    private SocketConnectDispatch() {
        // utility class
    }

    public static void connect(Socket socket, SocketAddress endpoint) throws IOException {
        if (!MockFramework.isEnabled()) {
            socket.connect(endpoint);
            return;
        }

        if (socket instanceof MockSocket) {
            ((MockSocket) socket).connect(endpoint);
            return;
        }
        if (socket instanceof MockSSLSocket) {
            ((MockSSLSocket) socket).connect(endpoint);
            return;
        }

        throw new IOException("Real socket connect is not supported while EvoSuite VNET mocks are active");
    }

    public static void connect(Socket socket, SocketAddress endpoint, int timeout) throws IOException {
        if (!MockFramework.isEnabled()) {
            socket.connect(endpoint, timeout);
            return;
        }

        if (socket instanceof MockSocket) {
            ((MockSocket) socket).connect(endpoint, timeout);
            return;
        }
        if (socket instanceof MockSSLSocket) {
            ((MockSSLSocket) socket).connect(endpoint, timeout);
            return;
        }

        throw new IOException("Real socket connect is not supported while EvoSuite VNET mocks are active");
    }
}
