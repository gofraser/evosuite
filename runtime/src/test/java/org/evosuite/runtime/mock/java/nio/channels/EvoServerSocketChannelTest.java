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
import org.evosuite.runtime.vnet.VirtualNetwork;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Covers {@link EvoServerSocketChannel} and {@link EvoSocketChannel}, the virtual-network-backed
 * channel objects returned by {@link MockServerSocketChannel#open()} and
 * {@link MockSocketChannel#open()} when the mock framework is enabled.
 */
public class EvoServerSocketChannelTest {

    @BeforeEach
    public void init() {
        MockFramework.enable();
        VirtualNetwork.getInstance().reset();
    }

    @Test
    public void testOpenReturnsOpenChannel() throws IOException {
        ServerSocketChannel channel = MockServerSocketChannel.open();
        Assertions.assertNotNull(channel);
        Assertions.assertTrue(channel.isOpen());
        channel.close();
    }

    @Test
    public void testSetReuseAddress() throws IOException {
        ServerSocketChannel channel = MockServerSocketChannel.open();
        channel.socket().setReuseAddress(true);
        Assertions.assertTrue(channel.socket().getReuseAddress());
        channel.close();
    }

    @Test
    public void testAcceptWithoutPendingConnectionThrows() throws IOException {
        ServerSocketChannel channel = MockServerSocketChannel.open();
        channel.socket().bind(new InetSocketAddress("127.0.0.1", 8080), 1024);

        Assertions.assertThrows(IOException.class, () -> channel.accept());

        channel.close();
    }

    @Test
    public void testAcceptReturnsConnectedChannelWithIncomingData() throws IOException {
        ServerSocketChannel channel = MockServerSocketChannel.open();
        channel.socket().setReuseAddress(true);
        channel.socket().bind(new InetSocketAddress("127.0.0.1", 8080), 1024);

        Assertions.assertTrue(NetworkHandling.sendMessageOnTcp(
                new EvoSuiteLocalAddress("127.0.0.1", 8080), "hello"));

        SocketChannel accepted = channel.accept();
        Assertions.assertNotNull(accepted);
        Assertions.assertTrue(accepted.isConnected());

        ByteBuffer buf = ByteBuffer.allocate(64);
        int n = accepted.read(buf);
        Assertions.assertTrue(n > 0);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        Assertions.assertEquals("hello", new String(data, StandardCharsets.UTF_8));

        accepted.close();
        channel.close();
    }

    @Test
    public void testCloseMakesChannelClosed() throws IOException {
        ServerSocketChannel channel = MockServerSocketChannel.open();
        channel.socket().bind(new InetSocketAddress("127.0.0.1", 8081), 1024);

        channel.close();

        Assertions.assertFalse(channel.isOpen());
        Assertions.assertThrows(ClosedChannelException.class, () -> channel.accept());
    }

    @Test
    public void testSocketChannelOpenReturnsOpenChannel() throws IOException {
        SocketChannel channel = MockSocketChannel.open();
        Assertions.assertNotNull(channel);
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertFalse(channel.isConnected());
        channel.close();
    }

    @Test
    public void testServingThreadBlocksUntilClientArrives() throws Exception {
        ServerSocketChannel server = MockServerSocketChannel.open();
        server.bind(new InetSocketAddress(4242));

        java.util.concurrent.atomic.AtomicReference<Object> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        Thread serving = new Thread(() -> {
            try {
                outcome.set(server.accept());
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        serving.start();
        serving.join(200);
        Assertions.assertTrue(serving.isAlive(), "a serving thread should wait for a client, not fail at once");

        NetworkHandling.sendMessageOnTcp(new EvoSuiteLocalAddress("127.0.0.1", 4242), "hello");
        serving.join(5000);
        Assertions.assertFalse(serving.isAlive());
        Assertions.assertTrue(outcome.get() instanceof SocketChannel, String.valueOf(outcome.get()));
        server.close();
    }

    @Test
    public void testClosingReleasesServingThread() throws Exception {
        ServerSocketChannel server = MockServerSocketChannel.open();
        server.bind(new InetSocketAddress(4243));

        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread serving = new Thread(() -> {
            try {
                server.accept();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        serving.start();
        serving.join(200);
        Assertions.assertTrue(serving.isAlive());

        server.close();
        serving.join(5000);
        Assertions.assertFalse(serving.isAlive());
        Assertions.assertTrue(failure.get() instanceof java.nio.channels.AsynchronousCloseException,
                String.valueOf(failure.get()));
    }
}
