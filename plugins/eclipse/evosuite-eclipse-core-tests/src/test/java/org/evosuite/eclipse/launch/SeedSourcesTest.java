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
package org.evosuite.eclipse.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where EvoSuite reads the sources of the test classes that a run extends. */
public class SeedSourcesTest {

    @Test
    public void testClassesInOneSourceFolderAreReadFromIt(@TempDir Path project) throws Exception {
        File root = project.resolve("src/test/java").toFile();
        GenerationTarget target = GenerationTarget.extending("demo.Vault", List.of(
                new GenerationTarget.Seed("demo.VaultTest", root), new GenerationTarget.Seed("demo.VaultIT", root)));
        assertEquals(root, TestGenerationJob.seedSourceDir(target, project.resolve("out").toFile()));
    }

    /** EvoSuite takes one source folder, so test classes from several are copied into one. */
    @Test
    public void testClassesInSeveralSourceFoldersAreCopiedTogether(@TempDir Path project) throws Exception {
        Path unit = project.resolve("src/test/java/demo/VaultTest.java");
        Path integration = project.resolve("src/it/java/demo/VaultIT.java");
        Files.createDirectories(unit.getParent());
        Files.createDirectories(integration.getParent());
        Files.writeString(unit, "class VaultTest {}");
        Files.writeString(integration, "class VaultIT {}");
        GenerationTarget target = GenerationTarget.extending("demo.Vault", List.of(
                new GenerationTarget.Seed("demo.VaultTest", project.resolve("src/test/java").toFile()),
                new GenerationTarget.Seed("demo.VaultIT", project.resolve("src/it/java").toFile())));
        File output = project.resolve("state/generated/run").toFile();

        File sources = TestGenerationJob.seedSourceDir(target, output);

        assertEquals(TestGenerationJob.seedCopy(output), sources);
        assertEquals("class VaultTest {}", Files.readString(sources.toPath().resolve("demo/VaultTest.java")));
        assertEquals("class VaultIT {}", Files.readString(sources.toPath().resolve("demo/VaultIT.java")));
    }
}
