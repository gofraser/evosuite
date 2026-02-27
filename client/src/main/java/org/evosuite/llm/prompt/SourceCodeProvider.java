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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Locates source code for the CUT so it can be included in prompt context.
 */
public class SourceCodeProvider {

    private static final Logger logger = LoggerFactory.getLogger(SourceCodeProvider.class);
    private static final long MAX_SOURCE_FILE_BYTES = 524_288L;

    private final Path projectRoot;

    public SourceCodeProvider() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public SourceCodeProvider(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** Returns the source code for the given class name, or empty if unavailable. */
    public Optional<String> getSourceCode(String className) {
        Path configured = configuredSourcePath();
        if (configured != null) {
            return readFile(configured);
        }

        Path inferred = inferSourcePath(className);
        if (inferred != null) {
            return readFile(inferred);
        }
        return Optional.empty();
    }

    private Path configuredSourcePath() {
        String configuredPath = Properties.LLM_SOURCE_PATH == null ? "" : Properties.LLM_SOURCE_PATH.trim();
        if (configuredPath.isEmpty()) {
            return null;
        }
        return Paths.get(configuredPath);
    }

    private Path inferSourcePath(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        String relative = className.replace('.', '/') + ".java";
        Path candidate = projectRoot.resolve("src/main/java").resolve(relative);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private Optional<String> readFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            long fileSize = Files.size(path);
            if (fileSize > MAX_SOURCE_FILE_BYTES) {
                logger.warn("Skipping source file {} because size {} exceeds {}",
                        path, fileSize, MAX_SOURCE_FILE_BYTES);
                return Optional.empty();
            }
            return Optional.of(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }
}
