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

import org.evosuite.runtime.mock.java.io.MockIOException;
import org.evosuite.runtime.vnet.VirtualNetwork;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

/**
 * Selector provider shared by the virtual selectable channels.
 *
 * <p>The provider is selected per mocked call site. It is deliberately not installed through
 * the JVM provider system property: ordinary, non-mocked code continues to use the platform
 * provider in the same JVM.</p>
 */
public final class EvoSelectorProvider extends SelectorProvider {

    private static final EvoSelectorProvider INSTANCE = new EvoSelectorProvider();

    private EvoSelectorProvider() {
    }

    /** Return the provider used by all virtual selectable channels. */
    public static EvoSelectorProvider provider() {
        return INSTANCE;
    }

    /**
     * Returns {@code true} if the virtual network has moved past {@code openGeneration}, the
     * reset generation a channel was opened in. As a side effect, the first time this is
     * detected the channel is actually closed, so that {@code isOpen()} -- which is
     * {@code final} in {@link java.nio.channels.spi.AbstractInterruptibleChannel} and only
     * reflects the real closed flag -- starts reporting {@code false} too.
     */
    static boolean closeIfStale(long openGeneration, Closeable channel) {
        if (VirtualNetwork.getInstance().getResetGeneration() == openGeneration) {
            return false;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort; the channel is being reported as stale/closed regardless
        }
        return true;
    }

    @Override
    public DatagramChannel openDatagramChannel() throws IOException {
        throw new MockIOException("NIO datagram channels are disabled in mocked execution");
    }

    @Override
    public DatagramChannel openDatagramChannel(java.net.ProtocolFamily family) throws IOException {
        throw new MockIOException("NIO datagram channels are disabled in mocked execution");
    }

    @Override
    public Pipe openPipe() throws IOException {
        throw new MockIOException("NIO pipes are disabled in mocked execution");
    }

    @Override
    public AbstractSelector openSelector() throws IOException {
        return new EvoSelector(this);
    }

    @Override
    public ServerSocketChannel openServerSocketChannel() throws IOException {
        return new EvoServerSocketChannel();
    }

    @Override
    public SocketChannel openSocketChannel() throws IOException {
        return new EvoSocketChannel();
    }
}
