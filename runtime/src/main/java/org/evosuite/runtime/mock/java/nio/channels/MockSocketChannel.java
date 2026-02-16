/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

/**
 * Static replacement for {@link SocketChannel} entry points.
 */
public class MockSocketChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return SocketChannel.class.getName();
    }

    public static SocketChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return SocketChannel.open();
        }
        throw new MockIOException("NIO socket channels are disabled in mocked execution");
    }

    public static SocketChannel open(SocketAddress remote) throws IOException {
        if (!MockFramework.isEnabled()) {
            return SocketChannel.open(remote);
        }
        throw new MockIOException("NIO socket channels are disabled in mocked execution");
    }

    public static boolean connect(SocketChannel channel, SocketAddress remote) throws IOException {
        if (!MockFramework.isEnabled()) {
            return channel.connect(remote);
        }
        throw new MockIOException("NIO socket channels are disabled in mocked execution");
    }

    public static SocketChannel bind(SocketChannel channel, SocketAddress local) throws IOException {
        if (!MockFramework.isEnabled()) {
            return channel.bind(local);
        }
        throw new MockIOException("NIO socket channels are disabled in mocked execution");
    }
}
