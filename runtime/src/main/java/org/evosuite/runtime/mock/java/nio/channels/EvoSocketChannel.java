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

import org.evosuite.runtime.mock.java.net.MockSocket;
import org.evosuite.runtime.vnet.VirtualNetwork;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@link SocketChannel} backed by the EvoSuite virtual network.
 *
 * <p>Returned by {@link MockSocketChannel#open()}, and by {@link EvoServerSocketChannel#accept()}
 * to wrap an accepted {@link MockSocket}. Reads and writes go through the mock socket's
 * input/output streams, which are themselves backed by the {@code NativeTcp} buffers of the
 * virtual network.
 */
public class EvoSocketChannel extends SocketChannel {

    private static final Set<SocketOption<?>> SUPPORTED_OPTIONS = supportedOptionsSet();

    private final MockSocket socket;

    private volatile boolean blocking = true;

    private InputStream in;
    private OutputStream out;

    /**
     * The {@link VirtualNetwork} reset generation at the time this channel was created.
     * See {@link EvoServerSocketChannel#staleGeneration()} for why this matters: once the
     * network has moved past this generation, the test that owned this channel is over.
     */
    private final long openGeneration = VirtualNetwork.getInstance().getResetGeneration();

    EvoSocketChannel() {
        super(SelectorProvider.provider());
        this.socket = new MockSocket();
    }

    private EvoSocketChannel(MockSocket socket) {
        super(SelectorProvider.provider());
        this.socket = socket;
    }

    static EvoSocketChannel wrap(MockSocket accepted) {
        return new EvoSocketChannel(accepted);
    }

    /**
     * Returns {@code true} if the virtual network has moved past the generation this
     * channel was opened in, closing the channel as a side effect the first time this is
     * detected (see {@link EvoServerSocketChannel#staleGeneration()}).
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
        set.add(StandardSocketOptions.SO_SNDBUF);
        set.add(StandardSocketOptions.SO_KEEPALIVE);
        set.add(StandardSocketOptions.TCP_NODELAY);
        return Collections.unmodifiableSet(set);
    }

    @Override
    public SocketChannel bind(SocketAddress local) throws IOException {
        socket.bind(local);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            socket.setReuseAddress((Boolean) value);
        } else if (name == StandardSocketOptions.SO_RCVBUF) {
            socket.setReceiveBufferSize((Integer) value);
        } else if (name == StandardSocketOptions.SO_SNDBUF) {
            socket.setSendBufferSize((Integer) value);
        } else if (name == StandardSocketOptions.SO_KEEPALIVE) {
            socket.setKeepAlive((Boolean) value);
        } else if (name == StandardSocketOptions.TCP_NODELAY) {
            socket.setTcpNoDelay((Boolean) value);
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
        } else if (name == StandardSocketOptions.SO_SNDBUF) {
            return (T) Integer.valueOf(socket.getSendBufferSize());
        } else if (name == StandardSocketOptions.SO_KEEPALIVE) {
            return (T) Boolean.valueOf(socket.getKeepAlive());
        } else if (name == StandardSocketOptions.TCP_NODELAY) {
            return (T) Boolean.valueOf(socket.getTcpNoDelay());
        }
        throw new UnsupportedOperationException("Unsupported option: " + name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return SUPPORTED_OPTIONS;
    }

    @Override
    public Socket socket() {
        return socket;
    }

    @Override
    public boolean isConnected() {
        return socket.isConnected();
    }

    @Override
    public boolean isConnectionPending() {
        return false;
    }

    @Override
    public boolean connect(SocketAddress remote) throws IOException {
        if (isConnected()) {
            throw new AlreadyConnectedException();
        }
        socket.connect(remote);
        return true;
    }

    @Override
    public boolean finishConnect() throws IOException {
        if (!isConnected()) {
            throw new NoConnectionPendingException();
        }
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return socket.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return socket.getLocalSocketAddress();
    }

    @Override
    public SocketChannel shutdownInput() throws IOException {
        socket.shutdownInput();
        return this;
    }

    @Override
    public SocketChannel shutdownOutput() throws IOException {
        socket.shutdownOutput();
        return this;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        ensureOpenAndConnected();
        int remaining = dst.remaining();
        if (remaining == 0) {
            return 0;
        }
        byte[] buf = new byte[remaining];
        int n = in().read(buf, 0, remaining);
        if (n > 0) {
            dst.put(buf, 0, n);
        }
        return n;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        checkBufferRange(dsts, offset, length);
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            int wanted = dsts[i].remaining();
            if (wanted == 0) {
                continue;
            }
            int n = read(dsts[i]);
            if (n < 0) {
                return total > 0 ? total : -1;
            }
            total += n;
            if (n < wanted) {
                // the underlying stream had no more to give right now
                break;
            }
        }
        return total;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        ensureOpenAndConnected();
        int n = src.remaining();
        if (n == 0) {
            return 0;
        }
        byte[] buf = new byte[n];
        src.get(buf);
        out().write(buf);
        return n;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        checkBufferRange(srcs, offset, length);
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            total += write(srcs[i]);
        }
        return total;
    }

    private static void checkBufferRange(ByteBuffer[] buffers, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buffers.length) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void ensureOpenAndConnected() throws ClosedChannelException {
        if (staleGeneration() || !isOpen()) {
            throw new ClosedChannelException();
        }
        if (!isConnected()) {
            throw new NotYetConnectedException();
        }
    }

    private InputStream in() throws IOException {
        if (in == null) {
            synchronized (this) {
                if (in == null) {
                    in = socket.getInputStream();
                }
            }
        }
        return in;
    }

    private OutputStream out() throws IOException {
        if (out == null) {
            synchronized (this) {
                if (out == null) {
                    out = socket.getOutputStream();
                }
            }
        }
        return out;
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
