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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

public class SocketConnectDispatchTest {

    @AfterEach
    public void tearDown() {
        MockFramework.disable();
    }

    @Test
    public void delegatesToRegularSocketWhenMocksAreDisabled() throws IOException {
        RecordingSocket socket = new RecordingSocket();
        SocketConnectDispatch.connect(socket, null);
        Assertions.assertTrue(socket.connectWithoutTimeoutCalled);
    }

    @Test
    public void rejectsRegularSocketWhenMocksAreEnabled() {
        MockFramework.enable();
        IOException ex = Assertions.assertThrows(IOException.class,
                () -> SocketConnectDispatch.connect(new RecordingSocket(), null));
        Assertions.assertTrue(ex.getMessage().contains("Real socket connect"));
    }

    @Test
    public void delegatesToMockSocketWhenMocksAreEnabled() {
        MockFramework.enable();
        MockSocket mockSocket = new MockSocket();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SocketConnectDispatch.connect(mockSocket, null));
    }

    @Test
    public void delegatesToMockSslSocketWhenMocksAreEnabled() {
        MockFramework.enable();
        MockSSLSocket mockSslSocket = new MockSSLSocket();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SocketConnectDispatch.connect(mockSslSocket, null));
    }

    private static class RecordingSocket extends Socket {
        private boolean connectWithoutTimeoutCalled = false;

        @Override
        public void connect(SocketAddress endpoint) {
            connectWithoutTimeoutCalled = true;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
            connectWithoutTimeoutCalled = true;
        }
    }
}
