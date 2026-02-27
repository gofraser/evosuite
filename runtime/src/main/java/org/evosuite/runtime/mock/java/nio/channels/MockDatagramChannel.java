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
import java.net.ProtocolFamily;
import java.nio.channels.DatagramChannel;

/**
 * Static replacement for {@link DatagramChannel} entry points.
 */
public class MockDatagramChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return DatagramChannel.class.getName();
    }

    /**
     * Replacement for {@link DatagramChannel#open()}.
     *
     * @return the datagram channel
     * @throws IOException if an I/O error occurs
     */
    public static DatagramChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return DatagramChannel.open();
        }
        throw new MockIOException("NIO datagram channels are disabled in mocked execution");
    }

    /**
     * Replacement for {@link DatagramChannel#open(ProtocolFamily)}.
     *
     * @param family the family
     * @return the datagram channel
     * @throws IOException if an I/O error occurs
     */
    public static DatagramChannel open(ProtocolFamily family) throws IOException {
        if (!MockFramework.isEnabled()) {
            return DatagramChannel.open(family);
        }
        throw new MockIOException("NIO datagram channels are disabled in mocked execution");
    }
}
