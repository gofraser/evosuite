/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.file;

import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.runtime.mock.java.io.MockFileInputStream;
import org.evosuite.runtime.mock.java.io.MockFileOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;
import java.util.function.BiPredicate;
import java.util.concurrent.atomic.AtomicInteger;
import org.evosuite.runtime.mock.java.nio.channels.MockFileChannel;

/**
 * Small deterministic subset of {@link Files} backed by existing java.io mocks.
 */
public class MockFiles implements StaticReplacementMock {

    private static final AtomicInteger TEMP_COUNTER = new AtomicInteger(0);

    @Override
    public String getMockedClassName() {
        return Files.class.getName();
    }

    public static boolean exists(Path path, LinkOption... options) {
        return asMockFile(path).exists();
    }

    public static boolean notExists(Path path, LinkOption... options) {
        return !exists(path, options);
    }

    public static boolean isDirectory(Path path, LinkOption... options) {
        return asMockFile(path).isDirectory();
    }

    public static boolean isRegularFile(Path path, LinkOption... options) {
        return asMockFile(path).isFile();
    }

    public static boolean isReadable(Path path) {
        return asMockFile(path).canRead();
    }

    public static boolean isWritable(Path path) {
        return asMockFile(path).canWrite();
    }

    public static boolean isExecutable(Path path) {
        return asMockFile(path).canExecute();
    }

