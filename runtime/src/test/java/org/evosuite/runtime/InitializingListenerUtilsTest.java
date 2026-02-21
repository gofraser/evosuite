/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

public class InitializingListenerUtilsTest {

    @Test
    public void testWriteAndReadInitializationClassList() throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-init-list-");
        File metadata = tempDir.resolve(InitializingListener.INITIALIZATION_METADATA_FILE_STRING).toFile();

        List<String> classes = Arrays.asList(
                "org.foo.Bar_ESTest_scaffolding",
                "org.foo.Baz_ESTest_scaffolding"
        );
        InitializingListenerUtils.writeInitializationClassList(metadata, classes);

        List<String> parsed = InitializingListenerUtils.readInitializationClassList(metadata);
        Assertions.assertEquals(classes, parsed);

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
