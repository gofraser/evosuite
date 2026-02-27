/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.nio.file;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;

/**
 * Static replacement for {@link FileSystems}.
 */
public class MockFileSystems implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return FileSystems.class.getName();
    }

    public static FileSystem getDefault() {
        if (!MockFramework.isEnabled()) {
            return FileSystems.getDefault();
        }
        return Paths.get("").getFileSystem();
    }

    public static FileSystem getFileSystem(URI uri) {
        if (!MockFramework.isEnabled()) {
            return FileSystems.getFileSystem(uri);
        }
        return getDefault();
    }

    public static FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(uri, env);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static FileSystem newFileSystem(URI uri, Map<String, ?> env, ClassLoader loader) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(uri, env, loader);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static FileSystem newFileSystem(Path path, ClassLoader loader) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(path, loader);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static FileSystem newFileSystem(Path path) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(path);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(path, env);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static FileSystem newFileSystem(Path path, Map<String, ?> env, ClassLoader loader) throws IOException {
        if (!MockFramework.isEnabled()) {
            return FileSystems.newFileSystem(path, env, loader);
        }
        throw new MockIOException("Creating custom FileSystem providers is disabled in mocked execution");
    }

    public static Iterable<FileSystemProvider> installedProviders() {
        if (!MockFramework.isEnabled()) {
            return FileSystemProvider.installedProviders();
        }
        return Collections.emptyList();
    }
}
