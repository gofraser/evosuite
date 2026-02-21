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
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.java.io.MockIOException;
import org.evosuite.runtime.mock.java.lang.MockRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Reflection-driven checks for Java11+ mocks that keep test sources Java 8 compatible.
 */
public class ModernMocksTest {

    private boolean oldUseVfs;
    private boolean oldUseVnet;
    private boolean oldMockJvm;
    private boolean oldMockFramework;

    @BeforeEach
    public void setUp() {
        oldUseVfs = RuntimeSettings.useVFS;
        oldUseVnet = RuntimeSettings.useVNET;
        oldMockJvm = RuntimeSettings.mockJVMNonDeterminism;
        oldMockFramework = MockFramework.isEnabled();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.useVFS = oldUseVfs;
        RuntimeSettings.useVNET = oldUseVnet;
        RuntimeSettings.mockJVMNonDeterminism = oldMockJvm;
        if (oldMockFramework) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    public void testModernMockListEntriesWhenAvailable() {
        if (!isPresent("org.evosuite.runtime.mock.java.util.MockThreadLocalRandom")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        RuntimeSettings.useVNET = true;
        RuntimeSettings.mockJVMNonDeterminism = true;

        Assertions.assertTrue(MockList.shouldBeMocked("java.util.concurrent.ThreadLocalRandom"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.util.SplittableRandom"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.lang.System"));
        if (isPresent("org.evosuite.runtime.mock.java.util.random.MockRandomGenerator")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.util.random.RandomGenerator"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.util.random.MockRandomGeneratorFactory")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.util.random.RandomGeneratorFactory"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.time.MockZoneId")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.time.ZoneId"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.time.MockZoneOffset")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.time.ZoneOffset"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.time.MockZoneRules")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.time.zone.ZoneRules"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.time.MockZoneRulesProvider")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.time.zone.ZoneRulesProvider"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.lang.MockScopedValue")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.lang.ScopedValue"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.lang.MockScopedValueCarrier")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.lang.ScopedValue$Carrier"));
        }
        if (isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockStructuredTaskScope")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.util.concurrent.StructuredTaskScope"));
        }
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.Files"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.Paths"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.FileSystems"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.FileStore"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.FileSystem"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.file.Path"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.net.http.HttpClient"));
        if (isPresent("org.evosuite.runtime.mock.java.net.http.MockWebSocket")) {
            Assertions.assertTrue(MockList.shouldBeMocked("java.net.http.WebSocket"));
        }
        Assertions.assertTrue(MockList.shouldBeMocked("java.util.concurrent.Executors"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.util.concurrent.CompletableFuture"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.lang.ProcessBuilder"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.lang.ProcessHandle"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.lang.Process"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.channels.FileChannel"));
        Assertions.assertTrue(MockList.shouldBeMocked("java.nio.channels.AsynchronousFileChannel"));
    }

    @Test
    public void testMockFilesReadWriteRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");

        Path p = Paths.get("modern-mock-files-roundtrip.txt");
        Method writeString = mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class);
        Method readString = mockFiles.getMethod("readString", Path.class);

        writeString.invoke(null, p, "hello-modern", (Object) new java.nio.file.OpenOption[0]);
        String text = (String) readString.invoke(null, p);

        Assertions.assertEquals("hello-modern", text);
    }

    @Test
    public void testMockFilesNewDirectoryStreamIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");

        Path dir = Paths.get("modern-directory-stream");
        Path c = dir.resolve("c.txt");
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.log");

        new java.io.File(dir.toString()).mkdirs();
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, c, "c", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, a, "a", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, b, "b", (Object) new java.nio.file.OpenOption[0]);

        DirectoryStream<Path> stream = (DirectoryStream<Path>) mockFiles
                .getMethod("newDirectoryStream", Path.class)
                .invoke(null, dir);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Path p : stream) {
            names.add(p.getFileName().toString());
        }
        stream.close();
        Assertions.assertEquals(Arrays.asList("a.txt", "b.log", "c.txt"), names);

        DirectoryStream<Path> txtStream = (DirectoryStream<Path>) mockFiles
                .getMethod("newDirectoryStream", Path.class, String.class)
                .invoke(null, dir, "*.txt");
        java.util.List<String> txtNames = new java.util.ArrayList<>();
        for (Path p : txtStream) {
            txtNames.add(p.getFileName().toString());
        }
        txtStream.close();
        Assertions.assertEquals(Arrays.asList("a.txt", "c.txt"), txtNames);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockFilesListAndWalkDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("modern-walk-root");
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");

        new java.io.File(dirA.toString()).mkdirs();
        new java.io.File(dirB.toString()).mkdirs();

        Path f2 = dirB.resolve("2.txt");
        Path f1 = dirA.resolve("1.txt");
        Path top = root.resolve("z.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, f2, "2", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, f1, "1", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, top, "z", (Object) new java.nio.file.OpenOption[0]);

        try (Stream<Path> list = (Stream<Path>) mockFiles.getMethod("list", Path.class).invoke(null, root)) {
            List<String> listed = list.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            Assertions.assertEquals(Arrays.asList("a", "b", "z.txt"), listed);
        }

        try (Stream<Path> walk = (Stream<Path>) mockFiles
                .getMethod("walk", Path.class, int.class, java.nio.file.FileVisitOption[].class)
                .invoke(null, root, 1, (Object) new java.nio.file.FileVisitOption[0])) {
            List<String> walked = walk
                    .map(p -> root.equals(p) ? "." : root.relativize(p).toString())
                    .collect(Collectors.toList());
            Assertions.assertEquals(Arrays.asList(".", "a", "b", "z.txt"), walked);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockFilesFindDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("modern-find-root");
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        new java.io.File(dirA.toString()).mkdirs();
        new java.io.File(dirB.toString()).mkdirs();

        Path top = root.resolve("top.txt");
        Path nestedTxt = dirA.resolve("nested.txt");
        Path nestedLog = dirB.resolve("nested.log");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, top, "t", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, nestedTxt, "n", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, nestedLog, "l", (Object) new java.nio.file.OpenOption[0]);

        BiPredicate<Path, Object> txtMatcher = (path, attrs) -> path.toString().endsWith(".txt");
        try (Stream<Path> found = (Stream<Path>) mockFiles
                .getMethod("find", Path.class, int.class, BiPredicate.class, java.nio.file.FileVisitOption[].class)
                .invoke(null, root, 2, txtMatcher, (Object) new java.nio.file.FileVisitOption[0])) {
            List<String> names = found
                    .map(p -> root.equals(p) ? "." : root.relativize(p).toString())
                    .collect(Collectors.toList());
            Assertions.assertEquals(Arrays.asList("a/nested.txt", "top.txt"), names);
        }

        try (Stream<Path> shallow = (Stream<Path>) mockFiles
                .getMethod("find", Path.class, int.class, BiPredicate.class, java.nio.file.FileVisitOption[].class)
                .invoke(null, root, 1, txtMatcher, (Object) new java.nio.file.FileVisitOption[0])) {
            List<String> names = shallow
                    .map(p -> root.equals(p) ? "." : root.relativize(p).toString())
                    .collect(Collectors.toList());
            Assertions.assertEquals(Arrays.asList("top.txt"), names);
        }
    }

    @Test
    public void testMockFilesWalkFileTreeDeterministicOrder() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("walk-tree-root");
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        new java.io.File(dirA.toString()).mkdirs();
        new java.io.File(dirB.toString()).mkdirs();
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, dirA.resolve("1.txt"), "1", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, dirB.resolve("2.txt"), "2", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, root.resolve("z.txt"), "z", (Object) new java.nio.file.OpenOption[0]);

        final List<String> events = new ArrayList<>();
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                events.add("pre:" + rel(root, dir));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                events.add("file:" + rel(root, file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                events.add("post:" + rel(root, dir));
                return FileVisitResult.CONTINUE;
            }

            private String rel(Path base, Path p) {
                return base.equals(p) ? "." : base.relativize(p).toString();
            }
        };

        mockFiles.getMethod("walkFileTree", Path.class, Set.class, int.class, java.nio.file.FileVisitor.class)
                .invoke(null, root, EnumSet.noneOf(FileVisitOption.class), 2, visitor);

        Assertions.assertEquals(Arrays.asList(
                "pre:.",
                "pre:a",
                "file:a/1.txt",
                "post:a",
                "pre:b",
                "file:b/2.txt",
                "post:b",
                "file:z.txt",
                "post:."
        ), events);
    }

    @Test
    public void testMockFilesWalkFileTreeSkipSubtreeAndMaxDepth() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("walk-tree-skip-root");
        Path dirA = root.resolve("a");
        Path dirB = root.resolve("b");
        new java.io.File(dirA.toString()).mkdirs();
        new java.io.File(dirB.toString()).mkdirs();
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, dirA.resolve("skip.txt"), "x", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, dirB.resolve("keep.txt"), "y", (Object) new java.nio.file.OpenOption[0]);

        final List<String> events = new ArrayList<>();
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String rel = root.equals(dir) ? "." : root.relativize(dir).toString();
                events.add("pre:" + rel);
                if ("a".equals(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                events.add("file:" + root.relativize(file).toString());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                events.add("post:" + (root.equals(dir) ? "." : root.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
        };

        mockFiles.getMethod("walkFileTree", Path.class, Set.class, int.class, java.nio.file.FileVisitor.class)
                .invoke(null, root, EnumSet.noneOf(FileVisitOption.class), 1, visitor);

        Assertions.assertEquals(Arrays.asList(
                "pre:.",
                "pre:a",
                "post:a",
                "pre:b",
                "post:b",
                "post:."
        ), events);
    }

    @Test
    public void testMockFilesWalkFileTreeTerminate() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("walk-tree-terminate-root");
        new java.io.File(root.resolve("a").toString()).mkdirs();
        new java.io.File(root.resolve("b").toString()).mkdirs();
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, root.resolve("a/1.txt"), "1", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, root.resolve("b/2.txt"), "2", (Object) new java.nio.file.OpenOption[0]);

        final List<String> events = new ArrayList<>();
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                events.add("pre:" + (root.equals(dir) ? "." : root.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                events.add("file:" + root.relativize(file).toString());
                return FileVisitResult.TERMINATE;
            }
        };

        mockFiles.getMethod("walkFileTree", Path.class, Set.class, int.class, java.nio.file.FileVisitor.class)
                .invoke(null, root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, visitor);

        Assertions.assertTrue(events.contains("pre:."));
        Assertions.assertEquals(3, events.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockFilesLineApisRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path path = Paths.get("modern-lines.txt");
        List<String> expected = Arrays.asList("alpha", "beta", "gamma");

        mockFiles.getMethod("write", Path.class, Iterable.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, expected, (Object) new java.nio.file.OpenOption[0]);

        List<String> lines = (List<String>) mockFiles.getMethod("readAllLines", Path.class).invoke(null, path);
        Assertions.assertEquals(expected, lines);

        try (Stream<String> stream = (Stream<String>) mockFiles.getMethod("lines", Path.class).invoke(null, path)) {
            List<String> fromStream = stream.collect(Collectors.toList());
            Assertions.assertEquals(expected, fromStream);
        }
    }

    @Test
    public void testMockFilesCopyAndMoveSemantics() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path src = Paths.get("copy-src.txt");
        Path dst = Paths.get("copy-dst.txt");
        Path moved = Paths.get("moved.txt");

        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, src, "source", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, dst, "target", (Object) new java.nio.file.OpenOption[0]);

        try {
            mockFiles.getMethod("copy", Path.class, Path.class, java.nio.file.CopyOption[].class)
                    .invoke(null, src, dst, (Object) new java.nio.file.CopyOption[0]);
            Assertions.fail("Expected copy without REPLACE_EXISTING to fail");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("FileAlreadyExistsException"));
        }

        mockFiles.getMethod("copy", Path.class, Path.class, java.nio.file.CopyOption[].class)
                .invoke(null, src, dst, (Object) new java.nio.file.CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
        String dstContent = (String) mockFiles.getMethod("readString", Path.class).invoke(null, dst);
        Assertions.assertEquals("source", dstContent);

        mockFiles.getMethod("move", Path.class, Path.class, java.nio.file.CopyOption[].class)
                .invoke(null, dst, moved, (Object) new java.nio.file.CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
        boolean oldExists = (Boolean) mockFiles.getMethod("exists", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, dst, (Object) new java.nio.file.LinkOption[0]);
        boolean movedExists = (Boolean) mockFiles.getMethod("exists", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, moved, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertFalse(oldExists);
        Assertions.assertTrue(movedExists);
    }

    @Test
    public void testMockFilesStreamCopyOverloads() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path target = Paths.get("stream-copy-target.bin");
        byte[] payload = "stream-copy".getBytes(StandardCharsets.UTF_8);

        long written = (Long) mockFiles
                .getMethod("copy", java.io.InputStream.class, Path.class, java.nio.file.CopyOption[].class)
                .invoke(null, new ByteArrayInputStream(payload), target, (Object) new java.nio.file.CopyOption[0]);
        Assertions.assertEquals(payload.length, written);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long copied = (Long) mockFiles
                .getMethod("copy", Path.class, java.io.OutputStream.class)
                .invoke(null, target, out);
        Assertions.assertEquals(payload.length, copied);
        Assertions.assertArrayEquals(payload, out.toByteArray());
    }

    @Test
    public void testMockFilesCopyMoveOptionPolicies() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path src = Paths.get("copy-opt-src.txt");
        Path dst = Paths.get("copy-opt-dst.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, src, "payload", (Object) new java.nio.file.OpenOption[0]);

        mockFiles.getMethod("copy", Path.class, Path.class, java.nio.file.CopyOption[].class)
                .invoke(null, src, dst, (Object) new java.nio.file.CopyOption[]{
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
                });
        Assertions.assertEquals("payload", mockFiles.getMethod("readString", Path.class).invoke(null, dst));

        try {
            mockFiles.getMethod("move", Path.class, Path.class, java.nio.file.CopyOption[].class)
                    .invoke(null, dst, Paths.get("copy-opt-moved.txt"), (Object) new java.nio.file.CopyOption[]{
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
                    });
            Assertions.fail("Expected COPY_ATTRIBUTES to be unsupported for move");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockFilesFollowLinksOptionRejected() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path root = Paths.get("follow-links-policy-root");
        new java.io.File(root.toString()).mkdirs();
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, root.resolve("x.txt"), "x", (Object) new java.nio.file.OpenOption[0]);

        try {
            mockFiles.getMethod("walk", Path.class, java.nio.file.FileVisitOption[].class)
                    .invoke(null, root, (Object) new java.nio.file.FileVisitOption[]{
                            java.nio.file.FileVisitOption.FOLLOW_LINKS
                    });
            Assertions.fail("Expected FOLLOW_LINKS to be unsupported for walk");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }

        try {
            mockFiles.getMethod("find", Path.class, int.class, BiPredicate.class, java.nio.file.FileVisitOption[].class)
                    .invoke(null, root, 1, (BiPredicate<Path, Object>) (p, a) -> true,
                            (Object) new java.nio.file.FileVisitOption[]{java.nio.file.FileVisitOption.FOLLOW_LINKS});
            Assertions.fail("Expected FOLLOW_LINKS to be unsupported for find");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }

        try {
            mockFiles.getMethod("walkFileTree", Path.class, Set.class, int.class, java.nio.file.FileVisitor.class)
                    .invoke(null, root, EnumSet.of(java.nio.file.FileVisitOption.FOLLOW_LINKS), 1,
                            new SimpleFileVisitor<Path>() {
                            });
            Assertions.fail("Expected FOLLOW_LINKS to be unsupported for walkFileTree");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }
    }

    @Test
    public void testMockFilesCreateDirectoriesAndTempPaths() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path deep = Paths.get("create-dirs-root", "a", "b", "c");

        mockFiles.getMethod("createDirectories", Path.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, deep, (Object) new java.nio.file.attribute.FileAttribute[0]);
        boolean deepExists = (Boolean) mockFiles.getMethod("exists", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, deep, (Object) new java.nio.file.LinkOption[0]);
        boolean deepDir = (Boolean) mockFiles.getMethod("isDirectory", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, deep, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertTrue(deepExists);
        Assertions.assertTrue(deepDir);

        Path base = Paths.get("temp-base");
        mockFiles.getMethod("createDirectories", Path.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, base, (Object) new java.nio.file.attribute.FileAttribute[0]);

        Path tempFile = (Path) mockFiles
                .getMethod("createTempFile", Path.class, String.class, String.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, base, "pref-", ".dat", (Object) new java.nio.file.attribute.FileAttribute[0]);
        Path tempDir = (Path) mockFiles
                .getMethod("createTempDirectory", Path.class, String.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, base, "dir-", (Object) new java.nio.file.attribute.FileAttribute[0]);

        Assertions.assertTrue(tempFile.getFileName().toString().startsWith("pref-"));
        Assertions.assertTrue(tempFile.getFileName().toString().endsWith(".dat"));
        Assertions.assertEquals(base, tempFile.getParent());

        Assertions.assertTrue(tempDir.getFileName().toString().startsWith("dir-"));
        Assertions.assertEquals(base, tempDir.getParent());
        boolean tempDirExists = (Boolean) mockFiles.getMethod("exists", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, tempDir, (Object) new java.nio.file.LinkOption[0]);
        boolean tempDirIsDir = (Boolean) mockFiles.getMethod("isDirectory", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, tempDir, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertTrue(tempDirExists);
        Assertions.assertTrue(tempDirIsDir);
    }

    @Test
    public void testMockFilesCreateDirectoriesFailsWhenLeafIsFile() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path existingFile = Paths.get("mkdirs-existing-file.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, existingFile, "x", (Object) new java.nio.file.OpenOption[0]);

        try {
            mockFiles.getMethod("createDirectories", Path.class, java.nio.file.attribute.FileAttribute[].class)
                    .invoke(null, existingFile, (Object) new java.nio.file.attribute.FileAttribute[0]);
            Assertions.fail("Expected createDirectories to fail when path is an existing file");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("FileAlreadyExistsException"));
        }
    }

    @Test
    public void testMockFilesLargeBytePayloadRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path payloadPath = Paths.get("large-payload.bin");
        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }

        mockFiles.getMethod("write", Path.class, byte[].class, java.nio.file.OpenOption[].class)
                .invoke(null, payloadPath, payload, (Object) new java.nio.file.OpenOption[0]);

        byte[] readBack = (byte[]) mockFiles.getMethod("readAllBytes", Path.class).invoke(null, payloadPath);
        Assertions.assertArrayEquals(payload, readBack);
    }

    @Test
    public void testMockFilesLinkApisAreBlocked() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path link = Paths.get("blocked-link");
        Path target = Paths.get("target.txt");

        try {
            mockFiles.getMethod("createSymbolicLink", Path.class, Path.class, java.nio.file.attribute.FileAttribute[].class)
                    .invoke(null, link, target, (Object) new java.nio.file.attribute.FileAttribute[0]);
            Assertions.fail("Expected symbolic-link creation to be blocked");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("IOException"));
        }

        try {
            mockFiles.getMethod("createLink", Path.class, Path.class).invoke(null, link, target);
            Assertions.fail("Expected hard-link creation to be blocked");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("IOException"));
        }

        try {
            mockFiles.getMethod("readSymbolicLink", Path.class).invoke(null, link);
            Assertions.fail("Expected symbolic-link read to be blocked");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("IOException"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockFilesBasicAttributeApis() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("attrs.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, "abcd", (Object) new java.nio.file.OpenOption[0]);

        Object sizeObj = mockFiles
                .getMethod("getAttribute", Path.class, String.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, "basic:size", (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(4L, ((Long) sizeObj).longValue());

        Map<String, Object> one = (Map<String, Object>) mockFiles
                .getMethod("readAttributes", Path.class, String.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, "basic:isRegularFile", (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(Boolean.TRUE, one.get("isRegularFile"));

        Map<String, Object> all = (Map<String, Object>) mockFiles
                .getMethod("readAttributes", Path.class, String.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, "basic:*", (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(4L, ((Long) all.get("size")).longValue());
        Assertions.assertEquals(Boolean.TRUE, all.get("isRegularFile"));

        BasicFileAttributes attrs = (BasicFileAttributes) mockFiles
                .getMethod("readAttributes", Path.class, Class.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, BasicFileAttributes.class, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(4L, attrs.size());
        Assertions.assertTrue(attrs.isRegularFile());

        FileTime expected = FileTime.fromMillis(123456789L);
        mockFiles.getMethod("setAttribute", Path.class, String.class, Object.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, "basic:lastModifiedTime", expected, (Object) new java.nio.file.LinkOption[0]);
        BasicFileAttributes updated = (BasicFileAttributes) mockFiles
                .getMethod("readAttributes", Path.class, Class.class, java.nio.file.LinkOption[].class)
                .invoke(null, p, BasicFileAttributes.class, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(expected.toMillis(), updated.lastModifiedTime().toMillis());
    }

    @Test
    public void testMockFilesAccessChecksAndContentType() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Class<?> mockFileClass = Class.forName("org.evosuite.runtime.mock.java.io.MockFile");

        Path p = Paths.get("perm-and-type.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, "hello", (Object) new java.nio.file.OpenOption[0]);

        Object mockFile = mockFileClass.getConstructor(String.class).newInstance(p.toString());
        mockFileClass.getMethod("setReadable", boolean.class, boolean.class).invoke(mockFile, false, false);
        mockFileClass.getMethod("setWritable", boolean.class, boolean.class).invoke(mockFile, false, false);
        mockFileClass.getMethod("setExecutable", boolean.class, boolean.class).invoke(mockFile, true, false);

        boolean readable = (Boolean) mockFiles.getMethod("isReadable", Path.class).invoke(null, p);
        boolean writable = (Boolean) mockFiles.getMethod("isWritable", Path.class).invoke(null, p);
        boolean executable = (Boolean) mockFiles.getMethod("isExecutable", Path.class).invoke(null, p);
        Assertions.assertFalse(readable);
        Assertions.assertFalse(writable);
        Assertions.assertTrue(executable);

        String txtType = (String) mockFiles.getMethod("probeContentType", Path.class).invoke(null, p);
        String jsonType = (String) mockFiles.getMethod("probeContentType", Path.class).invoke(null, Paths.get("x.json"));
        String unknown = (String) mockFiles.getMethod("probeContentType", Path.class).invoke(null, Paths.get("x.unknownext"));
        Assertions.assertEquals("text/plain", txtType);
        Assertions.assertEquals("application/json", jsonType);
        Assertions.assertNull(unknown);

        Path hidden = Paths.get(".hidden-name.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, hidden, "h", (Object) new java.nio.file.OpenOption[0]);
        boolean hiddenValue = (Boolean) mockFiles.getMethod("isHidden", Path.class).invoke(null, hidden);
        boolean symbolic = (Boolean) mockFiles.getMethod("isSymbolicLink", Path.class).invoke(null, hidden);
        Assertions.assertTrue(hiddenValue);
        Assertions.assertFalse(symbolic);
    }

    @Test
    public void testMockFilesSizeMtimeAndIsSameFile() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path first = Paths.get("same-file-a.txt");
        Path second = Paths.get("same-file-b.txt");

        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, first, "12345", (Object) new java.nio.file.OpenOption[0]);
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, second, "x", (Object) new java.nio.file.OpenOption[0]);

        long size = (Long) mockFiles.getMethod("size", Path.class).invoke(null, first);
        Assertions.assertEquals(5L, size);

        FileTime t = FileTime.fromMillis(987654321L);
        mockFiles.getMethod("setLastModifiedTime", Path.class, FileTime.class).invoke(null, first, t);
        FileTime observed = (FileTime) mockFiles
                .getMethod("getLastModifiedTime", Path.class, java.nio.file.LinkOption[].class)
                .invoke(null, first, (Object) new java.nio.file.LinkOption[0]);
        Assertions.assertEquals(t.toMillis(), observed.toMillis());

        boolean sameSelf = (Boolean) mockFiles.getMethod("isSameFile", Path.class, Path.class).invoke(null, first, first);
        boolean different = (Boolean) mockFiles.getMethod("isSameFile", Path.class, Path.class).invoke(null, first, second);
        Assertions.assertTrue(sameSelf);
        Assertions.assertFalse(different);
    }

    @Test
    public void testMockFilesBufferedReaderWriterRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("buffered-roundtrip.txt");

        BufferedWriter writer = (BufferedWriter) mockFiles
                .getMethod("newBufferedWriter", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, (Object) new java.nio.file.OpenOption[0]);
        writer.write("line-1");
        writer.newLine();
        writer.write("line-2");
        writer.close();

        BufferedReader reader = (BufferedReader) mockFiles.getMethod("newBufferedReader", Path.class).invoke(null, p);
        String first = reader.readLine();
        String second = reader.readLine();
        String third = reader.readLine();
        reader.close();

        Assertions.assertEquals("line-1", first);
        Assertions.assertEquals("line-2", second);
        Assertions.assertNull(third);
    }

    @Test
    public void testMockFilesNewOutputStreamOptionSemantics() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("out-options.txt");

        // default behavior creates file and truncates existing content
        java.io.OutputStream out1 = (java.io.OutputStream) mockFiles
                .getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, (Object) new java.nio.file.OpenOption[0]);
        out1.write("abc".getBytes(StandardCharsets.UTF_8));
        out1.close();

        java.io.OutputStream out2 = (java.io.OutputStream) mockFiles
                .getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, (Object) new java.nio.file.OpenOption[0]);
        out2.write("x".getBytes(StandardCharsets.UTF_8));
        out2.close();
        String content = (String) mockFiles.getMethod("readString", Path.class).invoke(null, p);
        Assertions.assertEquals("x", content);

        // APPEND extends existing content
        java.io.OutputStream out3 = (java.io.OutputStream) mockFiles
                .getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.APPEND
                });
        out3.write("yz".getBytes(StandardCharsets.UTF_8));
        out3.close();
        String appended = (String) mockFiles.getMethod("readString", Path.class).invoke(null, p);
        Assertions.assertEquals("xyz", appended);

        // CREATE_NEW on existing file fails deterministically
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, "still-here", (Object) new java.nio.file.OpenOption[0]);
        try {
            mockFiles.getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                    .invoke(null, p, (Object) new java.nio.file.OpenOption[]{
                            java.nio.file.StandardOpenOption.CREATE_NEW
                    });
            Assertions.fail("Expected CREATE_NEW on existing file to fail");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("FileAlreadyExistsException"));
        }

        // APPEND + TRUNCATE_EXISTING is invalid
        try {
            mockFiles.getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                    .invoke(null, p, (Object) new java.nio.file.OpenOption[]{
                            java.nio.file.StandardOpenOption.APPEND,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                    });
            Assertions.fail("Expected invalid APPEND+TRUNCATE_EXISTING combination");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testMockFilesOptionValidationPolicies() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("option-policy.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, "x", (Object) new java.nio.file.OpenOption[0]);

        try {
            mockFiles.getMethod("newInputStream", Path.class, java.nio.file.OpenOption[].class)
                    .invoke(null, p, (Object) new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.WRITE});
            Assertions.fail("Expected WRITE to be invalid for newInputStream");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof IllegalArgumentException);
        }

        try {
            mockFiles.getMethod("newOutputStream", Path.class, java.nio.file.OpenOption[].class)
                    .invoke(null, p, (Object) new java.nio.file.OpenOption[]{
                            java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
                    });
            Assertions.fail("Expected DELETE_ON_CLOSE to be unsupported");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }

        Set<java.nio.file.OpenOption> invalid = new HashSet<>();
        invalid.add(java.nio.file.StandardOpenOption.DELETE_ON_CLOSE);
        try {
            mockFiles.getMethod("newByteChannel", Path.class, Set.class, java.nio.file.attribute.FileAttribute[].class)
                    .invoke(null, p, invalid, (Object) new java.nio.file.attribute.FileAttribute[0]);
            Assertions.fail("Expected DELETE_ON_CLOSE to be unsupported for byte channels");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause instanceof UnsupportedOperationException);
        }

        Set<java.nio.file.OpenOption> valid = new HashSet<>();
        valid.add(java.nio.file.StandardOpenOption.READ);
        valid.add(java.nio.file.LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel ch = (SeekableByteChannel) mockFiles
                .getMethod("newByteChannel", Path.class, Set.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, p, valid, (Object) new java.nio.file.attribute.FileAttribute[0]);
        ch.close();
    }

    @Test
    public void testMockFilesNewByteChannelReadWrite() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("byte-channel.txt");

        Set<java.nio.file.OpenOption> writeOpts = new HashSet<>();
        writeOpts.add(java.nio.file.StandardOpenOption.CREATE);
        writeOpts.add(java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        writeOpts.add(java.nio.file.StandardOpenOption.WRITE);
        SeekableByteChannel out = (SeekableByteChannel) mockFiles
                .getMethod("newByteChannel", Path.class, Set.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, p, writeOpts, (Object) new java.nio.file.attribute.FileAttribute[0]);
        out.write(ByteBuffer.wrap("abc123".getBytes(StandardCharsets.UTF_8)));
        out.close();

        Set<java.nio.file.OpenOption> readOpts = new HashSet<>();
        readOpts.add(java.nio.file.StandardOpenOption.READ);
        SeekableByteChannel in = (SeekableByteChannel) mockFiles
                .getMethod("newByteChannel", Path.class, Set.class, java.nio.file.attribute.FileAttribute[].class)
                .invoke(null, p, readOpts, (Object) new java.nio.file.attribute.FileAttribute[0]);
        ByteBuffer buf = ByteBuffer.allocate(16);
        int read = in.read(buf);
        in.close();

        Assertions.assertEquals(6, read);
        String payload = new String(buf.array(), 0, read, StandardCharsets.UTF_8);
        Assertions.assertEquals("abc123", payload);
    }

    @Test
    public void testMockFileSystemsGetDefaultAndProviderPolicy() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFileSystems")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileSystemsClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFileSystems");

        FileSystem fs = (FileSystem) mockFileSystemsClass.getMethod("getDefault").invoke(null);
        Assertions.assertNotNull(fs);
        Assertions.assertEquals(Paths.get("").getFileSystem(), fs);

        FileSystem fileUriFs = (FileSystem) mockFileSystemsClass
                .getMethod("getFileSystem", URI.class)
                .invoke(null, URI.create("file:///"));
        FileSystem jarUriFs = (FileSystem) mockFileSystemsClass
                .getMethod("getFileSystem", URI.class)
                .invoke(null, URI.create("jar:file:///tmp/x.jar!/"));
        Assertions.assertEquals(fs, fileUriFs);
        Assertions.assertEquals(fs, jarUriFs);

        Object providers = mockFileSystemsClass.getMethod("installedProviders").invoke(null);
        Assertions.assertTrue(providers instanceof Iterable);
        Assertions.assertFalse(((Iterable<?>) providers).iterator().hasNext());

        try {
            mockFileSystemsClass
                    .getMethod("newFileSystem", URI.class, Map.class)
                    .invoke(null, URI.create("jar:file:///tmp/x.jar!/"), java.util.Collections.emptyMap());
            Assertions.fail("Expected newFileSystem(URI,Map) to be blocked in mocked execution");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("MockIOException"));
        }

        try {
            mockFileSystemsClass
                    .getMethod("newFileSystem", Path.class, Map.class)
                    .invoke(null, Paths.get("dummy.zip"), java.util.Collections.emptyMap());
            Assertions.fail("Expected newFileSystem(Path,Map) to be blocked in mocked execution");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("MockIOException"));
        }
    }

    @Test
    public void testMockFilesGetFileStoreDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockFiles")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFiles = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockFiles");
        Path p = Paths.get("modern-file-store-test.txt");
        mockFiles.getMethod("writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .invoke(null, p, "x", (Object) new java.nio.file.OpenOption[0]);

        FileStore store = (FileStore) mockFiles.getMethod("getFileStore", Path.class).invoke(null, p);
        Assertions.assertNotNull(store);
        Assertions.assertEquals("evosuite-vfs", store.name());
        Assertions.assertEquals("evosuite", store.type());
        Assertions.assertFalse(store.isReadOnly());
        Assertions.assertEquals(1_073_741_824L, store.getTotalSpace());
        Assertions.assertEquals(805_306_368L, store.getUsableSpace());
        Assertions.assertEquals(805_306_368L, store.getUnallocatedSpace());
        Assertions.assertTrue(store.supportsFileAttributeView("basic"));
        Assertions.assertFalse(store.supportsFileAttributeView("posix"));
        Assertions.assertEquals(store.getTotalSpace(), ((Long) store.getAttribute("totalSpace")).longValue());
    }

    @Test
    public void testWatchServiceApisDeterministicWhenMocked() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")
                || !isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioPath")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileSystemClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem");
        Class<?> mockPathClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioPath");

        FileSystem fs = FileSystems.getDefault();
        Path watchedDir = Paths.get("watch-service-mock-test");

        WatchService watcher = (WatchService) mockFileSystemClass
                .getMethod("newWatchService", FileSystem.class)
                .invoke(null, fs);
        Assertions.assertNotNull(watcher);

        WatchKey key = (WatchKey) mockPathClass
                .getMethod("register", Path.class, WatchService.class, WatchEvent.Kind[].class)
                .invoke(null, watchedDir, watcher, (Object) new WatchEvent.Kind[]{
                        StandardWatchEventKinds.ENTRY_CREATE
                });
        Assertions.assertNotNull(key);
        Assertions.assertTrue(key.isValid());
        Assertions.assertNull(watcher.poll());

        Path created = Paths.get("created.txt");
        mockFileSystemClass
                .getMethod("emitEvent", WatchService.class, Path.class, WatchEvent.Kind.class, Path.class)
                .invoke(null, watcher, watchedDir, StandardWatchEventKinds.ENTRY_CREATE, created);

        WatchKey signalled = watcher.poll(1L, TimeUnit.SECONDS);
        Assertions.assertNotNull(signalled);
        Assertions.assertEquals(key, signalled);

        List<WatchEvent<?>> events = signalled.pollEvents();
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(StandardWatchEventKinds.ENTRY_CREATE, events.get(0).kind());
        Assertions.assertEquals(created, events.get(0).context());
        Assertions.assertTrue(signalled.reset());

        watcher.close();
        Assertions.assertFalse(signalled.isValid());
    }

    @Test
    public void testMockNioFileSystemPathMatcherDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileSystemClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockNioFileSystem");
        FileSystem fs = FileSystems.getDefault();

        PathMatcher glob = (PathMatcher) mockFileSystemClass
                .getMethod("getPathMatcher", FileSystem.class, String.class)
                .invoke(null, fs, "glob:*.txt");
        Assertions.assertTrue(glob.matches(Paths.get("alpha.txt")));
        Assertions.assertFalse(glob.matches(Paths.get("alpha.log")));

        PathMatcher regex = (PathMatcher) mockFileSystemClass
                .getMethod("getPathMatcher", FileSystem.class, String.class)
                .invoke(null, fs, "regex:.*\\.data");
        Assertions.assertTrue(regex.matches(Paths.get("x.data")));
        Assertions.assertFalse(regex.matches(Paths.get("x.txt")));
    }

    @Test
    public void testMockHttpClientCanSendDeterministicResponse() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.net.http.MockHttpClient")
                || !isPresent("java.net.http.HttpClient")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockHttpClientClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockHttpClient");
        Class<?> httpClientClass = Class.forName("java.net.http.HttpClient");
        Class<?> httpRequestClass = Class.forName("java.net.http.HttpRequest");
        Class<?> httpRequestBuilderClass = Class.forName("java.net.http.HttpRequest$Builder");
        Class<?> httpResponseClass = Class.forName("java.net.http.HttpResponse");
        Class<?> httpBodyHandlerClass = Class.forName("java.net.http.HttpResponse$BodyHandler");
        Class<?> httpBodyHandlersClass = Class.forName("java.net.http.HttpResponse$BodyHandlers");

        Object client = mockHttpClientClass.getMethod("newHttpClient").invoke(null);

        Object builder = httpRequestClass.getMethod("newBuilder", URI.class).invoke(null, URI.create("http://example.org"));
        builder = httpRequestBuilderClass.getMethod("GET").invoke(builder);
        Object request = httpRequestBuilderClass.getMethod("build").invoke(builder);

        Object bodyHandler = httpBodyHandlersClass.getMethod("ofString").invoke(null);
        Object response = httpClientClass
                .getMethod("send", httpRequestClass, httpBodyHandlerClass)
                .invoke(client, request, bodyHandler);

        int statusCode = (Integer) httpResponseClass.getMethod("statusCode").invoke(response);
        String body = (String) httpResponseClass.getMethod("body").invoke(response);

        Assertions.assertEquals(200, statusCode);
        Assertions.assertEquals("", body);
    }

    @Test
    public void testMockHttpClientValidation() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.net.http.MockHttpClient")
                || !isPresent("java.net.http.HttpClient")) {
            return;
        }

        Class<?> mockHttpClientClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockHttpClient");
        Class<?> httpClientClass = Class.forName("java.net.http.HttpClient");
        Class<?> httpRequestClass = Class.forName("java.net.http.HttpRequest");
        Class<?> httpBodyHandlerClass = Class.forName("java.net.http.HttpResponse$BodyHandler");
        Class<?> httpClientBuilderClass = Class.forName("java.net.http.HttpClient$Builder");

        Object client = mockHttpClientClass.getMethod("newHttpClient").invoke(null);
        assertInvocationCause(
                httpClientClass.getMethod("send", httpRequestClass, httpBodyHandlerClass),
                new Object[]{client, null, null},
                NullPointerException.class);

        Object builder = mockHttpClientClass.getMethod("newBuilder").invoke(null);
        assertInvocationCause(
                httpClientBuilderClass.getMethod("priority", int.class),
                new Object[]{builder, 0},
                IllegalArgumentException.class);
        assertInvocationCause(
                httpClientBuilderClass.getMethod("connectTimeout", java.time.Duration.class),
                new Object[]{builder, java.time.Duration.ZERO},
                IllegalArgumentException.class);
        assertInvocationCause(
                httpClientBuilderClass.getMethod("followRedirects", Class.forName("java.net.http.HttpClient$Redirect")),
                new Object[]{builder, null},
                NullPointerException.class);
    }

    @Test
    public void testMockHttpClientBuilderStateIsVisibleOnClient() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.net.http.MockHttpClient")
                || !isPresent("java.net.http.HttpClient")) {
            return;
        }

        Class<?> mockHttpClientClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockHttpClient");
        Class<?> httpClientBuilderClass = Class.forName("java.net.http.HttpClient$Builder");
        Class<?> httpClientClass = Class.forName("java.net.http.HttpClient");
        Class<?> redirectClass = Class.forName("java.net.http.HttpClient$Redirect");
        Class<?> versionClass = Class.forName("java.net.http.HttpClient$Version");

        Object builder = mockHttpClientClass.getMethod("newBuilder").invoke(null);
        Object redirectAlways = Enum.valueOf((Class<Enum>) redirectClass.asSubclass(Enum.class), "ALWAYS");
        Object versionHttp2 = Enum.valueOf((Class<Enum>) versionClass.asSubclass(Enum.class), "HTTP_2");

        java.util.concurrent.Executor executor = Runnable::run;
        httpClientBuilderClass.getMethod("connectTimeout", java.time.Duration.class)
                .invoke(builder, java.time.Duration.ofSeconds(3));
        httpClientBuilderClass.getMethod("followRedirects", redirectClass).invoke(builder, redirectAlways);
        httpClientBuilderClass.getMethod("version", versionClass).invoke(builder, versionHttp2);
        httpClientBuilderClass.getMethod("executor", java.util.concurrent.Executor.class).invoke(builder, executor);

        Object client = httpClientBuilderClass.getMethod("build").invoke(builder);
        Object timeoutOpt = httpClientClass.getMethod("connectTimeout").invoke(client);
        Object redirect = httpClientClass.getMethod("followRedirects").invoke(client);
        Object version = httpClientClass.getMethod("version").invoke(client);
        Object executorOpt = httpClientClass.getMethod("executor").invoke(client);

        Assertions.assertEquals(java.util.Optional.of(java.time.Duration.ofSeconds(3)), timeoutOpt);
        Assertions.assertEquals(redirectAlways, redirect);
        Assertions.assertEquals(versionHttp2, version);
        Assertions.assertTrue(((java.util.Optional<?>) executorOpt).isPresent());
    }

    @Test
    public void testMockHttpClientDelegatesWhenFrameworkDisabled() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.net.http.MockHttpClient")
                || !isPresent("java.net.http.HttpClient")) {
            return;
        }

        MockFramework.disable();
        Class<?> mockHttpClientClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockHttpClient");

        Object client = mockHttpClientClass.getMethod("newHttpClient").invoke(null);
        Object builder = mockHttpClientClass.getMethod("newBuilder").invoke(null);

        Assertions.assertFalse(client.getClass().getName().contains("MockHttpClient$DeterministicHttpClient"));
        Assertions.assertFalse(builder.getClass().getName().contains("MockHttpClient$DeterministicBuilder"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockHttpClientWebSocketIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.net.http.MockHttpClient")
                || !isPresent("java.net.http.WebSocket")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockHttpClientClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockHttpClient");
        Class<?> httpClientClass = Class.forName("java.net.http.HttpClient");
        Class<?> webSocketBuilderClass = Class.forName("java.net.http.WebSocket$Builder");
        Class<?> webSocketClass = Class.forName("java.net.http.WebSocket");
        Class<?> webSocketListenerClass = Class.forName("java.net.http.WebSocket$Listener");
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");

        Object client = mockHttpClientClass.getMethod("newHttpClient").invoke(null);
        Object builder = httpClientClass.getMethod("newWebSocketBuilder").invoke(client);
        final boolean[] opened = new boolean[]{false};

        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                webSocketListenerClass.getClassLoader(),
                new Class[]{webSocketListenerClass},
                (proxy, method, args) -> {
                    if ("onOpen".equals(method.getName())) {
                        opened[0] = true;
                        return null;
                    }
                    if (java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return null;
                });

        Object wsFuture = webSocketBuilderClass
                .getMethod("buildAsync", URI.class, webSocketListenerClass)
                .invoke(builder, URI.create("ws://example.org/socket"), listener);
        Object webSocket = completableFutureClass.getMethod("join").invoke(wsFuture);

        Assertions.assertTrue(opened[0]);
        Object sendFuture = webSocketClass.getMethod("sendText", CharSequence.class, boolean.class)
                .invoke(webSocket, "hello", true);
        Object sentWs = completableFutureClass.getMethod("join").invoke(sendFuture);
        Assertions.assertSame(webSocket, sentWs);

        webSocketClass.getMethod("sendClose", int.class, String.class).invoke(webSocket, 1000, "done");
        boolean outputClosed = (Boolean) webSocketClass.getMethod("isOutputClosed").invoke(webSocket);
        boolean inputClosed = (Boolean) webSocketClass.getMethod("isInputClosed").invoke(webSocket);
        Assertions.assertTrue(outputClosed);
        Assertions.assertTrue(inputClosed);

        assertInvocationCause(
                webSocketClass.getMethod("request", long.class),
                new Object[]{webSocket, 0L},
                IllegalArgumentException.class);

        if (isPresent("org.evosuite.runtime.mock.java.net.http.MockWebSocket")) {
            Class<?> mockWebSocketClass = Class.forName("org.evosuite.runtime.mock.java.net.http.MockWebSocket");
            Object unknownWebSocket = java.lang.reflect.Proxy.newProxyInstance(
                    webSocketClass.getClassLoader(),
                    new Class[]{webSocketClass},
                    (proxy, method, args) -> {
                        if ("isOutputClosed".equals(method.getName()) || "isInputClosed".equals(method.getName())) {
                            return false;
                        }
                        if ("getSubprotocol".equals(method.getName())) {
                            return "";
                        }
                        if ("request".equals(method.getName()) || "abort".equals(method.getName())) {
                            return null;
                        }
                        return CompletableFuture.completedFuture(proxy);
                    });
            assertInvocationCause(
                    mockWebSocketClass.getMethod("getSubprotocol", webSocketClass),
                    new Object[]{unknownWebSocket},
                    IllegalStateException.class);
        }
    }

    @Test
    public void testMockThreadLocalRandomDelegatesToEvoRandom() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.MockThreadLocalRandom")) {
            return;
        }

        Class<?> tlrClass = Class.forName("java.util.concurrent.ThreadLocalRandom");
        Class<?> mockTlrClass = Class.forName("org.evosuite.runtime.mock.java.util.MockThreadLocalRandom");
        Object tlr = tlrClass.getMethod("current").invoke(null);

        org.evosuite.runtime.Random.setNextRandom(3);
        int value = (Integer) mockTlrClass.getMethod("nextInt", tlrClass, int.class, int.class).invoke(null, tlr, 10, 20);
        Assertions.assertEquals(13, value);
    }

    @Test
    public void testMockSplittableRandomDelegatesToEvoRandom() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.MockSplittableRandom")) {
            return;
        }

        Class<?> srClass = Class.forName("java.util.SplittableRandom");
        Class<?> mockSrClass = Class.forName("org.evosuite.runtime.mock.java.util.MockSplittableRandom");
        Object sr = srClass.getConstructor().newInstance();

        org.evosuite.runtime.Random.setNextRandom(4);
        int value = (Integer) mockSrClass.getMethod("nextInt", srClass, int.class, int.class)
                .invoke(null, sr, 10, 20);
        Assertions.assertEquals(14, value);

        org.evosuite.runtime.Random.setNextRandom(7);
        LongStream stream = (LongStream) mockSrClass.getMethod("longs", srClass, long.class).invoke(null, sr, 3L);
        long[] mockLongs = stream.toArray();
        Assertions.assertEquals(3, mockLongs.length);
        Assertions.assertEquals(7L, mockLongs[0]);
    }

    @Test
    public void testMockRandomGeneratorAndFactoryDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.random.MockRandomGenerator")
                || !isPresent("org.evosuite.runtime.mock.java.util.random.MockRandomGeneratorFactory")
                || !isPresent("java.util.random.RandomGenerator")
                || !isPresent("java.util.random.RandomGeneratorFactory")) {
            return;
        }

        Class<?> rgClass = Class.forName("java.util.random.RandomGenerator");
        Class<?> rgFactoryClass = Class.forName("java.util.random.RandomGeneratorFactory");
        Class<?> mockRgClass = Class.forName("org.evosuite.runtime.mock.java.util.random.MockRandomGenerator");
        Class<?> mockFactoryClass = Class.forName("org.evosuite.runtime.mock.java.util.random.MockRandomGeneratorFactory");

        Object rg = mockRgClass.getMethod("of", String.class).invoke(null, "L64X128MixRandom");
        org.evosuite.runtime.Random.setNextRandom(2);
        int ranged = (Integer) mockRgClass.getMethod("nextInt", rgClass, int.class, int.class)
                .invoke(null, rg, 5, 9);
        Assertions.assertEquals(7, ranged);

        Object factory = mockFactoryClass.getMethod("getDefault").invoke(null);
        Object created = mockFactoryClass.getMethod("create", rgFactoryClass).invoke(null, factory);
        org.evosuite.runtime.Random.setNextRandom(11);
        long generated = (Long) mockRgClass.getMethod("nextLong", rgClass).invoke(null, created);
        Assertions.assertEquals(11L, generated);

        Object seeded = mockFactoryClass.getMethod("create", rgFactoryClass, long.class).invoke(null, factory, 42L);
        long first = (Long) rgClass.getMethod("nextLong").invoke(seeded);
        long second = (Long) rgClass.getMethod("nextLong").invoke(seeded);
        Assertions.assertEquals(42L, first);
        Assertions.assertEquals(43L, second);
    }

    @Test
    public void testMockZoneIdDefaultAndZoneOffsetParsing() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.time.MockZoneId")
                || !isPresent("org.evosuite.runtime.mock.java.time.MockZoneOffset")) {
            return;
        }

        RuntimeSettings.mockJVMNonDeterminism = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockZoneIdClass = Class.forName("org.evosuite.runtime.mock.java.time.MockZoneId");
        Class<?> mockZoneOffsetClass = Class.forName("org.evosuite.runtime.mock.java.time.MockZoneOffset");
        Class<?> zoneIdClass = Class.forName("java.time.ZoneId");
        Class<?> zoneOffsetClass = Class.forName("java.time.ZoneOffset");

        Object defaultZone = mockZoneIdClass.getMethod("systemDefault").invoke(null);
        String defaultId = (String) zoneIdClass.getMethod("getId").invoke(defaultZone);
        Assertions.assertEquals("Z", defaultId);

        Object offset = mockZoneOffsetClass.getMethod("ofTotalSeconds", int.class).invoke(null, 3600);
        String offsetId = (String) zoneOffsetClass.getMethod("getId").invoke(offset);
        Assertions.assertEquals("+01:00", offsetId);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMockZoneRulesProviderIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.time.MockZoneRulesProvider")) {
            return;
        }

        RuntimeSettings.mockJVMNonDeterminism = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockZoneRulesProviderClass = Class.forName("org.evosuite.runtime.mock.java.time.MockZoneRulesProvider");
        Class<?> zoneRulesClass = Class.forName("java.time.zone.ZoneRules");

        Set<String> ids = (Set<String>) mockZoneRulesProviderClass.getMethod("getAvailableZoneIds").invoke(null);
        Assertions.assertEquals(1, ids.size());
        Assertions.assertTrue(ids.contains("UTC"));

        Object rules = mockZoneRulesProviderClass
                .getMethod("getRules", String.class, boolean.class)
                .invoke(null, "UTC", true);
        Assertions.assertTrue(zoneRulesClass.isInstance(rules));

        Map<String, Object> versions = (Map<String, Object>) mockZoneRulesProviderClass
                .getMethod("getVersions", String.class)
                .invoke(null, "UTC");
        Assertions.assertTrue(versions.containsKey("evosuite"));
        Assertions.assertTrue(zoneRulesClass.isInstance(versions.get("evosuite")));

        boolean refreshed = (Boolean) mockZoneRulesProviderClass.getMethod("refresh").invoke(null);
        Assertions.assertFalse(refreshed);
    }

    @Test
    public void testMockScopedValueBasicBehavior() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockScopedValue")
                || !isPresent("java.lang.ScopedValue")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockScopedValueClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockScopedValue");
        Class<?> scopedValueClass = Class.forName("java.lang.ScopedValue");
        Class<?> carrierClass = Class.forName("java.lang.ScopedValue$Carrier");

        Object key = mockScopedValueClass.getMethod("newInstance").invoke(null);
        boolean boundBefore = (Boolean) mockScopedValueClass.getMethod("isBound", scopedValueClass).invoke(null, key);
        Assertions.assertFalse(boundBefore);

        String fallback = (String) mockScopedValueClass
                .getMethod("orElse", scopedValueClass, Object.class)
                .invoke(null, key, "fallback");
        Assertions.assertEquals("fallback", fallback);

        Object carrier = mockScopedValueClass.getMethod("where", scopedValueClass, Object.class).invoke(null, key, "value");
        String fromCarrier = (String) carrierClass.getMethod("get", scopedValueClass).invoke(carrier, key);
        Assertions.assertEquals("value", fromCarrier);

        final String[] fromRun = new String[1];
        Runnable reader = () -> {
            try {
                fromRun[0] = (String) mockScopedValueClass.getMethod("get", scopedValueClass).invoke(null, key);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        carrierClass.getMethod("run", Runnable.class).invoke(carrier, reader);
        Assertions.assertEquals("value", fromRun[0]);
    }

    @Test
    public void testMockStructuredTaskScopeRunsInline() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockStructuredTaskScope")
                || !isPresent("java.util.concurrent.StructuredTaskScope")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockScopeClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockStructuredTaskScope");
        Class<?> scopeClass = Class.forName("java.util.concurrent.StructuredTaskScope");
        Class<?> joinerClass = Class.forName("java.util.concurrent.StructuredTaskScope$Joiner");
        Class<?> subtaskClass = Class.forName("java.util.concurrent.StructuredTaskScope$Subtask");

        Object joiner = joinerClass.getMethod("awaitAll").invoke(null);
        Object scope = mockScopeClass.getMethod("open", joinerClass).invoke(null, joiner);

        Object subtask = mockScopeClass.getMethod("fork", scopeClass, Callable.class)
                .invoke(null, scope, (Callable<Integer>) () -> 7);
        Object joined = mockScopeClass.getMethod("join", scopeClass).invoke(null, scope);
        Assertions.assertNull(joined);
        Object state = subtaskClass.getMethod("state").invoke(subtask);
        Assertions.assertEquals("SUCCESS", String.valueOf(state));
        boolean cancelled = (Boolean) mockScopeClass.getMethod("isCancelled", scopeClass).invoke(null, scope);
        Assertions.assertFalse(cancelled);

        mockScopeClass.getMethod("close", scopeClass).invoke(null, scope);
        assertInvocationCause(
                mockScopeClass.getMethod("fork", scopeClass, Callable.class),
                new Object[]{scope, (Callable<Integer>) () -> 1},
                IllegalStateException.class);
    }

    @Test
    public void testMockScopedValueCarrierCall() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockScopedValueCarrier")
                || !isPresent("java.lang.ScopedValue$CallableOp")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockScopedValueClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockScopedValue");
        Class<?> mockCarrierClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockScopedValueCarrier");
        Class<?> scopedValueClass = Class.forName("java.lang.ScopedValue");
        Class<?> carrierClass = Class.forName("java.lang.ScopedValue$Carrier");
        Class<?> callableOpClass = Class.forName("java.lang.ScopedValue$CallableOp");

        Object key = mockScopedValueClass.getMethod("newInstance").invoke(null);
        Object carrier = mockScopedValueClass.getMethod("where", scopedValueClass, Object.class)
                .invoke(null, key, "value");

        Object callableOp = java.lang.reflect.Proxy.newProxyInstance(
                callableOpClass.getClassLoader(),
                new Class[]{callableOpClass},
                (proxy, method, args) -> "from-call");
        Object result = mockCarrierClass.getMethod("call", carrierClass, callableOpClass)
                .invoke(null, carrier, callableOp);
        Assertions.assertEquals("from-call", result);
    }

    @Test
    public void testMockPathsBuildsPath() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.file.MockPaths")) {
            return;
        }

        Class<?> mockPathsClass = Class.forName("org.evosuite.runtime.mock.java.nio.file.MockPaths");
        Object path = mockPathsClass.getMethod("get", String.class, String[].class)
                .invoke(null, "base", (Object) new String[]{"child.txt"});
        Assertions.assertTrue(path instanceof Path);
        Assertions.assertTrue(path.toString().contains("base"));
    }

    @Test
    public void testMockFileChannelReadWriteRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-channel.txt");

        FileChannel writeChannel = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE
                });
        writeChannel.write(ByteBuffer.wrap("nio-data".getBytes(StandardCharsets.UTF_8)));
        writeChannel.close();

        FileChannel readChannel = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.READ
                });
        ByteBuffer buffer = ByteBuffer.allocate(16);
        int read = readChannel.read(buffer);
        readChannel.close();

        Assertions.assertTrue(read > 0);
        String payload = new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
        Assertions.assertEquals("nio-data", payload);
    }

    @Test
    public void testMockAsynchronousFileChannelReadWriteRoundTrip() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockAsynchronousFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockAsyncFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockAsynchronousFileChannel");
        Path path = Paths.get("modern-async-nio-channel.txt");

        AsynchronousFileChannel channel = (AsynchronousFileChannel) mockAsyncFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });

        Future<Integer> writeFuture = channel.write(ByteBuffer.wrap("async-data".getBytes(StandardCharsets.UTF_8)), 0);
        Assertions.assertEquals(10, (int) writeFuture.get(1, TimeUnit.SECONDS));

        ByteBuffer dst = ByteBuffer.allocate(16);
        Future<Integer> readFuture = channel.read(dst, 0);
        int read = readFuture.get(1, TimeUnit.SECONDS);
        channel.close();

        Assertions.assertTrue(read > 0);
        String payload = new String(dst.array(), 0, read, StandardCharsets.UTF_8);
        Assertions.assertEquals("async-data", payload);
    }

    @Test
    public void testMockFileChannelLockAndRelease() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-file-lock.txt");

        FileChannel channel = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });

        FileLock lock = channel.tryLock();
        Assertions.assertNotNull(lock);
        Assertions.assertTrue(lock.isValid());

        try {
            channel.tryLock();
            Assertions.fail("Expected overlapping lock acquisition to fail");
        } catch (OverlappingFileLockException expected) {
            // expected
        }

        lock.release();
        Assertions.assertFalse(lock.isValid());

        FileLock second = channel.tryLock();
        Assertions.assertNotNull(second);
        second.release();
        channel.close();
    }

    @Test
    public void testMockFileChannelLockAcrossChannels() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-cross-channel-lock.txt");

        FileChannel first = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });
        FileChannel second = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });

        FileLock firstLock = first.lock(0, 10, false);
        Assertions.assertTrue(firstLock.isValid());

        try {
            second.tryLock(5, 10, false);
            Assertions.fail("Expected overlapping lock across channels to fail");
        } catch (OverlappingFileLockException expected) {
            // expected
        }

        FileLock disjoint = second.tryLock(20, 10, false);
        Assertions.assertNotNull(disjoint);
        Assertions.assertTrue(disjoint.isValid());

        disjoint.release();
        firstLock.release();
        first.close();
        second.close();
    }

    @Test
    public void testMockFileChannelLockModePermissions() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-lock-mode-permissions.txt");

        FileChannel writeOnly = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE
                });
        try {
            writeOnly.tryLock(0, 1, true);
            Assertions.fail("Expected shared lock on write-only channel to fail");
        } catch (NonReadableChannelException expected) {
            // expected
        } finally {
            writeOnly.close();
        }

        FileChannel readOnly = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.READ
                });
        try {
            readOnly.tryLock(0, 1, false);
            Assertions.fail("Expected exclusive lock on read-only channel to fail");
        } catch (NonWritableChannelException expected) {
            // expected
        }

        FileLock shared = readOnly.tryLock(0, 1, true);
        Assertions.assertNotNull(shared);
        shared.release();
        readOnly.close();
    }

    @Test
    public void testMockFileChannelMapDisabledDeterministically() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();
        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-mmap-policy.txt");

        FileChannel channel = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });
        channel.write(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)));

        try {
            channel.map(FileChannel.MapMode.READ_WRITE, 0, 1);
            Assertions.fail("Expected mapped-byte-buffer access to be blocked");
        } catch (MockIOException expected) {
            Assertions.assertTrue(expected.getMessage().contains("MappedByteBuffer"));
        } finally {
            channel.close();
        }
    }

    @Test
    public void testMockFileChannelCloseReleasesLocks() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel")) {
            return;
        }

        RuntimeSettings.useVFS = true;
        MockFramework.enable();
        Runtime.getInstance().resetRuntime();

        Class<?> mockFileChannelClass = Class.forName("org.evosuite.runtime.mock.java.nio.channels.MockFileChannel");
        Path path = Paths.get("modern-nio-close-releases-locks.txt");

        FileChannel first = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });
        FileChannel second = (FileChannel) mockFileChannelClass
                .getMethod("open", Path.class, java.nio.file.OpenOption[].class)
                .invoke(null, path, (Object) new java.nio.file.OpenOption[]{
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE
                });

        FileLock held = first.tryLock(0, 10, false);
        Assertions.assertTrue(held.isValid());
        first.close();
        Assertions.assertFalse(held.isValid());

        FileLock reacquired = second.tryLock(0, 10, false);
        Assertions.assertNotNull(reacquired);
        reacquired.release();
        second.close();
    }

    @Test
    public void testMockExecutorsRunsTasksDeterministically() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockExecutorsClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors");
        Object executorObj = mockExecutorsClass.getMethod("newSingleThreadExecutor").invoke(null);
        Assertions.assertTrue(executorObj instanceof ExecutorService);

        ExecutorService executor = (ExecutorService) executorObj;
        final int[] value = new int[]{0};
        executor.submit(() -> value[0] = 7).get(1, TimeUnit.SECONDS);
        Assertions.assertEquals(7, value[0]);
        executor.shutdownNow();
    }

    @Test
    public void testMockExecutorsWorkStealingPoolIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockExecutorsClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors");
        Object executorObj = mockExecutorsClass.getMethod("newWorkStealingPool", int.class).invoke(null, 4);
        Assertions.assertTrue(executorObj instanceof ExecutorService);

        ExecutorService executor = (ExecutorService) executorObj;
        Future<Integer> value = executor.submit(() -> 123);
        Assertions.assertEquals(123, (int) value.get(1, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    @Test
    public void testMockExecutorsRejectSubmissionsAfterShutdown() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockExecutorsClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors");
        Object executorObj = mockExecutorsClass.getMethod("newSingleThreadExecutor").invoke(null);
        Assertions.assertTrue(executorObj instanceof ExecutorService);

        ExecutorService executor = (ExecutorService) executorObj;
        executor.shutdownNow();
        try {
            executor.submit(() -> 1);
            Assertions.fail("Expected submissions after shutdown to be rejected");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // expected
        }
    }

    @Test
    public void testMockExecutorsFactoryValidationMatchesJdk() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockExecutorsClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors");
        java.util.concurrent.ThreadFactory tf = r -> new Thread(r, "mock-test");

        try {
            mockExecutorsClass.getMethod("newFixedThreadPool", int.class).invoke(null, 0);
            Assertions.fail("Expected newFixedThreadPool(0) to throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assertions.assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        try {
            mockExecutorsClass.getMethod("newWorkStealingPool", int.class).invoke(null, -1);
            Assertions.fail("Expected newWorkStealingPool(-1) to throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assertions.assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        try {
            mockExecutorsClass.getMethod("newCachedThreadPool", java.util.concurrent.ThreadFactory.class)
                    .invoke(null, new Object[]{null});
            Assertions.fail("Expected newCachedThreadPool(null) to throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assertions.assertTrue(e.getCause() instanceof NullPointerException);
        }

        try {
            mockExecutorsClass.getMethod("newScheduledThreadPool", int.class, java.util.concurrent.ThreadFactory.class)
                    .invoke(null, -1, tf);
            Assertions.fail("Expected newScheduledThreadPool(-1, tf) to throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assertions.assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        try {
            mockExecutorsClass.getMethod("newSingleThreadScheduledExecutor", java.util.concurrent.ThreadFactory.class)
                    .invoke(null, new Object[]{null});
            Assertions.fail("Expected newSingleThreadScheduledExecutor(null) to throw");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Assertions.assertTrue(e.getCause() instanceof NullPointerException);
        }
    }

    @Test
    public void testMockExecutorsScheduledValidationAndShutdown() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockExecutorsClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockExecutors");
        Object scheduledObj = mockExecutorsClass.getMethod("newSingleThreadScheduledExecutor").invoke(null);
        Assertions.assertTrue(scheduledObj instanceof java.util.concurrent.ScheduledExecutorService);

        java.util.concurrent.ScheduledExecutorService scheduled =
                (java.util.concurrent.ScheduledExecutorService) scheduledObj;

        try {
            scheduled.scheduleAtFixedRate(() -> {
            }, 0L, 0L, TimeUnit.SECONDS);
            Assertions.fail("Expected scheduleAtFixedRate with period<=0 to throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            scheduled.scheduleWithFixedDelay(() -> {
            }, 0L, 0L, TimeUnit.SECONDS);
            Assertions.fail("Expected scheduleWithFixedDelay with delay<=0 to throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            scheduled.schedule((Runnable) () -> {
            }, 1L, null);
            Assertions.fail("Expected null unit to throw");
        } catch (NullPointerException expected) {
            // expected
        }

        scheduled.shutdownNow();
        try {
            scheduled.schedule((java.util.concurrent.Callable<Integer>) () -> 1, 1L, TimeUnit.SECONDS);
            Assertions.fail("Expected schedule after shutdown to be rejected");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            // expected
        }
    }

    @Test
    public void testMockCompletableFutureRunAsyncExecutesImmediately() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        final int[] value = new int[]{0};

        Object cf = mockCfClass.getMethod("runAsync", Runnable.class)
                .invoke(null, (Runnable) () -> value[0] = 11);
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");
        completableFutureClass.getMethod("join").invoke(cf);

        Assertions.assertEquals(11, value[0]);
    }

    @Test
    public void testMockCompletableFutureCompleteOnTimeoutCompletesImmediately() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");

        Object cf = completableFutureClass.getConstructor().newInstance();
        mockCfClass.getMethod("completeOnTimeout", completableFutureClass, Object.class, long.class, TimeUnit.class)
                .invoke(null, cf, "timeout-value", 10L, TimeUnit.SECONDS);
        String value = (String) completableFutureClass.getMethod("join").invoke(cf);

        Assertions.assertEquals("timeout-value", value);
    }

    @Test
    public void testMockCompletableFutureDelayedExecutorRunsImmediately() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        final int[] value = new int[]{0};

        java.util.concurrent.Executor delayed = (java.util.concurrent.Executor) mockCfClass
                .getMethod("delayedExecutor", long.class, TimeUnit.class)
                .invoke(null, 10L, TimeUnit.SECONDS);
        delayed.execute(() -> value[0] = 21);

        Assertions.assertEquals(21, value[0]);
    }

    @Test
    public void testMockCompletableFutureDelayedExecutorUsesProvidedExecutor() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        final int[] executions = new int[]{0};

        java.util.concurrent.Executor delegate = command -> {
            executions[0]++;
            command.run();
        };
        java.util.concurrent.Executor delayed = (java.util.concurrent.Executor) mockCfClass
                .getMethod("delayedExecutor", long.class, TimeUnit.class, java.util.concurrent.Executor.class)
                .invoke(null, 5L, TimeUnit.MINUTES, delegate);

        delayed.execute(() -> executions[0]++);
        Assertions.assertEquals(2, executions[0]);
    }

    @Test
    public void testMockCompletableFutureOrTimeoutLeavesPendingFutureUntouched() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");

        Object cf = completableFutureClass.getConstructor().newInstance();
        Object returned = mockCfClass.getMethod("orTimeout", completableFutureClass, long.class, TimeUnit.class)
                .invoke(null, cf, 1L, TimeUnit.MILLISECONDS);

        Assertions.assertSame(cf, returned);
        boolean done = (Boolean) completableFutureClass.getMethod("isDone").invoke(cf);
        Assertions.assertFalse(done);
    }

    @Test
    public void testMockCompletableFutureNullArgumentValidation() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");
        Object cf = completableFutureClass.getConstructor().newInstance();

        assertInvocationCause(mockCfClass.getMethod("runAsync", Runnable.class), new Object[]{null}, NullPointerException.class);
        assertInvocationCause(mockCfClass.getMethod("supplyAsync", java.util.function.Supplier.class), new Object[]{null}, NullPointerException.class);
        assertInvocationCause(
                mockCfClass.getMethod("delayedExecutor", long.class, TimeUnit.class),
                new Object[]{1L, null},
                NullPointerException.class);
        assertInvocationCause(
                mockCfClass.getMethod("delayedExecutor", long.class, TimeUnit.class, java.util.concurrent.Executor.class),
                new Object[]{1L, TimeUnit.SECONDS, null},
                NullPointerException.class);
        assertInvocationCause(
                mockCfClass.getMethod("orTimeout", completableFutureClass, long.class, TimeUnit.class),
                new Object[]{cf, 1L, null},
                NullPointerException.class);
        assertInvocationCause(
                mockCfClass.getMethod("completeOnTimeout", completableFutureClass, Object.class, long.class, TimeUnit.class),
                new Object[]{cf, "x", 1L, null},
                NullPointerException.class);
        assertInvocationCause(
                mockCfClass.getMethod("failedFuture", Throwable.class),
                new Object[]{null},
                NullPointerException.class);
    }

    @Test
    public void testMockCompletableFutureFailedFutureIsExceptional() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");

        RuntimeException expected = new RuntimeException("boom");
        Object failed = mockCfClass.getMethod("failedFuture", Throwable.class).invoke(null, expected);
        try {
            completableFutureClass.getMethod("join").invoke(failed);
            Assertions.fail("Expected failed future to throw");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("CompletionException"));
        }
    }

    @Test
    public void testMockCompletableFutureCompletedStageReturnsValue() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockCfClass = Class.forName("org.evosuite.runtime.mock.java.util.concurrent.MockCompletableFuture");
        Class<?> completionStageClass = Class.forName("java.util.concurrent.CompletionStage");

        Object stage = mockCfClass.getMethod("completedStage", Object.class).invoke(null, "ok");
        Object cf = completionStageClass.getMethod("toCompletableFuture").invoke(stage);
        Class<?> completableFutureClass = Class.forName("java.util.concurrent.CompletableFuture");
        String value = (String) completableFutureClass.getMethod("join").invoke(cf);
        Assertions.assertEquals("ok", value);
    }

    @Test
    public void testMockProcessBuilderStartIsBlocked() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessBuilderClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessBuilder");
        ProcessBuilder builder = new ProcessBuilder("echo", "x");

        try {
            mockProcessBuilderClass.getMethod("start", ProcessBuilder.class).invoke(null, builder);
            Assertions.fail("Expected process creation to be blocked");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(cause.getClass().getName().contains("MockIOException"));
        }
    }

    @Test
    public void testMockRuntimeExecUsesProcessBuilderBridge() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")) {
            return;
        }

        MockFramework.enable();
        try {
            MockRuntime.exec(java.lang.Runtime.getRuntime(), new String[]{"echo", "x"});
            Assertions.fail("Expected process creation to be blocked");
        } catch (MockIOException expected) {
            Assertions.assertTrue(expected.getMessage().contains("Cannot start processes"));
        }
    }

    @Test
    public void testMockRuntimeExecRejectsInvalidEnvEntry() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")) {
            return;
        }

        MockFramework.enable();
        try {
            MockRuntime.exec(java.lang.Runtime.getRuntime(), new String[]{"echo", "x"}, new String[]{"BROKEN_ENV"}, null);
            Assertions.fail("Expected invalid env entry to be rejected");
        } catch (IllegalArgumentException expected) {
            Assertions.assertTrue(expected.getMessage().contains("Invalid environment variable"));
        }
    }

    @Test
    public void testMockSystemConsoleIsBlockedWhenMockingEnabled() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MockFramework.enable();

        Class<?> mockSystemClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockSystem");
        Object console = mockSystemClass.getMethod("console").invoke(null);
        Assertions.assertNull(console);
    }

    @Test
    public void testMockProcessBuilderStartPipelineReturnsDeterministicProcesses() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")
                || !isPresent("org.evosuite.runtime.mock.java.lang.MockProcess")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessBuilderClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessBuilder");
        Class<?> processClass = Class.forName("java.lang.Process");

        List<ProcessBuilder> builders = Arrays.asList(
                new ProcessBuilder("echo", "a"),
                new ProcessBuilder("echo", "b"));
        Object result = mockProcessBuilderClass.getMethod("startPipeline", List.class).invoke(null, builders);
        Assertions.assertTrue(result instanceof List);
        List<?> processes = (List<?>) result;
        Assertions.assertEquals(2, processes.size());

        long pid0 = (Long) processClass.getMethod("pid").invoke(processes.get(0));
        long pid1 = (Long) processClass.getMethod("pid").invoke(processes.get(1));
        Assertions.assertEquals(5000L, pid0);
        Assertions.assertEquals(5001L, pid1);

        Object first = processes.get(0);
        Object last = processes.get(processes.size() - 1);
        java.io.OutputStream firstIn = (java.io.OutputStream) processClass.getMethod("getOutputStream").invoke(first);
        java.io.InputStream lastOut = (java.io.InputStream) processClass.getMethod("getInputStream").invoke(last);

        firstIn.write("pipe-data".getBytes(StandardCharsets.UTF_8));
        firstIn.flush();

        byte[] buffer = new byte[32];
        int read = lastOut.read(buffer);
        Assertions.assertTrue(read > 0);
        String payload = new String(buffer, 0, read, StandardCharsets.UTF_8);
        Assertions.assertEquals("pipe-data", payload);
    }

    @Test
    public void testMockProcessBuilderStartPipelineRejectsNullElements() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessBuilderClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessBuilder");
        List<ProcessBuilder> builders = Arrays.asList(new ProcessBuilder("echo", "a"), null);
        assertInvocationCause(
                mockProcessBuilderClass.getMethod("startPipeline", List.class),
                new Object[]{builders},
                NullPointerException.class);
    }

    @Test
    public void testMockProcessBuilderStartPipelineClosePropagates() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessBuilder")
                || !isPresent("org.evosuite.runtime.mock.java.lang.MockProcess")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessBuilderClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessBuilder");
        Class<?> processClass = Class.forName("java.lang.Process");

        List<ProcessBuilder> builders = Arrays.asList(
                new ProcessBuilder("echo", "a"),
                new ProcessBuilder("echo", "b"));
        List<?> processes = (List<?>) mockProcessBuilderClass.getMethod("startPipeline", List.class).invoke(null, builders);

        Object first = processes.get(0);
        Object last = processes.get(processes.size() - 1);
        java.io.OutputStream firstIn = (java.io.OutputStream) processClass.getMethod("getOutputStream").invoke(first);
        java.io.InputStream lastOut = (java.io.InputStream) processClass.getMethod("getInputStream").invoke(last);

        firstIn.write("x".getBytes(StandardCharsets.UTF_8));
        firstIn.flush();
        Assertions.assertTrue(lastOut.read() >= 0);
        firstIn.close();
        Assertions.assertEquals(-1, lastOut.read());
    }

    @Test
    public void testMockProcessHandleIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessHandle")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessHandleClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessHandle");
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");

        Object handle = mockProcessHandleClass.getMethod("current").invoke(null);
        long pid = (Long) processHandleClass.getMethod("pid").invoke(handle);
        Assertions.assertEquals(4242L, pid);

        Object onExit = mockProcessHandleClass.getMethod("onExit", processHandleClass).invoke(null, handle);
        Class<?> cfClass = Class.forName("java.util.concurrent.CompletableFuture");
        Object joined = cfClass.getMethod("join").invoke(onExit);
        long joinedPid = (Long) processHandleClass.getMethod("pid").invoke(joined);
        Assertions.assertEquals(4242L, joinedPid);
    }

    @Test
    public void testMockProcessHandleValidation() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcessHandle")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessHandleClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcessHandle");
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");

        assertInvocationCause(
                mockProcessHandleClass.getMethod("of", long.class),
                new Object[]{0L},
                IllegalArgumentException.class);
        assertInvocationCause(
                mockProcessHandleClass.getMethod("pid", processHandleClass),
                new Object[]{null},
                NullPointerException.class);
    }

    @Test
    public void testMockProcessIsDeterministic() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcess")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcess");
        Class<?> processClass = Class.forName("java.lang.Process");
        Class<?> cfClass = Class.forName("java.util.concurrent.CompletableFuture");

        Object process = mockProcessClass.getMethod("deterministicProcess").invoke(null);
        long pid = (Long) mockProcessClass.getMethod("pid", processClass).invoke(null, process);
        int exitValue = (Integer) mockProcessClass.getMethod("exitValue", processClass).invoke(null, process);
        boolean waited = (Boolean) mockProcessClass
                .getMethod("waitFor", processClass, long.class, TimeUnit.class)
                .invoke(null, process, 1L, TimeUnit.SECONDS);
        Object onExit = mockProcessClass.getMethod("onExit", processClass).invoke(null, process);
        Object joined = cfClass.getMethod("join").invoke(onExit);
        long joinedPid = (Long) processClass.getMethod("pid").invoke(joined);

        Assertions.assertEquals(4343L, pid);
        Assertions.assertEquals(0, exitValue);
        Assertions.assertTrue(waited);
        Assertions.assertEquals(4343L, joinedPid);
    }

    @Test
    public void testMockProcessDestroyForciblyMarksProcessNotAlive() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcess")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcess");
        Class<?> processClass = Class.forName("java.lang.Process");

        Object process = mockProcessClass.getMethod("deterministicProcess").invoke(null);
        Object returned = mockProcessClass.getMethod("destroyForcibly", processClass).invoke(null, process);
        Assertions.assertSame(process, returned);
        boolean alive = (Boolean) mockProcessClass.getMethod("isAlive", processClass).invoke(null, process);
        Assertions.assertFalse(alive);
    }

    @Test
    public void testMockProcessValidation() throws Exception {
        if (!isPresent("org.evosuite.runtime.mock.java.lang.MockProcess")) {
            return;
        }

        MockFramework.enable();
        Class<?> mockProcessClass = Class.forName("org.evosuite.runtime.mock.java.lang.MockProcess");
        Class<?> processClass = Class.forName("java.lang.Process");

        assertInvocationCause(
                mockProcessClass.getMethod("pid", processClass),
                new Object[]{null},
                NullPointerException.class);
        assertInvocationCause(
                mockProcessClass.getMethod("waitFor", processClass, long.class, TimeUnit.class),
                new Object[]{null, 1L, TimeUnit.SECONDS},
                NullPointerException.class);
        Object process = mockProcessClass.getMethod("deterministicProcess").invoke(null);
        assertInvocationCause(
                mockProcessClass.getMethod("waitFor", processClass, long.class, TimeUnit.class),
                new Object[]{process, 1L, null},
                NullPointerException.class);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void assertInvocationCause(Method method, Object[] args, Class<? extends Throwable> expectedCause) throws Exception {
        try {
            boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
            Object receiver = null;
            Object[] actualArgs = args;
            if (!isStatic) {
                if (args == null || args.length == 0) {
                    throw new IllegalArgumentException("instance method requires receiver in args[0]");
                }
                receiver = args[0];
                actualArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
            }
            method.invoke(receiver, actualArgs);
            Assertions.fail("Expected invocation to throw " + expectedCause.getSimpleName());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Assertions.assertTrue(expectedCause.isInstance(cause), "Unexpected cause: " + cause);
        }
    }
}
