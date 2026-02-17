/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
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
