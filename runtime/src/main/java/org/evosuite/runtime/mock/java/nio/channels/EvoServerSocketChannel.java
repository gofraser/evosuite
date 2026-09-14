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

import org.evosuite.runtime.mock.java.net.MockServerSocket;
import org.evosuite.runtime.mock.java.net.MockSocket;
import org.evosuite.runtime.vnet.VirtualNetwork;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@link ServerSocketChannel} backed by the EvoSuite virtual network.
 *
 * <p>Returned by {@link MockServerSocketChannel#open()} when the mock framework is enabled.
 * Rather than opening a real listening socket (which is refused by the sandbox, and in any
 * case would not be under EvoSuite's control), it wraps a {@link MockServerSocket}, so that
 * binding/accepting reuse the exact same virtual network machinery as {@code java.net.ServerSocket}.
 */
public class EvoServerSocketChannel extends ServerSocketChannel {

    private static final Set<SocketOption<?>> SUPPORTED_OPTIONS = supportedOptionsSet();

    private final MockServerSocket socket;

    private volatile boolean blocking = true;

    /**
     * The thread that opened the channel. A blocking accept() from this thread with
     * no simulated client pending fails at once, as the java.net mock does: the test
     * itself is calling and waiting would only burn its budget. Any other thread is
     * one the SUT started to serve connections, and for it accept() blocks like the
     * real thing until a client arrives, the channel is closed or the thread is
     * interrupted. Failing immediately there turns a server's
     * {@code while (running) { try { accept(); } catch (Throwable t) { log(t); } }}
     * into a hot loop that outlives the test and starves everything after it.
     */
    private final Thread openingThread = Thread.currentThread();

    /** How long a serving thread waits between checks for a pending client, in ms. */
    private static final long ACCEPT_POLL_MS = 20L;

    /**
     * The {@link VirtualNetwork} reset generation at the time this channel was opened.
     * Once {@link VirtualNetwork#reset()} has run again (ie the test that opened this
     * channel is over), the channel is treated as closed: nothing else ever closes a
     * channel handed to a background serving thread, so without this a thread stuck in
     * {@link #awaitClient()} would poll forever, long after its test ended.
     */
    private final long openGeneration = VirtualNetwork.getInstance().getResetGeneration();

    EvoServerSocketChannel() throws IOException {
        super(SelectorProvider.provider());
        this.socket = new MockServerSocket();
    }

    /**
     * Returns {@code true} if the virtual network has moved past the generation this
     * channel was opened in. As a side effect, the first time this is detected the
     * channel is actually closed, so that {@link #isOpen()} -- which is {@code final} in
     * {@link java.nio.channels.spi.AbstractInterruptibleChannel} and only reflects the
     * real closed flag -- starts reporting {@code false} too.
     */
    private boolean staleGeneration() {
        if (VirtualNetwork.getInstance().getResetGeneration() != openGeneration) {
            try {
                close();
            } catch (IOException ignored) {
                // best effort; the channel is being reported as stale/closed regardless
            }
            return true;
        }
        return false;
    }

    private static Set<SocketOption<?>> supportedOptionsSet() {
        Set<SocketOption<?>> set = new HashSet<>();
        set.add(StandardSocketOptions.SO_REUSEADDR);
        set.add(StandardSocketOptions.SO_RCVBUF);
        return Collections.unmodifiableSet(set);
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        socket.bind(local, backlog);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            socket.setReuseAddress((Boolean) value);
        } else if (name == StandardSocketOptions.SO_RCVBUF) {
            socket.setReceiveBufferSize((Integer) value);
        } else {
            throw new UnsupportedOperationException("Unsupported option: " + name);
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            return (T) Boolean.valueOf(socket.getReuseAddress());
        } else if (name == StandardSocketOptions.SO_RCVBUF) {
            return (T) Integer.valueOf(socket.getReceiveBufferSize());
        }
        throw new UnsupportedOperationException("Unsupported option: " + name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return SUPPORTED_OPTIONS;
    }

    @Override
    public ServerSocket socket() {
        return socket;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return socket.getLocalSocketAddress();
    }

    @Override
    public SocketChannel accept() throws IOException {
        if (staleGeneration()) {
            // The network -- and the test that opened this channel -- has moved on.
            // Throw the same exception a thread blocked in accept() gets when another
            // thread closes the channel from under it: this SUT (and well-behaved NIO
            // code in general) already handles AsynchronousCloseException quietly,
            // where a generic ClosedChannelException would fall into an expensive
            // catch-and-log-and-retry path.
            throw new AsynchronousCloseException();
        }
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        if (!socket.isBound()) {
            throw new NotYetBoundException();
        }

        Socket accepted;
        try {
            accepted = socket.accept();
        } catch (IOException e) {
            if (!blocking) {
                // real NIO semantics: a non-blocking accept() with nothing pending
                // returns null instead of blocking/throwing
                return null;
            }
            if (Thread.currentThread() == openingThread) {
                throw e;
            }
            accepted = awaitClient();
        }

        if (!(accepted instanceof MockSocket)) {
            // should never happen while the mock framework stays enabled for the
            // lifetime of this channel, but guard against a stray real Socket anyway
            throw new IOException("Unexpected non-mocked socket returned by accept()");
        }

        return EvoSocketChannel.wrap((MockSocket) accepted);
    }

    /**
     * Blocks a serving thread until a simulated client is pending, the channel is
     * closed or the thread is interrupted.
     */
    private Socket awaitClient() throws IOException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ClosedByInterruptException();
            }
            // Checked every poll (ACCEPT_POLL_MS = 20ms) so a reset unblocks a serving
            // thread promptly instead of leaving it polling forever after its test ended.
            if (staleGeneration() || !isOpen()) {
                throw new AsynchronousCloseException();
            }
            try {
                return socket.accept();
            } catch (IOException noClientYet) {
                try {
                    Thread.sleep(ACCEPT_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ClosedByInterruptException();
                }
            }
        }
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        socket.close();
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        this.blocking = block;
    }
}
