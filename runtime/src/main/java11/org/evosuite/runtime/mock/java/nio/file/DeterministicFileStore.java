/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.file;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

final class DeterministicFileStore extends FileStore {

    static final DeterministicFileStore INSTANCE = new DeterministicFileStore();

    private static final long TOTAL_SPACE = 1_073_741_824L; // 1 GiB
    private static final long USABLE_SPACE = 805_306_368L; // 768 MiB
    private static final long UNALLOCATED_SPACE = 805_306_368L;

    private DeterministicFileStore() {
    }

    @Override
    public String name() {
        return "evosuite-vfs";
    }

    @Override
    public String type() {
        return "evosuite";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long getTotalSpace() {
        return TOTAL_SPACE;
    }

    @Override
    public long getUsableSpace() {
        return USABLE_SPACE;
    }

    @Override
    public long getUnallocatedSpace() {
        return UNALLOCATED_SPACE;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return type == BasicFileAttributeView.class;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return "basic".equalsIgnoreCase(name);
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(String attribute) throws IOException {
        if ("totalSpace".equals(attribute)) {
            return getTotalSpace();
        }
        if ("usableSpace".equals(attribute)) {
            return getUsableSpace();
        }
        if ("unallocatedSpace".equals(attribute)) {
            return getUnallocatedSpace();
        }
        if ("readOnly".equals(attribute)) {
            return isReadOnly();
        }
        if ("name".equals(attribute)) {
            return name();
        }
        if ("type".equals(attribute)) {
            return type();
        }
        throw new UnsupportedOperationException("Unsupported FileStore attribute: " + attribute);
    }
}
