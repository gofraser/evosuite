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
package com.examples.selector;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.nio.channels.EvoServerSocketChannel;
import org.evosuite.runtime.mock.java.nio.channels.EvoSocketChannel;
import org.evosuite.runtime.mock.java.nio.channels.EvoSelectorProvider;
import org.evosuite.runtime.mock.java.nio.channels.EvoSelectorInstrumentedTest;
import org.evosuite.runtime.testdata.EvoSuiteLocalAddress;
import org.evosuite.runtime.testdata.NetworkHandling;
import org.evosuite.runtime.vnet.EndPointInfo;
import org.evosuite.runtime.vnet.RemoteTcpServer;
import org.evosuite.runtime.vnet.VirtualNetwork;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Ordinary JDK calls in this class are transformed after agent activation. */
public final class EvoSelectorInstrumentedProbe {

    private EvoSelectorInstrumentedProbe() { }

    private static RemoteTcpServer remoteServer() {
        RemoteTcpServer remote = new RemoteTcpServer(new EndPointInfo(
                "192.168.0.50", 8262, VirtualNetwork.ConnectionType.TCP));
        VirtualNetwork.getInstance().addRemoteTcpServer(remote);
        return remote;
    }

    private static void connect(SocketChannel client) throws Exception {
        client.configureBlocking(false);
        client.bind(new InetSocketAddress("127.0.0.1", 0));
        assertTrue(client.connect(new InetSocketAddress("192.168.0.50", 8262)));
        assertTrue(client.isConnected());
        assertFalse(client.isConnectionPending());
        assertTrue(client.finishConnect());
    }

