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
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;

/**
 * Static replacement for {@link FileSystem} WatchService entry points.
 */
public class MockNioFileSystem implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return FileSystem.class.getName();
    }

    public static WatchService newWatchService(FileSystem fileSystem) throws IOException {
        if (!MockFramework.isEnabled()) {
            return fileSystem.newWatchService();
        }
        return new DeterministicWatchService();
    }

    public static PathMatcher getPathMatcher(FileSystem fileSystem, String syntaxAndPattern) {
        if (!MockFramework.isEnabled()) {
            return fileSystem.getPathMatcher(syntaxAndPattern);
        }
        final PathMatcher delegate = fileSystem.getPathMatcher(syntaxAndPattern);
        return new PathMatcher() {
            @Override
            public boolean matches(Path path) {
                return delegate.matches(path);
            }
        };
    }

    public static void emitEvent(WatchService watchService, Path watchedPath, java.nio.file.WatchEvent.Kind<?> kind,
                                 Path context) throws IOException {
        if (!MockFramework.isEnabled()) {
            throw new MockIOException("Event emission is only available in mocked execution");
        }
        if (!(watchService instanceof DeterministicWatchService)) {
            throw new MockIOException("Unsupported WatchService implementation for mocked event emission");
        }
        ((DeterministicWatchService) watchService).emit(watchedPath, kind, context);
    }
}
