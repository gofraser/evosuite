/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;

/**
 * Static replacement for {@link AsynchronousServerSocketChannel} entry points.
 */
public class MockAsynchronousServerSocketChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return AsynchronousServerSocketChannel.class.getName();
    }

    /**
     * Replacement for {@link AsynchronousServerSocketChannel#open()}.
     *
     * @return the asynchronous server socket channel
     * @throws IOException if an I/O error occurs
     */
    public static AsynchronousServerSocketChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousServerSocketChannel.open();
        }
        throw new MockIOException("Asynchronous server socket channels are disabled in mocked execution");
    }

    /**
     * Replacement for {@link AsynchronousServerSocketChannel#open(AsynchronousChannelGroup)}.
     *
     * @param group the group
     * @return the asynchronous server socket channel
     * @throws IOException if an I/O error occurs
     */
    public static AsynchronousServerSocketChannel open(AsynchronousChannelGroup group) throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousServerSocketChannel.open(group);
        }
        throw new MockIOException("Asynchronous server socket channels are disabled in mocked execution");
    }
}
