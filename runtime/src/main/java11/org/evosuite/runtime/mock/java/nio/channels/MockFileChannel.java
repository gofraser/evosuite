/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.EvoFileChannel;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.runtime.mock.java.io.MockFileInputStream;
import org.evosuite.runtime.mock.java.io.MockFileOutputStream;
import org.evosuite.runtime.mock.java.io.MockRandomAccessFile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic replacement for {@link FileChannel} open entry points.
 */
public class MockFileChannel implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return FileChannel.class.getName();
    }

    public static FileChannel open(Path path, OpenOption... options) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileChannel.open(path, options);
        }
        return open(path, toSet(options));
    }

    public static FileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileChannel.open(path, options, attrs);
        }
        return open(path, options);
    }

    private static FileChannel open(Path path, Set<? extends OpenOption> options) throws IOException {
        Set<? extends OpenOption> opts = (options == null) ? Collections.emptySet() : options;

        boolean append = opts.contains(StandardOpenOption.APPEND);
        boolean truncate = opts.contains(StandardOpenOption.TRUNCATE_EXISTING);
        boolean create = opts.contains(StandardOpenOption.CREATE);
        boolean createNew = opts.contains(StandardOpenOption.CREATE_NEW);
        boolean write = append || opts.contains(StandardOpenOption.WRITE) || truncate || create || createNew;
        boolean read = opts.isEmpty() || opts.contains(StandardOpenOption.READ) || !write;

        if (append && truncate) {
            throw new IllegalArgumentException("APPEND and TRUNCATE_EXISTING are mutually exclusive");
        }

        MockFile file = new MockFile(path.toString());

        if (createNew) {
            if (!file.createNewFile()) {
                throw new FileAlreadyExistsException(path.toString());
            }
        } else if (create && !file.exists()) {
            file.createNewFile();
        }

        if (!file.exists() && read && !write) {
            throw new NoSuchFileException(path.toString());
        }

        if (read && write) {
            int initialPosition = 0;
            try (MockRandomAccessFile raf = new MockRandomAccessFile(file, "rw")) {
                if (truncate) {
                    raf.setLength(0L);
                }
                if (append) {
                    long len = raf.length();
                    if (len > Integer.MAX_VALUE) {
                        throw new IOException("Virtual file system does not support files larger than "
                                + Integer.MAX_VALUE + " bytes");
                    }
                    initialPosition = (int) len;
                }
            }
            return EvoFileChannel.create(file.getAbsolutePath(), true, true, initialPosition);
        }

        if (write) {
            return new MockFileOutputStream(file, append).getChannel();
        }

        return new MockFileInputStream(file).getChannel();
    }

    private static Set<OpenOption> toSet(OpenOption... options) {
        Set<OpenOption> set = new HashSet<>();
        if (options != null) {
            Collections.addAll(set, options);
        }
        return set;
    }
}
