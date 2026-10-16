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
package org.evosuite.runtime;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.vfs.VirtualFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuntimeResetVfsTest {

    private final boolean useVFS = RuntimeSettings.useVFS;

    @AfterEach
    public void restore() {
        RuntimeSettings.useVFS = useVFS;
        VirtualFileSystem.getInstance().resetSingleton();
        MockFramework.disable();
    }

    @Test
    public void filesOfAnEarlierTestDoNotLeakIntoOneWithoutVfs() {
        String path = new File("leftOverFromAnEarlierTest.txt").getAbsolutePath();

        // a generated test that uses the VFS creates a file there
        RuntimeSettings.useVFS = true;
        Runtime.getInstance().resetRuntime();
        assertTrue(VirtualFileSystem.getInstance().createFile(path));

        // the next test does not use the VFS, but mocked File classes still consult it
        RuntimeSettings.useVFS = false;
        Runtime.getInstance().resetRuntime();

        assertFalse(VirtualFileSystem.getInstance().exists(path));
    }
}
