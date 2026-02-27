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
package org.evosuite.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class InitializingListenerTest {

    @Test
    public void testScaffoldingListIsUsedWhenPresent() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-init-priority-");
        File scaffolding = tempDir.resolve(InitializingListener.SCAFFOLDING_LIST_FILE_STRING).toFile();

        List<String> scaffoldingClasses = Arrays.asList("org.foo.Legacy_scaffolding");
        InitializingListenerUtils.writeInitializationClassList(scaffolding, scaffoldingClasses);

        List<String> parsed = InitializingListener.readInitializationClasses(scaffolding);
        Assertions.assertEquals(scaffoldingClasses, parsed);

        deleteTempDir(tempDir);
    }

    @Test
    public void testEmptyInitializationListWhenScaffoldingListMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-init-empty-meta-");
        File scaffolding = tempDir.resolve(InitializingListener.SCAFFOLDING_LIST_FILE_STRING).toFile();

        List<String> parsed = InitializingListener.readInitializationClasses(scaffolding);
        Assertions.assertTrue(parsed.isEmpty());

        deleteTempDir(tempDir);
    }

    @Test
    public void testScaffoldingListIsUsed() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-init-scaff-fallback-");
        File scaffolding = tempDir.resolve(InitializingListener.SCAFFOLDING_LIST_FILE_STRING).toFile();

        List<String> expected = Arrays.asList("org.foo.A_scaffolding", "org.foo.B_scaffolding");
        InitializingListenerUtils.writeInitializationClassList(scaffolding, expected);

        List<String> parsed = InitializingListener.readInitializationClasses(scaffolding);
        Assertions.assertEquals(expected, parsed);

        deleteTempDir(tempDir);
    }

    private static void deleteTempDir(Path tempDir) throws Exception {
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort cleanup for test-only files
                }
            });
        }
    }
}
