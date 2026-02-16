/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Deterministic replacement for {@link AsynchronousFileChannel} open entry points.
 */
public class MockAsynchronousFileChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return AsynchronousFileChannel.class.getName();
    }

    public static AsynchronousFileChannel open(Path path, OpenOption... options) throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousFileChannel.open(path, options);
        }
        FileChannel channel = MockFileChannel.open(path, options);
        return new DeterministicAsynchronousFileChannel(channel);
    }

    public static AsynchronousFileChannel open(
            Path path,
            Set<? extends OpenOption> options,
            ExecutorService executor,
            FileAttribute<?>... attrs) throws IOException {
        if (!MockFramework.isEnabled()) {
            return AsynchronousFileChannel.open(path, options, executor, attrs);
        }
        FileChannel channel = MockFileChannel.open(path, options, attrs);
        return new DeterministicAsynchronousFileChannel(channel);
    }

    private static final class DeterministicAsynchronousFileChannel extends AsynchronousFileChannel {

        private final FileChannel delegate;
        private volatile boolean open;

        private DeterministicAsynchronousFileChannel(FileChannel delegate) {
            this.delegate = delegate;
            this.open = true;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public AsynchronousFileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public <A> void lock(long position, long size, boolean shared, A attachment,
                             CompletionHandler<FileLock, ? super A> handler) {
            try {
                handler.completed(tryLock(position, size, shared), attachment);
            } catch (Throwable t) {
                handler.failed(t, attachment);
            }
        }

        @Override
        public Future<FileLock> lock(long position, long size, boolean shared) {
            try {
                return CompletableFuture.completedFuture(tryLock(position, size, shared));
            } catch (Throwable t) {
                CompletableFuture<FileLock> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        public <A> void read(ByteBuffer dst, long position, A attachment,
                             CompletionHandler<Integer, ? super A> handler) {
            try {
                handler.completed(delegate.read(dst, position), attachment);
            } catch (Throwable t) {
                handler.failed(t, attachment);
            }
        }

        @Override
        public Future<Integer> read(ByteBuffer dst, long position) {
            try {
                return CompletableFuture.completedFuture(delegate.read(dst, position));
            } catch (Throwable t) {
                CompletableFuture<Integer> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        @Override
        public <A> void write(ByteBuffer src, long position, A attachment,
                              CompletionHandler<Integer, ? super A> handler) {
            try {
                handler.completed(delegate.write(src, position), attachment);
            } catch (Throwable t) {
                handler.failed(t, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src, long position) {
            try {
                return CompletableFuture.completedFuture(delegate.write(src, position));
            } catch (Throwable t) {
                CompletableFuture<Integer> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        @Override
        public void close() throws IOException {
            open = false;
            delegate.close();
        }

        @Override
        public boolean isOpen() {
            return open && delegate.isOpen();
        }
    }
}
