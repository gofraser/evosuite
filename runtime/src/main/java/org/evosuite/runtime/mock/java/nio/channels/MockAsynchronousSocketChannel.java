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
import java.nio.channels.AsynchronousSocketChannel;

/**
 * Static replacement for {@link AsynchronousSocketChannel} entry points.
 */
public class MockAsynchronousSocketChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return AsynchronousSocketChannel.class.getName();
    }

    /**
     * Replacement for {@link AsynchronousSocketChannel#open()}.
     *
     * @return the asynchronous socket channel
     * @throws IOException if an I/O error occurs
     */
    public static AsynchronousSocketChannel open() throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousSocketChannel.open();
        }
        throw new MockIOException("Asynchronous socket channels are disabled in mocked execution");
    }

    /**
     * Replacement for {@link AsynchronousSocketChannel#open(AsynchronousChannelGroup)}.
     *
     * @param group the group
     * @return the asynchronous socket channel
     * @throws IOException if an I/O error occurs
     */
    public static AsynchronousSocketChannel open(AsynchronousChannelGroup group) throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousSocketChannel.open(group);
        }
        throw new MockIOException("Asynchronous socket channels are disabled in mocked execution");
    }
}
