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
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;

/**
 * Static replacement for {@link ServerSocketChannel} entry points.
 *
 * <p>When the mock framework is enabled, {@link #open()} does not open a real listening
 * socket (which the sandbox refuses anyway): it returns an {@link EvoServerSocketChannel},
 * a channel backed by the EvoSuite virtual network. This keeps SUT code that calls
 * {@code ServerSocketChannel.open()} inside a {@code catch (Throwable)} block (a common
 * pattern) from having the channel object itself be {@code null}, which previously made
 * every subsequent use of the channel (bind, accept, isOpen) unreachable.
 */
public class MockServerSocketChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ServerSocketChannel.class.getName();
    }

    /**
     * Replacement for {@link ServerSocketChannel#open()}.
     *
     * @return the server socket channel
     * @throws IOException if an I/O error occurs
     */
    public static ServerSocketChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return ServerSocketChannel.open();
        }
        return new EvoServerSocketChannel();
    }

    /**
     * Replacement for {@link ServerSocketChannel#bind(SocketAddress)}.
     *
     * @param channel the channel
     * @param local   the local address
     * @return the server socket channel
     * @throws IOException if an I/O error occurs
     */
    public static ServerSocketChannel bind(ServerSocketChannel channel, SocketAddress local) throws IOException {
        return channel.bind(local);
    }

    /**
     * Replacement for {@link ServerSocketChannel#bind(SocketAddress, int)}.
     *
     * @param channel the channel
     * @param local   the local address
     * @param backlog the backlog
     * @return the server socket channel
     * @throws IOException if an I/O error occurs
     */
    public static ServerSocketChannel bind(ServerSocketChannel channel, SocketAddress local, int backlog)
            throws IOException {
        return channel.bind(local, backlog);
    }
}