    public static void ordinaryFactoriesShareVirtualProviderAndExchangeData() throws Exception {
        SelectorProvider provider = SelectorProvider.provider();
        RemoteTcpServer remote = remoteServer();
        try (Selector selector = Selector.open();
             Selector providerSelector = provider.openSelector();
             ServerSocketChannel server = ServerSocketChannel.open();
             ServerSocketChannel providerServer = provider.openServerSocketChannel();
             SocketChannel client = SocketChannel.open();
             SocketChannel providerClient = provider.openSocketChannel()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("127.0.0.1", 8265));
            server.register(selector, SelectionKey.OP_ACCEPT);
            assertTrue(provider instanceof EvoSelectorProvider);
            assertSame(provider, selector.provider());
            assertSame(provider, providerSelector.provider());
            assertTrue(server instanceof EvoServerSocketChannel);
            assertTrue(providerServer instanceof EvoServerSocketChannel);
            assertTrue(client instanceof EvoSocketChannel);
            assertTrue(providerClient instanceof EvoSocketChannel);
            assertSame(provider, server.provider());
            assertSame(provider, client.provider());
            connect(client);
            SelectionKey key = client.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE);
            assertEquals(1, selector.selectNow());
            assertFalse(key.isConnectable());
            assertTrue(key.isWritable());
            assertEquals(5, client.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8))));
            assertEquals("hello", remote.getAllReceivedDataAsString());
            key.interestOps(SelectionKey.OP_READ);
            selector.selectedKeys().clear();
            assertEquals(0, selector.selectNow());
            assertTrue(remote.sendMessage("reply"));
            assertEquals(1, selector.selectNow());
            assertTrue(key.isReadable());
            ByteBuffer bytes = ByteBuffer.allocate(5);
            assertEquals(5, client.read(bytes));
            assertEquals("reply", new String(bytes.array(), StandardCharsets.UTF_8));
            client.shutdownInput();
            selector.selectedKeys().clear();
            assertEquals(1, selector.selectNow());
            assertTrue(key.isReadable());
            assertEquals(-1, client.read(ByteBuffer.allocate(1)));
            client.shutdownOutput();
            selector.selectedKeys().clear();
            assertEquals(1, selector.selectNow());
            assertTrue(key.isReadable());
            assertEquals(-1, client.read(ByteBuffer.allocate(1)));
        }
    }

    public static void selectedKeysRetainReadinessUntilRemovedAndCancelledKeysDeregister()
            throws Exception {
        remoteServer();
        try (Selector selector = Selector.open(); SocketChannel client = SocketChannel.open()) {
            connect(client);
            Set<SelectionKey> keysView = selector.keys();
            assertTrue(keysView.isEmpty());
            Object attachment = new Object();
            SelectionKey key = client.register(selector, SelectionKey.OP_WRITE, attachment);
            assertTrue(keysView.contains(key));
            assertSame(attachment, key.attachment());
            assertSame(key, client.keyFor(selector));
            assertEquals(1, selector.selectNow());
            assertTrue(key.isWritable());
            assertEquals(0, selector.selectNow());
            assertEquals(0, selector.select(50L));
            assertTrue(selector.selectedKeys().contains(key));
            key.interestOps(0);
            assertEquals(0, selector.selectNow());
            assertTrue(key.isWritable()); // previously selected readiness is retained
            assertTrue(selector.selectedKeys().remove(key));
            assertEquals(0, selector.selectNow());
            assertEquals(0, key.readyOps());
            key.interestOps(SelectionKey.OP_WRITE);
            assertEquals(1, selector.selectNow());
            assertThrows(UnsupportedOperationException.class, () -> selector.selectedKeys().add(key));
            assertThrows(UnsupportedOperationException.class, () -> selector.keys().remove(key));
            key.cancel();
            assertFalse(key.isValid());
            assertThrows(CancelledKeyException.class, key::interestOps);
            assertThrows(CancelledKeyException.class, key::readyOps);
            assertThrows(CancelledKeyException.class, () -> key.interestOps(SelectionKey.OP_READ));
            assertTrue(client.isRegistered());
            selector.wakeup();
            assertEquals(0, selector.selectNow());
            assertFalse(client.isRegistered());
            assertNull(client.keyFor(selector));
            assertFalse(keysView.contains(key));
            assertFalse(selector.keys().contains(key));
            assertFalse(selector.selectedKeys().contains(key));
            SelectionKey replacement = client.register(selector, SelectionKey.OP_WRITE);
            assertNotSame(key, replacement);
            assertTrue(keysView.contains(replacement));
            assertSame(replacement, client.keyFor(selector));
            assertEquals(1, selector.selectNow());
        }
    }

    public static void blockedSelectWakesForIncomingConnectionAndClientData() throws Exception {
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("127.0.0.1", 8260));
            assertThrows(IllegalArgumentException.class, () -> server.register(selector, SelectionKey.OP_READ));
            SelectionKey acceptKey = server.register(selector, SelectionKey.OP_ACCEPT);
            SelectionWait acceptWait = new SelectionWait(selector, 0L);
            try {
                acceptWait.startAndAwaitBlocked();
                assertTrue(NetworkHandling.sendDataOnTcp(new EvoSuiteLocalAddress("127.0.0.1", 8260),
                        "hello".getBytes(StandardCharsets.UTF_8)));
                acceptWait.assertResult(1);
                assertTrue(acceptKey.isAcceptable());
                try (SocketChannel accepted = server.accept()) {
                    assertNotNull(accepted);
                    assertTrue(accepted instanceof EvoSocketChannel);
                    accepted.configureBlocking(false);
                    selector.selectedKeys().clear();
                    SelectionKey readKey = accepted.register(selector, SelectionKey.OP_READ);
                    assertEquals(1, selector.selectNow());
                    assertTrue(readKey.isReadable());
                    ByteBuffer bytes = ByteBuffer.allocate(5);
                    assertEquals(5, accepted.read(bytes));
                    assertEquals("hello", new String(bytes.array(), StandardCharsets.UTF_8));
                    accepted.close();
                    assertFalse(readKey.isValid());
                    selector.selectNow();
                    assertFalse(selector.keys().contains(readKey));
                }
            } finally {
                acceptWait.stop();
            }
        }
        RemoteTcpServer remote = remoteServer();
        try (Selector selector = Selector.open(); SocketChannel client = SocketChannel.open()) {
            connect(client);
            SelectionKey key = client.register(selector, SelectionKey.OP_READ);
            SelectionWait readWait = new SelectionWait(selector, 0L);
            try {
                readWait.startAndAwaitBlocked();
                assertTrue(remote.sendMessage("data"));
                readWait.assertResult(1);
                assertTrue(key.isReadable());
                assertEquals(4, client.read(ByteBuffer.allocate(4)));
            } finally {
                readWait.stop();
            }
        }
    }

    public static void blockedReadWakesForSocketInputShutdown() throws Exception {
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("127.0.0.1", 8266));
            assertTrue(NetworkHandling.sendDataOnTcp(new EvoSuiteLocalAddress("127.0.0.1", 8266),
                    new byte[] {1}));
            try (SocketChannel accepted = server.accept()) {
                assertNotNull(accepted);
                accepted.configureBlocking(false);
                assertEquals(1, accepted.read(ByteBuffer.allocate(1)));
                SelectionKey key = accepted.register(selector, SelectionKey.OP_READ);
                SelectionWait wait = new SelectionWait(selector, 0L);
                try {
                    wait.startAndAwaitBlocked();
                    accepted.socket().shutdownInput();
                    wait.assertResult(1);
                    assertTrue(key.isReadable());
                    assertEquals(-1, accepted.read(ByteBuffer.allocate(1)));
                } finally {
                    wait.stop();
                }
            }
        }
    }

    public static void longTimeoutWakeupInterruptAndCloseReleaseBlockedSelection() throws Exception {
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("127.0.0.1", 8267));
            SelectionKey key = server.register(selector, SelectionKey.OP_ACCEPT);
            assertThrows(IllegalArgumentException.class, () -> selector.select(-1L));
            selector.wakeup();
            assertEquals(0, selector.select(Long.MAX_VALUE));
            SelectionWait wait = new SelectionWait(selector, Long.MAX_VALUE);
            try {
                wait.startAndAwaitBlocked();
                selector.wakeup();
                wait.assertResult(0);
            } finally {
                wait.stop();
            }
            SelectionWait interrupted = new SelectionWait(selector, 0L);
            try {
                interrupted.startAndAwaitBlocked();
                interrupted.thread.interrupt();
                interrupted.assertResult(0);
                assertTrue(interrupted.interrupted);
            } finally {
                interrupted.stop();
            }
            // AbstractSelector's interruptor may leave a wakeup for the next selection.
            assertEquals(0, selector.selectNow());
            SelectionWait closed = new SelectionWait(selector, 0L);
            try {
                closed.startAndAwaitBlocked();
                selector.close();
                closed.join();
                assertTrue(closed.failure.get() instanceof ClosedSelectorException);
            } finally {
                closed.stop();
            }
            assertFalse(key.isValid());
            assertFalse(server.isRegistered());
            assertNull(server.keyFor(selector));
            assertThrows(ClosedSelectorException.class, selector::keys);
            assertThrows(ClosedSelectorException.class, selector::selectedKeys);
        }
    }

    public static void disabledFactoriesAndForeignProvidersRemainNative() throws Exception {
        try (Selector virtualSelector = Selector.open(); SocketChannel virtualChannel = SocketChannel.open()) {
            virtualChannel.configureBlocking(false);
            MockFramework.disable();
            try (Selector nativeSelector = Selector.open(); SocketChannel nativeChannel = SocketChannel.open()) {
                assertFalse(nativeSelector.provider() instanceof EvoSelectorProvider);
                assertFalse(nativeChannel instanceof EvoSocketChannel);
                nativeChannel.configureBlocking(false);
                assertThrows(IllegalSelectorException.class,
                        () -> nativeChannel.register(virtualSelector, SelectionKey.OP_READ));
                assertThrows(IllegalSelectorException.class,
                        () -> virtualChannel.register(nativeSelector, SelectionKey.OP_READ));
            } finally {
                MockFramework.enable();
            }
        }
    }

    private static final class SelectionWait {
        private final Selector selector;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        private volatile int result = -1;
        private volatile boolean interrupted;

        SelectionWait(Selector selector, long timeout) {
            this.selector = selector;
            thread = new Thread(() -> {
                try {
                    result = selector.select(timeout);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    interrupted = Thread.currentThread().isInterrupted();
                }
            }, "virtual-selector-boundary");
            thread.setDaemon(true);
        }

        void startAndAwaitBlocked() throws Exception {
            thread.start();
            EvoSelectorInstrumentedTest.awaitSelectorWait(thread);
        }

        void join() throws Exception {
            thread.join(5000L);
            assertFalse("selector worker remains blocked", thread.isAlive());
        }

        void assertResult(int expected) throws Exception {
            join();
            if (failure.get() != null) {
                throw new AssertionError("selection failed", failure.get());
            }
            assertEquals(expected, result);
        }

        void stop() throws Exception {
            if (thread.isAlive()) {
                selector.wakeup();
                thread.interrupt();
                thread.join(5000L);
            }
            assertFalse("selector worker leaked", thread.isAlive());
        }
    }
}
