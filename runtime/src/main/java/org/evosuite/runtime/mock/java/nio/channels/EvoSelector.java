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

import org.evosuite.runtime.vnet.VirtualNetwork;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small selector for the virtual TCP channels.
 *
 * <p>It evaluates readiness from the virtual network state instead of using a native file
 * descriptor. Waiting is done on the virtual-network state monitor, which is signalled by
 * pending connections, virtual TCP writes, channel state changes, and network reset.</p>
 */
final class EvoSelector extends AbstractSelector {

    private final Map<SelectableChannel, EvoSelectionKey> keys = new LinkedHashMap<>();
    private final Set<SelectionKey> keySet = new LinkedHashSet<>();
    private final Set<SelectionKey> keySetView = Collections.unmodifiableSet(new KeysView());
    private final Set<SelectionKey> selectedKeys = new LinkedHashSet<>();
    private final Set<SelectionKey> selectedKeysView = new SelectedKeysView();
    private final Object selectLock = new Object();
    private final AtomicBoolean wakeup = new AtomicBoolean();
    private boolean readyPresent;

    EvoSelector(SelectorProvider provider) {
        super(provider);
    }

    @Override
    public Set<SelectionKey> keys() {
        ensureOpen();
        return keySetView;
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
        ensureOpen();
        return selectedKeysView;
    }

    @Override
    public int selectNow() throws IOException {
        return selectInternal(false, 0L);
    }

    @Override
    public int select(long timeout) throws IOException {
        if (timeout < 0L) {
            throw new IllegalArgumentException("Negative timeout");
        }
        return selectInternal(true, timeout);
    }

    @Override
    public int select() throws IOException {
        return selectInternal(true, 0L);
    }

    @Override
    public Selector wakeup() {
        wakeup.set(true);
        signalStateChange();
        return this;
    }

    @Override
    protected SelectionKey register(AbstractSelectableChannel channel, int ops, Object attachment) {
        if (channel.provider() != provider()) {
            throw new IllegalSelectorException();
        }
        synchronized (this) {
            ensureOpen();
            EvoSelectionKey existing = keys.get(channel);
            if (existing != null && existing.isValid()) {
                existing.attach(attachment);
                existing.interestOps(ops);
                return existing;
            }
            EvoSelectionKey key = new EvoSelectionKey(this, channel, ops, attachment);
            keys.put(channel, key);
            keySet.add(key);
            signalStateChange();
            return key;
        }
    }

    @Override
    protected void implCloseSelector() throws IOException {
        List<EvoSelectionKey> toDeregister;
        synchronized (this) {
            toDeregister = new ArrayList<>(keys.values());
        }
        for (EvoSelectionKey key : toDeregister) {
            deregister(key);
        }
        synchronized (this) {
            keys.clear();
            keySet.clear();
            selectedKeys.clear();
        }
        signalStateChange();
    }

    void signalStateChange() {
        VirtualNetwork.getInstance().signalStateChange();
    }

    private int selectInternal(boolean mayBlock, long timeout) throws IOException {
        synchronized (selectLock) {
            ensureOpen();
            begin();
            try {
                long timeoutNanos = timeout == 0L ? 0L : timeoutToNanos(timeout);
                long startNanos = timeoutNanos == 0L ? 0L : System.nanoTime();
                while (true) {
                    // Capture the version before checking predicates.  A wakeup or close
                    // racing this loop then changes the observed version and cannot be
                    // lost between the check and the monitor wait.
                    long observedVersion = VirtualNetwork.getInstance().getStateVersion();
                    ensureOpen();
                    processCancelledKeys();
                    if (wakeup.getAndSet(false)) {
                        return 0;
                    }

                    int ready = updateReadyKeys();
                    if (ready > 0 || readyPresent || !mayBlock) {
                        return ready;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return 0;
                    }

                    long waitMillis;
                    if (timeoutNanos == 0L) {
                        waitMillis = 0L;
                    } else {
                        long remaining = timeoutNanos - (System.nanoTime() - startNanos);
                        if (remaining <= 0L) {
                            return 0;
                        }
                        // Round up without adding to remaining: Long.MAX_VALUE plus the
                        // rounding unit would overflow and turn a long wait into 1ms polls.
                        waitMillis = Math.max(1L, 1L + (remaining - 1L) / 1000000L);
                    }

                    try {
                        VirtualNetwork.getInstance().awaitStateChange(observedVersion, waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return 0;
                    }
                }
            } finally {
                end();
            }
        }
    }

    private int updateReadyKeys() {
        List<Map.Entry<SelectableChannel, EvoSelectionKey>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(keys.entrySet());
        }

        Map<EvoSelectionKey, Integer> readiness = new LinkedHashMap<>();
        for (Map.Entry<SelectableChannel, EvoSelectionKey> entry : snapshot) {
            EvoSelectionKey key = entry.getValue();
            try {
                if (key.isValid()) {
                    readiness.put(key, readyOps(entry.getKey(), key.interestOps()));
                }
            } catch (CancelledKeyException ignored) {
                // Cancellation can race this snapshot.  The next sweep will
                // deregister it through AbstractSelector's cancelled-key queue.
            }
        }

        int updated = 0;
        readyPresent = false;
        synchronized (this) {
            for (Map.Entry<EvoSelectionKey, Integer> entry : readiness.entrySet()) {
                EvoSelectionKey key = entry.getKey();
                if (!key.isValid() || keys.get(key.channel()) != key) {
                    continue;
                }
                int ready = entry.getValue();
                if (ready != 0) {
                    readyPresent = true;
                }
                try {
                    int oldReady = key.readyOps();
                    if (selectedKeys.contains(key)) {
                        ready |= oldReady;
                        key.setReadyOps(ready);
                        if ((ready & ~oldReady) != 0) {
                            updated++;
                        }
                    } else {
                        key.setReadyOps(ready);
                        if (ready != 0) {
                            selectedKeys.add(key);
                            updated++;
                        }
                    }
                } catch (CancelledKeyException ignored) {
                    // A close/cancel between the validity check and readyOps
                    // must not make select fail for unrelated keys.
                }
            }
        }
        return updated;
    }

