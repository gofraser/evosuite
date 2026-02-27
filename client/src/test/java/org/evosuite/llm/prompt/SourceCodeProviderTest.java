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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SourceCodeProviderTest {

    @TempDir
    Path tempDir;

    private final String originalSourcePath = Properties.LLM_SOURCE_PATH;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_SOURCE_PATH = originalSourcePath;
    }

    @Test
    void returnsEmptyForOversizedConfiguredSourceFile() throws Exception {
        Path sourceFile = tempDir.resolve("LargeSource.java");
        byte[] largeContent = new byte[524_289];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = 'a';
        }
        Files.write(sourceFile, largeContent);

        Properties.LLM_SOURCE_PATH = sourceFile.toString();
        SourceCodeProvider provider = new SourceCodeProvider(tempDir);

        Optional<String> result = provider.getSourceCode("com.example.Foo");
        assertFalse(result.isPresent());
    }

    @Test
    void readsConfiguredSourceFileWhenWithinLimit() throws Exception {
        Path sourceFile = tempDir.resolve("SmallSource.java");
        String source = "class SmallSource {}";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        Properties.LLM_SOURCE_PATH = sourceFile.toString();
        SourceCodeProvider provider = new SourceCodeProvider(tempDir);

        Optional<String> result = provider.getSourceCode("com.example.Foo");
        assertTrue(result.isPresent());
        assertEquals(source, result.get());
    }
}
