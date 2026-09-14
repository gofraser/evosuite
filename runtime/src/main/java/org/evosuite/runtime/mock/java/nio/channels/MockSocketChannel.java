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
import java.nio.channels.SocketChannel;

/**
 * Static replacement for {@link SocketChannel} entry points.
 *
 * <p>When the mock framework is enabled, {@link #open()} returns an {@link EvoSocketChannel}
 * backed by the EvoSuite virtual network, rather than throwing: SUT code that expects a real
 * (if unconnected) channel object back from {@code SocketChannel.open()} can then go on to
 * call {@code connect}/{@code read}/{@code write} on it as normal.
 */
public class MockSocketChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return SocketChannel.class.getName();
    }

    /**
     * Replacement for {@link SocketChannel#open()}.
     *
     * @return the socket channel
     * @throws IOException if an I/O error occurs
     */
    public static SocketChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return SocketChannel.open();
        }
        return new EvoSocketChannel();
    }

    /**
     * Replacement for {@link SocketChannel#open(SocketAddress)}.
     *
     * @param remote the remote address
     * @return the socket channel
     * @throws IOException if an I/O error occurs
     */
    public static SocketChannel open(SocketAddress remote) throws IOException {
        if (!MockFramework.isEnabled()) {
            return SocketChannel.open(remote);
        }
        SocketChannel channel = new EvoSocketChannel();
        channel.connect(remote);
        return channel;
    }

    /**
     * Replacement for {@link SocketChannel#connect(SocketAddress)}.
     *
     * @param channel the channel
     * @param remote  the remote address
     * @return true if connected
     * @throws IOException if an I/O error occurs
     */
    public static boolean connect(SocketChannel channel, SocketAddress remote) throws IOException {
        return channel.connect(remote);
    }

    /**
     * Replacement for {@link SocketChannel#bind(SocketAddress)}.
     *
     * @param channel the channel
     * @param local   the local address
     * @return the socket channel
     * @throws IOException if an I/O error occurs
     */
    public static SocketChannel bind(SocketChannel channel, SocketAddress local) throws IOException {
        return channel.bind(local);
    }
}
