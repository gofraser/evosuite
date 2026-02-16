/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.file;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class DeterministicWatchService implements WatchService {

    private final BlockingQueue<DeterministicWatchKey> queue = new LinkedBlockingQueue<>();
    private final Map<String, DeterministicWatchKey> keyByPath = new HashMap<>();
    private volatile boolean open = true;

    WatchKey register(Path path, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throwIfClosed();
        if (path == null) {
            throw new NullPointerException("path");
        }
        if (events == null) {
            throw new NullPointerException("events");
        }
        if (events.length == 0) {
            throw new IllegalArgumentException("No watch event kinds supplied");
        }

        String key = normalize(path);
        synchronized (this) {
            DeterministicWatchKey existing = keyByPath.get(key);
            if (existing != null && existing.isValid()) {
                existing.setEventKinds(events);
                return existing;
            }

            DeterministicWatchKey created = new DeterministicWatchKey(this, path, events);
            keyByPath.put(key, created);
            return created;
        }
    }

    void emit(Path watchedPath, WatchEvent.Kind<?> kind, Path context) {
        throwIfClosed();
        if (watchedPath == null || kind == null) {
            throw new NullPointerException();
        }

        DeterministicWatchKey key;
        synchronized (this) {
            key = keyByPath.get(normalize(watchedPath));
        }
        if (key == null || !key.isValid() || !key.accepts(kind)) {
            return;
        }

        Path ctx = (kind == java.nio.file.StandardWatchEventKinds.OVERFLOW)
                ? null
                : ((context != null) ? context : watchedPath.getFileName());
        key.addEvent(new DeterministicWatchEvent(kind, ctx));
        enqueueIfNeeded(key);
    }

    @Override
    public WatchKey poll() {
        throwIfClosed();
        return queue.poll();
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        throwIfClosed();
        return queue.poll(timeout, unit);
    }

    @Override
    public WatchKey take() throws InterruptedException {
        throwIfClosed();
        return queue.take();
    }

    @Override
    public void close() {
        open = false;
        synchronized (this) {
            for (DeterministicWatchKey key : keyByPath.values()) {
                key.invalidate();
            }
            keyByPath.clear();
        }
        queue.clear();
    }

    boolean isOpen() {
        return open;
    }

    void unregister(DeterministicWatchKey key) {
        synchronized (this) {
            keyByPath.remove(normalize((Path) key.watchable()));
        }
    }

    void enqueueIfNeeded(DeterministicWatchKey key) {
        if (!open || !key.isValid()) {
            return;
        }
        if (key.markSignalled()) {
            queue.offer(key);
        }
    }

    private void throwIfClosed() {
        if (!open) {
            throw new ClosedWatchServiceException();
        }
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static final class DeterministicWatchKey implements WatchKey {

        private final DeterministicWatchService owner;
        private final Path path;
        private final List<WatchEvent<?>> events = new ArrayList<>();
        private volatile boolean valid = true;
        private volatile boolean signalled = false;
        private Set<WatchEvent.Kind<?>> kinds = new HashSet<>();

        private DeterministicWatchKey(DeterministicWatchService owner, Path path, WatchEvent.Kind<?>[] eventKinds) {
            this.owner = owner;
            this.path = path;
            setEventKinds(eventKinds);
        }

        private synchronized void setEventKinds(WatchEvent.Kind<?>[] eventKinds) {
            kinds = new HashSet<>();
            for (WatchEvent.Kind<?> kind : eventKinds) {
                if (kind == null) {
                    throw new NullPointerException("kind");
                }
                if (kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_DELETE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY
                        || kind == StandardWatchEventKinds.OVERFLOW) {
                    kinds.add(kind);
                }
            }
        }

        private synchronized boolean accepts(WatchEvent.Kind<?> kind) {
            return kinds.contains(kind);
        }

        private synchronized void addEvent(WatchEvent<?> event) {
            events.add(event);
        }

        private synchronized boolean markSignalled() {
            if (signalled) {
                return false;
            }
            signalled = true;
            return true;
        }

        private synchronized void invalidate() {
            valid = false;
            signalled = false;
            events.clear();
        }

        @Override
        public synchronized boolean isValid() {
            return valid && owner.isOpen();
        }

        @Override
        public synchronized List<WatchEvent<?>> pollEvents() {
            List<WatchEvent<?>> copy = new ArrayList<>(events);
            events.clear();
            return copy;
        }

        @Override
        public synchronized boolean reset() {
            if (!isValid()) {
                return false;
            }

            signalled = false;
            if (!events.isEmpty()) {
                owner.enqueueIfNeeded(this);
            }
            return true;
        }

        @Override
        public synchronized void cancel() {
            if (!valid) {
                return;
            }
            valid = false;
            signalled = false;
            events.clear();
            owner.unregister(this);
        }

        @Override
        public Watchable watchable() {
            return path;
        }
    }

    private static final class DeterministicWatchEvent implements WatchEvent<Path> {

        private final Kind<Path> kind;
        private final Path context;

        @SuppressWarnings("unchecked")
        private DeterministicWatchEvent(Kind<?> kind, Path context) {
            this.kind = (Kind<Path>) kind;
            this.context = context;
        }

        @Override
        public Kind<Path> kind() {
            return kind;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Path context() {
            return context;
        }
    }

}