    private void processCancelledKeys() {
        List<EvoSelectionKey> cancelled = new ArrayList<>();
        Set<SelectionKey> cancelledKeys = cancelledKeys();
        synchronized (cancelledKeys) {
            for (SelectionKey key : cancelledKeys) {
                if (key instanceof EvoSelectionKey) {
                    cancelled.add((EvoSelectionKey) key);
                }
            }
        }
        for (EvoSelectionKey key : cancelled) {
            deregister(key);
            synchronized (this) {
                if (keys.get(key.channel()) == key) {
                    keys.remove(key.channel());
                }
                keySet.remove(key);
                selectedKeys.remove(key);
            }
        }
        synchronized (cancelledKeys) {
            cancelledKeys.removeAll(cancelled);
        }
    }

    private static long timeoutToNanos(long timeoutMillis) {
        if (timeoutMillis >= Long.MAX_VALUE / 1000000L) {
            return Long.MAX_VALUE;
        }
        return timeoutMillis * 1000000L;
    }

    /** Live, read-only key view whose iterators tolerate concurrent registration. */
    private final class KeysView extends AbstractSet<SelectionKey> {
        @Override
        public Iterator<SelectionKey> iterator() {
            synchronized (EvoSelector.this) {
                return new ArrayList<>(keySet).iterator();
            }
        }

        @Override
        public int size() {
            synchronized (EvoSelector.this) {
                return keySet.size();
            }
        }

        @Override
        public boolean contains(Object object) {
            synchronized (EvoSelector.this) {
                return keySet.contains(object);
            }
        }
    }

    private final class SelectedKeysView extends AbstractSet<SelectionKey> {
        @Override
        public Iterator<SelectionKey> iterator() {
            final List<SelectionKey> snapshot;
            synchronized (EvoSelector.this) {
                snapshot = new ArrayList<>(selectedKeys);
            }
            final Iterator<SelectionKey> iterator = snapshot.iterator();
            return new Iterator<SelectionKey>() {
                private SelectionKey current;

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public SelectionKey next() {
                    current = iterator.next();
                    return current;
                }

                @Override
                public void remove() {
                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    SelectedKeysView.this.remove(current);
                    current = null;
                }
            };
        }

        @Override
        public int size() {
            synchronized (EvoSelector.this) {
                return selectedKeys.size();
            }
        }

        @Override
        public boolean contains(Object object) {
            synchronized (EvoSelector.this) {
                return selectedKeys.contains(object);
            }
        }

        @Override
        public boolean remove(Object object) {
            synchronized (EvoSelector.this) {
                return selectedKeys.remove(object);
            }
        }

        @Override
        public void clear() {
            synchronized (EvoSelector.this) {
                selectedKeys.clear();
            }
        }

        @Override
        public boolean add(SelectionKey key) {
            throw new UnsupportedOperationException("selected key set does not support add");
        }
    }

    private static int readyOps(SelectableChannel channel, int interestOps) {
        int ready = 0;
        if (channel instanceof EvoServerSocketChannel) {
            if ((interestOps & SelectionKey.OP_ACCEPT) != 0
                    && ((EvoServerSocketChannel) channel).isAcceptReady()) {
                ready |= SelectionKey.OP_ACCEPT;
            }
        } else if (channel instanceof EvoSocketChannel) {
            EvoSocketChannel socket = (EvoSocketChannel) channel;
            if ((interestOps & SelectionKey.OP_READ) != 0 && socket.isReadReady()) {
                ready |= SelectionKey.OP_READ;
            }
            if ((interestOps & SelectionKey.OP_WRITE) != 0 && socket.isWriteReady()) {
                ready |= SelectionKey.OP_WRITE;
            }
            if ((interestOps & SelectionKey.OP_CONNECT) != 0 && socket.isConnectionReady()) {
                ready |= SelectionKey.OP_CONNECT;
            }
        }
        return ready;
    }

    private void ensureOpen() {
        if (!isOpen()) {
            throw new ClosedSelectorException();
        }
    }
}
