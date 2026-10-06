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

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectionKey;

/** Selection key owned by an {@link EvoSelector}. */
final class EvoSelectionKey extends AbstractSelectionKey {

    private final EvoSelector selector;
    private final SelectableChannel channel;
    private int interestOps;
    private int readyOps;

    EvoSelectionKey(EvoSelector selector, SelectableChannel channel, int interestOps, Object attachment) {
        this.selector = selector;
        this.channel = channel;
        this.interestOps = interestOps;
        attach(attachment);
    }

    @Override
    public SelectableChannel channel() {
        return channel;
    }

    @Override
    public Selector selector() {
        return selector;
    }

    @Override
    public synchronized int interestOps() {
        if (!isValid()) {
            throw new java.nio.channels.CancelledKeyException();
        }
        return interestOps;
    }

    @Override
    public synchronized SelectionKey interestOps(int ops) {
        if (!isValid()) {
            throw new java.nio.channels.CancelledKeyException();
        }
        if ((ops & ~channel.validOps()) != 0) {
            throw new IllegalArgumentException("Invalid interest operation: " + ops);
        }
        interestOps = ops;
        selector.signalStateChange();
        return this;
    }

    @Override
    public synchronized int readyOps() {
        if (!isValid()) {
            throw new java.nio.channels.CancelledKeyException();
        }
        return readyOps;
    }

    synchronized void setReadyOps(int readyOps) {
        this.readyOps = readyOps;
    }

}
