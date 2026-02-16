/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.file;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.nio.file.FileStore;

/**
 * Marker mock for {@link FileStore}; behavior is provided through {@link MockFiles#getFileStore}.
 */
public class MockFileStore implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return FileStore.class.getName();
    }
}
