/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.testdata.EvoSuiteLocalAddress;
import org.evosuite.runtime.testdata.NetworkHandling;
import org.evosuite.runtime.vnet.NativeTcp;
import org.evosuite.runtime.vnet.VirtualNetwork;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Verifies that a virtual connected channel supports the NIO half-close operations.
 * Each direction must stop independently: shutting input reports EOF while output
 * remains usable, and shutting output rejects writes while input remains usable.
 */
public class EvoSocketChannelHalfCloseTest {

    private static final int PORT = 8092;

    @BeforeEach
    public void init() {
        MockFramework.enable();
        VirtualNetwork.getInstance().reset();
    }

    @Test
    public void shutdownInputReportsEofAndKeepsOutputOpen() throws Exception {
        ServerSocketChannel server = openServer();
        SocketChannel accepted = acceptConnection(server, "before".getBytes());
        NativeTcp connection = soleOpenConnection();
        try {
            InputStream cachedInput = accepted.socket().getInputStream();
            ByteBuffer before = ByteBuffer.allocate(16);
            Assertions.assertEquals(6, accepted.read(before));

            accepted.shutdownInput();
            accepted.shutdownInput();
            Assertions.assertTrue(accepted.socket().isInputShutdown());

            // Data buffered after the half-close must not become readable again.
            connection.writeToSUT((byte) 9);
            Assertions.assertEquals(-1, accepted.read(ByteBuffer.allocate(1)));
            Assertions.assertEquals(-1, accepted.read(ByteBuffer.allocate(0)));
            Assertions.assertEquals(-1,
                    accepted.read(new ByteBuffer[]{ByteBuffer.allocate(0)}, 0, 1));
            Assertions.assertEquals(-1, cachedInput.read());

            // The opposite direction remains available after input is shut down.
            Assertions.assertEquals(1, accepted.write(ByteBuffer.wrap(new byte[]{7})));
            Assertions.assertEquals(7, connection.readInTestFromSUT());
        } finally {
            accepted.close();
            server.close();
        }
    }

    @Test
    public void shutdownOutputRejectsWritesAndKeepsInputOpen() throws Exception {
        ServerSocketChannel server = openServer();
        SocketChannel accepted = acceptConnection(server, null);
        NativeTcp connection = soleOpenConnection();
        try {
            OutputStream cachedOutput = accepted.socket().getOutputStream();
            Assertions.assertEquals(1, accepted.write(ByteBuffer.wrap(new byte[]{1})));
            Assertions.assertEquals(1, connection.readInTestFromSUT());

            accepted.shutdownOutput();
            accepted.shutdownOutput();
            Assertions.assertTrue(accepted.socket().isOutputShutdown());
            Assertions.assertThrows(ClosedChannelException.class,
                    () -> accepted.write(ByteBuffer.wrap(new byte[]{2})));
            Assertions.assertThrows(ClosedChannelException.class,
                    () -> accepted.write(ByteBuffer.allocate(0)));
            Assertions.assertThrows(ClosedChannelException.class,
                    () -> accepted.write(new ByteBuffer[]{ByteBuffer.allocate(0)}, 0, 1));
            Assertions.assertThrows(ClosedChannelException.class,
                    () -> accepted.write(new ByteBuffer[]{ByteBuffer.allocate(1)}, 0, 0));
            Assertions.assertThrows(SocketException.class, () -> cachedOutput.write(2));

            // Input remains available after the output half is shut down.
            connection.writeToSUT((byte) 3);
            ByteBuffer received = ByteBuffer.allocate(1);
            Assertions.assertEquals(1, accepted.read(received));
            received.flip();
            Assertions.assertEquals(3, received.get());
        } finally {
            accepted.close();
            server.close();
        }
    }

    private ServerSocketChannel openServer() throws IOException {
        ServerSocketChannel server = MockServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.socket().bind(new InetSocketAddress("127.0.0.1", PORT), 1024);
        return server;
    }

    private SocketChannel acceptConnection(ServerSocketChannel server, byte[] data)
            throws IOException {
        Assertions.assertTrue(NetworkHandling.sendDataOnTcp(
                new EvoSuiteLocalAddress("127.0.0.1", PORT), data));
        return server.accept();
    }

    private NativeTcp soleOpenConnection() {
        java.util.Set<NativeTcp> open =
                VirtualNetwork.getInstance().getViewOfOpenedTcpConnections();
        Assertions.assertEquals(1, open.size());
        return open.iterator().next();
    }
}
