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
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Static replacement for {@link Path} WatchService registration entry points.
 */
public class MockNioPath implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return Path.class.getName();
    }

    public static WatchKey register(Path path, WatchService watcher, WatchEvent.Kind<?>... events)
            throws IOException {
        if (!MockFramework.isEnabled()) {
            return path.register(watcher, events);
        }
        if (!(watcher instanceof DeterministicWatchService)) {
            throw new MockIOException("Path.watch registration requires deterministic WatchService");
        }
        return ((DeterministicWatchService) watcher).register(path, events);
    }

    public static WatchKey register(
            Path path,
            WatchService watcher,
            WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers) throws IOException {
        if (!MockFramework.isEnabled()) {
            return path.register(watcher, events, modifiers);
        }
        if (!(watcher instanceof DeterministicWatchService)) {
            throw new MockIOException("Path.watch registration requires deterministic WatchService");
        }
        return ((DeterministicWatchService) watcher).register(path, events, modifiers);
    }
}
