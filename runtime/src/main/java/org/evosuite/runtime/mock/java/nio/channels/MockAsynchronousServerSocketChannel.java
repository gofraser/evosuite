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
