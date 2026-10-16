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
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.testdata.EvoSuiteLocalAddress;
import org.evosuite.runtime.testdata.NetworkHandling;
import org.evosuite.runtime.vnet.NativeTcp;
import org.evosuite.runtime.vnet.VirtualNetwork;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * A nonblocking {@link EvoSocketChannel#read(ByteBuffer)} must tell "nothing available yet"
 * (0) apart from "the connection is really over" (-1): the underlying virtual stream
 * ({@link NativeTcp#readInSUTfromRemote()}) reports both the same way, as -1 from an empty
 * buffer, which is correct for the blocking {@code java.io} socket streams it was written
 * for but wrong for a nonblocking channel -- it tells the SUT the input has ended and can
 * abort protocol handling before the rest of the data ever arrives.
 */
public class EvoSocketChannelReadTest {

    @BeforeEach
    public void init() {
        MockFramework.enable();
        VirtualNetwork.getInstance().reset();
    }

    @Test
    public void nonblockingEmptyReadReturnsZeroThenByteCountThenEof() throws Exception {
        ServerSocketChannel server = MockServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.socket().bind(new InetSocketAddress("127.0.0.1", 8090), 1024);

        // Accept an empty virtual connection: a client is pending, but it has sent nothing yet.
        Assertions.assertTrue(NetworkHandling.sendDataOnTcp(
                new EvoSuiteLocalAddress("127.0.0.1", 8090), null));
        SocketChannel accepted = server.accept();
        accepted.configureBlocking(false);

        ByteBuffer buf = ByteBuffer.allocate(8);

        // Empty but open: must NOT be reported as end-of-stream.
        int n0 = accepted.read(buf);
        Assertions.assertEquals(0, n0, "a transient empty read must return 0, not -1, on a nonblocking channel");
        Assertions.assertTrue(accepted.isOpen());
        Assertions.assertTrue(accepted.isConnected());

        // Data arrives on the same connection after the fact.
        NativeTcp connection = soleOpenConnection();
        connection.writeToSUT((byte) 42);

        buf.clear();
        int n1 = accepted.read(buf);
        Assertions.assertEquals(1, n1, "a read after data arrives must return the byte count");
        buf.flip();
        Assertions.assertEquals((byte) 42, buf.get());

        // A real shutdown must still be reported as -1. MockSocket.shutdownInput() itself is
        // not implemented by the mock (it always throws), so close the underlying socket
        // directly -- exactly the "genuinely closed" half of what EvoSocketChannel checks
        // before trusting an empty read -- rather than the channel, which would make
        // ensureOpenAndConnected() throw ClosedChannelException before this is even reached.
        buf.clear();
        accepted.socket().close();
        int n2 = accepted.read(buf);
        Assertions.assertEquals(-1, n2, "a genuine shutdown must still be reported as end-of-stream");

        accepted.close();
        server.close();
    }

    @Test
    public void blockingReadDoesNotHangWhenNothingArrives() throws Exception {
        ServerSocketChannel server = MockServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.socket().bind(new InetSocketAddress("127.0.0.1", 8091), 1024);

        Assertions.assertTrue(NetworkHandling.sendDataOnTcp(
                new EvoSuiteLocalAddress("127.0.0.1", 8091), null));
        SocketChannel accepted = server.accept();
        // blocking is the channel's default mode

        long start = System.currentTimeMillis();
        int n = accepted.read(ByteBuffer.allocate(8));
        long elapsed = System.currentTimeMillis() - start;

        Assertions.assertEquals(0, n, "a blocking read with nothing to read and no shutdown must not fake an EOF");
        Assertions.assertTrue(elapsed < 5000, "a blocking read on an empty, open connection must stay time-bounded");

        accepted.close();
        server.close();
    }

    private NativeTcp soleOpenConnection() {
        java.util.Set<NativeTcp> open = VirtualNetwork.getInstance().getViewOfOpenedTcpConnections();
        Assertions.assertEquals(1, open.size());
        return open.iterator().next();
    }
}
