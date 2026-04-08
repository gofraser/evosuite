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
package org.evosuite.runtime.mock.javax.imageio.stream;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.runtime.mock.java.io.MockRandomAccessFile;

import javax.imageio.stream.FileImageOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Custom mock implementation of {@link javax.imageio.stream.FileImageOutputStream}.
 *
 * <p>The real {@code FileImageOutputStream(File)} constructor creates a
 * {@code RandomAccessFile} internally, bypassing EvoSuite's VFS.
 * This mock routes through {@link MockRandomAccessFile} instead.
 */
public class MockFileImageOutputStream extends FileImageOutputStream implements OverrideMock {

    public MockFileImageOutputStream(File file) throws FileNotFoundException, IOException {
        super(createRAF(file));
    }

    public MockFileImageOutputStream(RandomAccessFile raf) {
        super(raf);
    }

    private static RandomAccessFile createRAF(File file) throws FileNotFoundException {
        if (!MockFramework.isEnabled()) {
            return new RandomAccessFile(file, "rw");
        }
        File mockFile = (file != null) ? new MockFile(file.getPath()) : null;
        return new MockRandomAccessFile(mockFile, "rw");
    }
}
