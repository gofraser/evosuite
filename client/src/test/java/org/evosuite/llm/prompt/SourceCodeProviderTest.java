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