    public static boolean isHidden(Path path) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return file.isHidden();
    }

    public static boolean isSymbolicLink(Path path) {
        return false;
    }

    public static long size(Path path) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return file.length();
    }

    public static FileTime getLastModifiedTime(Path path, LinkOption... options) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return FileTime.fromMillis(Math.max(0L, file.lastModified()));
    }

    public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
        if (time == null) {
            throw new NullPointerException("time");
        }
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        if (!file.setLastModified(time.toMillis())) {
            throw new IOException("Failed to set lastModifiedTime on " + path);
        }
        return path;
    }

    public static boolean isSameFile(Path path, Path path2) throws IOException {
        if (path == null || path2 == null) {
            throw new NullPointerException();
        }
        String left = asMockFile(path).getAbsolutePath();
        String right = asMockFile(path2).getAbsolutePath();
        if (left.equals(right)) {
            return true;
        }
        if (!asMockFile(path).exists()) {
            throw new NoSuchFileException(path.toString());
        }
        if (!asMockFile(path2).exists()) {
            throw new NoSuchFileException(path2.toString());
        }
        return false;
    }

    public static Path createFile(Path path) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.createNewFile()) {
            throw new IOException("File already exists: " + path);
        }
        return path;
    }

    public static Path createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        MockFile file = asMockFile(dir);
        if (file.exists()) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!file.mkdir()) {
            throw new IOException("Failed to create directory: " + dir);
        }
        return dir;
    }

    public static Path createDirectories(Path dir, FileAttribute<?>... attrs) throws IOException {
        MockFile file = asMockFile(dir);
        if (file.exists()) {
            if (!file.isDirectory()) {
                throw new FileAlreadyExistsException(dir.toString());
            }
            return dir;
        }

        if (!file.mkdirs()) {
            throw new IOException("Failed to create directories: " + dir);
        }
        return dir;
    }

    public static Path createTempFile(Path dir, String prefix, String suffix, FileAttribute<?>... attrs)
            throws IOException {
        String p = normalizePrefix(prefix);
        String s = normalizeSuffix(suffix);
        if (dir == null) {
            java.io.File tmp = MockFile.createTempFile(p, s);
            return tmp.toPath();
        }
        createDirectories(dir);
        return createFile(uniqueTempPath(dir, p, s));
    }

    public static Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
        return createTempFile(null, prefix, suffix, attrs);
    }

    public static Path createTempDirectory(Path dir, String prefix, FileAttribute<?>... attrs) throws IOException {
        String p = normalizePrefix(prefix);
        Path parent;
        if (dir == null) {
            parent = new MockFile(System.getProperty("java.io.tmpdir")).toPath();
        } else {
            parent = dir;
        }
        createDirectories(parent);

        for (int i = 0; i < 10000; i++) {
            Path candidate = parent.resolve(p + TEMP_COUNTER.getAndIncrement());
            MockFile file = asMockFile(candidate);
            if (!file.exists() && file.mkdir()) {
                return candidate;
            }
        }
        throw new IOException("Failed to create temp directory");
    }

    public static Path createTempDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
        return createTempDirectory(null, prefix, attrs);
    }

    public static boolean deleteIfExists(Path path) {
        return asMockFile(path).delete();
    }

    public static void delete(Path path) throws IOException {
        if (!deleteIfExists(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        validateInputStreamOptions(options);
        return new MockFileInputStream(asMockFile(path));
    }

    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        boolean append = false;
        boolean truncate = false;
        boolean create = false;
        boolean createNew = false;

        if (options == null || options.length == 0) {
            create = true;
            truncate = true;
        } else {
            for (OpenOption option : options) {
                if (option == null) {
                    continue;
                }
                if (option == StandardOpenOption.APPEND) {
                    append = true;
                } else if (option == StandardOpenOption.TRUNCATE_EXISTING) {
                    truncate = true;
                } else if (option == StandardOpenOption.CREATE) {
                    create = true;
                } else if (option == StandardOpenOption.CREATE_NEW) {
                    createNew = true;
                } else if (option == StandardOpenOption.DELETE_ON_CLOSE) {
                    throw new UnsupportedOperationException("DELETE_ON_CLOSE is not supported in mocked execution");
                } else if (option == StandardOpenOption.WRITE) {
                    // explicit WRITE is valid for output streams
                } else if (option == StandardOpenOption.READ) {
                    throw new IllegalArgumentException("READ is not valid for newOutputStream");
                }
            }
        }

        if (append && truncate) {
            throw new IllegalArgumentException("APPEND and TRUNCATE_EXISTING are mutually exclusive");
        }

        MockFile file = asMockFile(path);
        if (createNew) {
            if (!file.createNewFile()) {
                throw new FileAlreadyExistsException(path.toString());
            }
        } else if (!file.exists()) {
            if (create) {
                file.createNewFile();
            } else {
                throw new NoSuchFileException(path.toString());
            }
        }

        boolean openAppend = append && !truncate;
        return new MockFileOutputStream(file, openAppend);
    }

    public static BufferedReader newBufferedReader(Path path) throws IOException {
        return newBufferedReader(path, StandardCharsets.UTF_8);
    }

    public static BufferedReader newBufferedReader(Path path, Charset cs) throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStream(path), cs));
    }

    public static BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        return newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    public static BufferedWriter newBufferedWriter(Path path, Charset cs, OpenOption... options) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(newOutputStream(path, options), cs));
    }

    public static SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                     FileAttribute<?>... attrs) throws IOException {
        validateByteChannelOptions(options);
        return MockFileChannel.open(path, options, attrs);
    }

    public static SeekableByteChannel newByteChannel(Path path, OpenOption... options) throws IOException {
        Set<OpenOption> opts = new HashSet<>();
        if (options != null) {
            Collections.addAll(opts, options);
        }
        return newByteChannel(path, opts);
    }

    public static byte[] readAllBytes(Path path) throws IOException {
        try (InputStream in = newInputStream(path)) {
            byte[] buffer = new byte[4096];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset cs) throws IOException {
        return new String(readAllBytes(path), cs);
    }

    public static List<String> readAllLines(Path path) throws IOException {
        return readAllLines(path, StandardCharsets.UTF_8);
    }

    public static List<String> readAllLines(Path path, Charset cs) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(newInputStream(path), cs))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    public static Stream<String> lines(Path path) throws IOException {
        return readAllLines(path).stream();
    }

    public static Stream<String> lines(Path path, Charset cs) throws IOException {
        return readAllLines(path, cs).stream();
    }

    public static Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
        try (OutputStream out = newOutputStream(path, options)) {
            out.write(bytes);
        }
        return path;
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, OpenOption... options)
            throws IOException {
        return write(path, lines, StandardCharsets.UTF_8, options);
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, Charset cs, OpenOption... options)
            throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(newOutputStream(path, options), cs))) {
            if (lines != null) {
                for (CharSequence line : lines) {
                    writer.write(line == null ? "null" : line.toString());
                    writer.newLine();
                }
            }
        }
        return path;
    }

    public static Path writeString(Path path, CharSequence value, OpenOption... options) throws IOException {
        return writeString(path, value, StandardCharsets.UTF_8, options);
    }

    public static Path writeString(Path path, CharSequence value, Charset cs, OpenOption... options) throws IOException {
        return write(path, value.toString().getBytes(cs), options);
    }

    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        validateCopyOptions(options, false);
        MockFile src = asMockFile(source);
        if (!src.exists()) {
            throw new NoSuchFileException(source.toString());
        }
        if (src.isDirectory()) {
            throw new IOException("Directory copy is not supported by mocked Files.copy");
        }

        boolean replaceExisting = hasOption(options, StandardCopyOption.REPLACE_EXISTING);
        MockFile dst = asMockFile(target);
        if (dst.exists() && !replaceExisting) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (dst.exists()) {
            dst.delete();
        }

        return write(target, readAllBytes(source));
    }

    public static long copy(InputStream in, Path target, CopyOption... options) throws IOException {
        if (in == null) {
            throw new NullPointerException("in");
        }
        validateCopyOptions(options, false);
        boolean replaceExisting = hasOption(options, StandardCopyOption.REPLACE_EXISTING);
        MockFile dst = asMockFile(target);
        if (dst.exists() && !replaceExisting) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (dst.exists()) {
            dst.delete();
        }

        long count = 0L;
        byte[] buffer = new byte[4096];
        try (OutputStream out = newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                count += read;
            }
        }
        return count;
    }

    public static long copy(Path source, OutputStream out) throws IOException {
        if (out == null) {
            throw new NullPointerException("out");
        }
        MockFile src = asMockFile(source);
        if (!src.exists()) {
            throw new NoSuchFileException(source.toString());
        }
        if (src.isDirectory()) {
            throw new IOException("Directory copy is not supported by mocked Files.copy");
        }

        long count = 0L;
        byte[] buffer = new byte[4096];
        try (InputStream in = newInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                count += read;
            }
        }
        return count;
    }

    public static Path move(Path source, Path target, CopyOption... options) throws IOException {
        validateCopyOptions(options, true);
        if (hasOption(options, StandardCopyOption.ATOMIC_MOVE)) {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "ATOMIC_MOVE is not supported in mocked execution");
        }
        Path moved = copy(source, target, options);
        delete(source);
        return moved;
    }

    public static Path createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        throw new IOException("Symbolic links are disabled in mocked execution");
    }

    public static Path createLink(Path link, Path existing) throws IOException {
        throw new IOException("Hard links are disabled in mocked execution");
    }

    public static Path readSymbolicLink(Path link) throws IOException {
        throw new IOException("Symbolic links are disabled in mocked execution");
    }

    public static String probeContentType(Path path) throws IOException {
        if (path == null) {
            throw new NullPointerException("path");
        }
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return "text/plain";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".bin")) {
            return "application/octet-stream";
        }
        return null;
    }

    public static FileStore getFileStore(Path path) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return DeterministicFileStore.INSTANCE;
    }

    public static Stream<Path> list(Path dir) throws IOException {
        MockFile base = asMockFile(dir);
        if (!base.exists()) {
            throw new NoSuchFileException(dir.toString());
        }
        if (!base.isDirectory()) {
            throw new NotDirectoryException(dir.toString());
        }
        String[] children = base.list();
        List<Path> entries = new ArrayList<>();
        if (children != null) {
            for (String child : children) {
                entries.add(dir.resolve(child));
            }
        }
        Collections.sort(entries);
        return entries.stream();
    }

    public static Stream<Path> walk(Path start, FileVisitOption... options) throws IOException {
        validateWalkOptions(options);
        return walk(start, Integer.MAX_VALUE, options);
    }

    public static Stream<Path> walk(Path start, int maxDepth, FileVisitOption... options) throws IOException {
        validateWalkOptions(options);
        MockFile root = asMockFile(start);
        if (!root.exists()) {
            throw new NoSuchFileException(start.toString());
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth is negative");
        }

        List<Path> entries = new ArrayList<>();
        collectWalk(start, entries, maxDepth, 0);
        return entries.stream();
    }

    public static Stream<Path> find(Path start, int maxDepth, BiPredicate<Path, BasicFileAttributes> matcher,
                                    FileVisitOption... options) throws IOException {
        if (matcher == null) {
            throw new NullPointerException("matcher");
        }
        validateWalkOptions(options);
        return walk(start, maxDepth, options).filter(path -> matcher.test(path, attributesOf(path)));
    }

    public static Path walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
        return walkFileTree(start, Collections.emptySet(), Integer.MAX_VALUE, visitor);
    }

    public static Path walkFileTree(Path start, Set<FileVisitOption> options, int maxDepth,
                                    FileVisitor<? super Path> visitor) throws IOException {
        if (visitor == null) {
            throw new NullPointerException("visitor");
        }
        validateWalkOptions(options == null ? null : options.toArray(new FileVisitOption[0]));
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth is negative");
        }
        MockFile root = asMockFile(start);
        if (!root.exists()) {
            throw new NoSuchFileException(start.toString());
        }
        walkFileTreeRecursive(start, 0, maxDepth, visitor);
        return start;
    }

    public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        return getBasicAttribute(path, attribute);
    }

    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        AttributeSpec spec = parseAttributeSpec(attributes);
        if (!"basic".equals(spec.view)) {
            throw new UnsupportedOperationException("Only basic attributes are supported");
        }

        BasicFileAttributes attrs = attributesOfExisting(path);
        Map<String, Object> out = new LinkedHashMap<>();
        if ("*".equals(spec.attribute)) {
            out.put("size", attrs.size());
            out.put("creationTime", attrs.creationTime());
            out.put("lastModifiedTime", attrs.lastModifiedTime());
            out.put("lastAccessTime", attrs.lastAccessTime());
            out.put("isDirectory", attrs.isDirectory());
            out.put("isRegularFile", attrs.isRegularFile());
            out.put("isSymbolicLink", attrs.isSymbolicLink());
            out.put("isOther", attrs.isOther());
            out.put("fileKey", attrs.fileKey());
            return out;
        }
        out.put(spec.attribute, getBasicAttribute(path, "basic:" + spec.attribute));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (!type.isAssignableFrom(BasicFileAttributes.class) && type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("Only BasicFileAttributes are supported");
        }
        return (A) attributesOfExisting(path);
    }

    public static Path setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        AttributeSpec spec = parseAttributeSpec(attribute);
        if (!"basic".equals(spec.view)) {
            throw new UnsupportedOperationException("Only basic attributes are supported");
        }
        if (!"lastModifiedTime".equals(spec.attribute)) {
            throw new UnsupportedOperationException("Only basic:lastModifiedTime is writable");
        }
        if (!(value instanceof FileTime)) {
            throw new IllegalArgumentException("lastModifiedTime requires a FileTime value");
        }

        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        if (!file.setLastModified(((FileTime) value).toMillis())) {
            throw new IOException("Failed to set lastModifiedTime on " + path);
        }
        return path;
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
        return newDirectoryStream(dir, entry -> true);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir, String glob) throws IOException {
        final java.nio.file.PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
        return newDirectoryStream(dir, entry -> matcher.matches(entry.getFileName()));
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        MockFile base = asMockFile(dir);
        if (!base.exists()) {
            throw new NoSuchFileException(dir.toString());
        }
        if (!base.isDirectory()) {
            throw new NotDirectoryException(dir.toString());
        }

        String[] children = base.list();
        List<Path> entries = new ArrayList<>();
        if (children != null) {
            for (String child : children) {
                Path entry = dir.resolve(child);
                try {
                    if (filter == null || filter.accept(entry)) {
                        entries.add(entry);
                    }
                } catch (IOException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new DirectoryIteratorException(new IOException(e));
                }
            }
        }
        Collections.sort(entries);
        return new MockDirectoryStream(entries);
    }

    private static MockFile asMockFile(Path path) {
        return new MockFile(path.toString());
    }

    private static boolean hasOption(CopyOption[] options, CopyOption expected) {
        if (options == null) {
            return false;
        }
        for (CopyOption option : options) {
            if (option == expected) {
                return true;
            }
        }
        return false;
    }

    private static void validateCopyOptions(CopyOption[] options, boolean forMove) {
        if (options == null) {
            return;
        }
        for (CopyOption option : options) {
            if (option == null) {
                continue;
            }
            if (option == StandardCopyOption.REPLACE_EXISTING || option == LinkOption.NOFOLLOW_LINKS) {
                continue;
            }
            if (!forMove && option == StandardCopyOption.COPY_ATTRIBUTES) {
                continue;
            }
            if (forMove && option == StandardCopyOption.ATOMIC_MOVE) {
                continue;
            }
            throw new UnsupportedOperationException("Unsupported copy/move option in mocked execution: " + option);
        }
    }

    private static void validateWalkOptions(FileVisitOption... options) {
        if (options == null) {
            return;
        }
        for (FileVisitOption option : options) {
            if (option == null) {
                continue;
            }
            if (option == FileVisitOption.FOLLOW_LINKS) {
                throw new UnsupportedOperationException("FOLLOW_LINKS is not supported in mocked execution");
            }
            throw new UnsupportedOperationException("Unsupported walk option in mocked execution: " + option);
        }
    }

    private static void validateInputStreamOptions(OpenOption... options) {
        if (options == null) {
            return;
        }
        for (OpenOption option : options) {
            if (option == null || option == StandardOpenOption.READ || option == LinkOption.NOFOLLOW_LINKS) {
                continue;
            }
            if (option == StandardOpenOption.DELETE_ON_CLOSE) {
                throw new UnsupportedOperationException("DELETE_ON_CLOSE is not supported in mocked execution");
            }
            throw new IllegalArgumentException("Unsupported option for newInputStream: " + option);
        }
    }

    private static void validateByteChannelOptions(Set<? extends OpenOption> options) {
        if (options == null) {
            return;
        }
        for (OpenOption option : options) {
            if (option == null
                    || option == StandardOpenOption.READ
                    || option == StandardOpenOption.WRITE
                    || option == StandardOpenOption.APPEND
                    || option == StandardOpenOption.TRUNCATE_EXISTING
                    || option == StandardOpenOption.CREATE
                    || option == StandardOpenOption.CREATE_NEW
                    || option == StandardOpenOption.SPARSE
                    || option == StandardOpenOption.SYNC
                    || option == StandardOpenOption.DSYNC
                    || option == LinkOption.NOFOLLOW_LINKS) {
                continue;
            }
            if (option == StandardOpenOption.DELETE_ON_CLOSE) {
                throw new UnsupportedOperationException("DELETE_ON_CLOSE is not supported in mocked execution");
            }
            throw new UnsupportedOperationException("Unsupported option for newByteChannel: " + option);
        }
    }

    private static String normalizePrefix(String prefix) {
        return (prefix == null || prefix.isEmpty()) ? "tmp" : prefix;
    }

    private static String normalizeSuffix(String suffix) {
        return (suffix == null || suffix.isEmpty()) ? ".tmp" : suffix;
    }

    private static Path uniqueTempPath(Path dir, String prefix, String suffix) {
        return dir.resolve(prefix + TEMP_COUNTER.getAndIncrement() + suffix);
    }

    private static void collectWalk(Path current, List<Path> entries, int maxDepth, int depth) throws IOException {
        entries.add(current);
        if (depth >= maxDepth) {
            return;
        }

        MockFile currentFile = asMockFile(current);
        if (!currentFile.isDirectory()) {
            return;
        }

        String[] children = currentFile.list();
        if (children == null || children.length == 0) {
            return;
        }
        List<String> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        Collections.sort(sorted);
        for (String child : sorted) {
            collectWalk(current.resolve(child), entries, maxDepth, depth + 1);
        }
    }

    private static FileVisitResult walkFileTreeRecursive(Path current, int depth, int maxDepth,
                                                         FileVisitor<? super Path> visitor) throws IOException {
        MockFile file = asMockFile(current);
        if (!file.exists()) {
            FileVisitResult missingResult = visitor.visitFileFailed(current, new NoSuchFileException(current.toString()));
            return normalizeVisitResult(missingResult);
        }

        if (file.isDirectory()) {
            FileVisitResult pre = normalizeVisitResult(visitor.preVisitDirectory(current, attributesOf(current)));
            if (pre == FileVisitResult.TERMINATE) {
                return pre;
            }
            if (pre != FileVisitResult.SKIP_SUBTREE && depth < maxDepth) {
                String[] children = file.list();
                if (children != null && children.length > 0) {
                    List<String> sorted = new ArrayList<>();
                    Collections.addAll(sorted, children);
                    Collections.sort(sorted);
                    for (String child : sorted) {
                        FileVisitResult childResult =
                                walkFileTreeRecursive(current.resolve(child), depth + 1, maxDepth, visitor);
                        if (childResult == FileVisitResult.TERMINATE) {
                            return childResult;
                        }
                        if (childResult == FileVisitResult.SKIP_SIBLINGS) {
                            break;
                        }
                    }
                }
            }
            FileVisitResult post = normalizeVisitResult(visitor.postVisitDirectory(current, null));
            if (post == FileVisitResult.TERMINATE) {
                return post;
            }
            return pre == FileVisitResult.SKIP_SIBLINGS ? FileVisitResult.SKIP_SIBLINGS : post;
        }

        return normalizeVisitResult(visitor.visitFile(current, attributesOf(current)));
    }

    private static FileVisitResult normalizeVisitResult(FileVisitResult result) {
        return result == null ? FileVisitResult.CONTINUE : result;
    }

    private static BasicFileAttributes attributesOf(Path path) {
        MockFile file = asMockFile(path);
        long lastModified = file.lastModified();
        FileTime modified = FileTime.fromMillis(Math.max(0L, lastModified));
        final boolean directory = file.isDirectory();
        final boolean regular = file.isFile();
        final long size = file.length();

        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return modified;
            }

            @Override
            public FileTime lastAccessTime() {
                return modified;
            }

            @Override
            public FileTime creationTime() {
                return modified;
            }

            @Override
            public boolean isRegularFile() {
                return regular;
            }

            @Override
            public boolean isDirectory() {
                return directory;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public Object fileKey() {
                return null;
            }
        };
    }

    private static BasicFileAttributes attributesOfExisting(Path path) throws IOException {
        MockFile file = asMockFile(path);
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString());
        }
        return attributesOf(path);
    }

    private static Object getBasicAttribute(Path path, String attribute) throws IOException {
        AttributeSpec spec = parseAttributeSpec(attribute);
        if (!"basic".equals(spec.view)) {
            throw new UnsupportedOperationException("Only basic attributes are supported");
        }
        BasicFileAttributes attrs = attributesOfExisting(path);
        switch (spec.attribute) {
            case "size":
                return attrs.size();
            case "creationTime":
                return attrs.creationTime();
            case "lastModifiedTime":
                return attrs.lastModifiedTime();
            case "lastAccessTime":
                return attrs.lastAccessTime();
            case "isDirectory":
                return attrs.isDirectory();
            case "isRegularFile":
                return attrs.isRegularFile();
            case "isSymbolicLink":
                return attrs.isSymbolicLink();
            case "isOther":
                return attrs.isOther();
            case "fileKey":
                return attrs.fileKey();
            default:
                throw new UnsupportedOperationException("Unsupported basic attribute: " + spec.attribute);
        }
    }

    private static AttributeSpec parseAttributeSpec(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            throw new IllegalArgumentException("Attribute is empty");
        }
        String view = "basic";
        String attr = attribute;
        int idx = attribute.indexOf(':');
        if (idx >= 0) {
            view = attribute.substring(0, idx).trim();
            attr = attribute.substring(idx + 1).trim();
        }
        if (view.isEmpty() || attr.isEmpty()) {
            throw new IllegalArgumentException("Invalid attribute syntax: " + attribute);
        }
        return new AttributeSpec(view, attr);
    }

    private static final class AttributeSpec {
        private final String view;
        private final String attribute;

        private AttributeSpec(String view, String attribute) {
            this.view = view;
            this.attribute = attribute;
        }
    }

    private static final class MockDirectoryStream implements DirectoryStream<Path> {
        private final List<Path> entries;
        private boolean open = true;
        private boolean iterated = false;

        private MockDirectoryStream(List<Path> entries) {
            this.entries = entries;
        }

        @Override
        public Iterator<Path> iterator() {
            if (!open) {
                throw new IllegalStateException("Directory stream closed");
            }
            if (iterated) {
                throw new IllegalStateException("Iterator already obtained");
            }
            iterated = true;
            return entries.iterator();
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
