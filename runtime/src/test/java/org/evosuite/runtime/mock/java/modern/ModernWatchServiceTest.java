/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.modern;

import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic WatchService checks for Java11+ mocks.
 */
public class ModernWatchServiceTest {

    private boolean oldUseVfs;
    private boolean oldMockFramework;

    @BeforeEach
    public void setUp() {
        oldUseVfs = RuntimeSettings.useVFS;
        oldMockFramework = MockFramework.isEnabled();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.useVFS = oldUseVfs;
        if (oldMockFramework) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    public void testWatchServiceQueuesEventsAcrossResetCycle() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")
                || !isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioPath")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Path watchedDir = Paths.get("watch-service-queue-test");
        try (WatchService watcher = newWatcher()) {
            WatchKey key = registerCreateWatcher(watchedDir, watcher);

            emit(watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, Paths.get("a.txt"));
            emit(watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, Paths.get("b.txt"));

            WatchKey firstSignal = watcher.poll(1L, TimeUnit.SECONDS);
            Assertions.assertNotNull(firstSignal);
            Assertions.assertEquals(key, firstSignal);
            List<WatchEvent<?>> firstEvents = firstSignal.pollEvents();
            Assertions.assertEquals(2, firstEvents.size());

            emit(watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, Paths.get("c.txt"));
            Assertions.assertTrue(firstSignal.reset());

            WatchKey secondSignal = watcher.poll(1L, TimeUnit.SECONDS);
            Assertions.assertNotNull(secondSignal);
            List<WatchEvent<?>> secondEvents = secondSignal.pollEvents();
            Assertions.assertEquals(1, secondEvents.size());
            Assertions.assertEquals(Paths.get("c.txt"), secondEvents.get(0).context());
            Assertions.assertTrue(secondSignal.reset());
        }
    }

    @Test
    public void testWatchKeyCancelAndCloseSemantics() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")
                || !isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioPath")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Path watchedDir = Paths.get("watch-service-cancel-test");
        WatchService watcher = newWatcher();
        WatchKey key = registerCreateWatcher(watchedDir, watcher);

        key.cancel();
        Assertions.assertFalse(key.isValid());

        emit(watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, Paths.get("ignored.txt"));
        Assertions.assertNull(watcher.poll());

        watcher.close();
        try {
            watcher.poll();
            Assertions.fail("Expected poll() on closed watcher to throw");
        } catch (ClosedWatchServiceException expected) {
            // expected
        }
    }

    @Test
    public void testWatchServiceOverflowEventDelivery() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")
                || !isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioPath")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Path watchedDir = Paths.get("watch-service-overflow-test");
        try (WatchService watcher = newWatcher()) {
            WatchKey key = registerWatcher(watchedDir, watcher, new WatchEvent.Kind[]{
                    StandardWatchEventKinds.OVERFLOW
            });

            emit(watcher, watchedDir, StandardWatchEventKinds.OVERFLOW, null);
            WatchKey signalled = watcher.poll(1L, TimeUnit.SECONDS);
            Assertions.assertNotNull(signalled);
            Assertions.assertEquals(key, signalled);

            List<WatchEvent<?>> events = signalled.pollEvents();
            Assertions.assertEquals(1, events.size());
            Assertions.assertEquals(StandardWatchEventKinds.OVERFLOW, events.get(0).kind());
            Assertions.assertNull(events.get(0).context());
            Assertions.assertTrue(signalled.reset());
        }
    }

    @Test
    public void testWatchServiceTakeUnblocksOnInjectedEvent() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")
                || !isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioPath")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Path watchedDir = Paths.get("watch-service-take-test");
        try (WatchService watcher = newWatcher()) {
            registerCreateWatcher(watchedDir, watcher);

            FutureTask<WatchKey> waitTask = new FutureTask<>(watcher::take);
            Thread t = new Thread(waitTask, "watch-take-test");
            t.start();

            java.lang.Thread.sleep(50L);
            emit(watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, Paths.get("take.txt"));

            WatchKey key = waitTask.get(1L, TimeUnit.SECONDS);
            Assertions.assertNotNull(key);
            List<WatchEvent<?>> events = key.pollEvents();
            Assertions.assertEquals(1, events.size());
            Assertions.assertEquals(Paths.get("take.txt"), events.get(0).context());
            Assertions.assertTrue(key.reset());
            t.join(1000L);
            Assertions.assertFalse(t.isAlive());
        }
    }

    private static WatchService newWatcher() throws Exception {
        Class<?> mockFileSystemClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem");
        FileSystem fs = FileSystems.getDefault();
        return (WatchService) mockFileSystemClass.getMethod("newWatchService", FileSystem.class).invoke(null, fs);
    }

    private static WatchKey registerCreateWatcher(Path path, WatchService watcher) throws Exception {
        return registerWatcher(path, watcher, new WatchEvent.Kind[]{
                StandardWatchEventKinds.ENTRY_CREATE
        });
    }

    private static WatchKey registerWatcher(Path path, WatchService watcher, WatchEvent.Kind<?>[] kinds)
            throws Exception {
        Class<?> mockPathClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioPath");
        return (WatchKey) mockPathClass
                .getMethod("register", Path.class, WatchService.class, WatchEvent.Kind[].class)
                .invoke(null, path, watcher, (Object) kinds);
    }

    private static void emit(WatchService watcher, Path watchedPath, WatchEvent.Kind<?> kind, Path context)
            throws Exception {
        Class<?> mockFileSystemClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem");
        mockFileSystemClass
                .getMethod("emitEvent", WatchService.class, Path.class, WatchEvent.Kind.class, Path.class)
                .invoke(null, watcher, watchedPath, kind, context);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
