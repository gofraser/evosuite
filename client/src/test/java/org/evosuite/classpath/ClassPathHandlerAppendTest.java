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
package org.evosuite.classpath;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassPathHandlerAppendTest {

    @TempDir
    Path dir;

    private final String originalCp = Properties.CP;

    @AfterEach
    void reset() {
        Properties.CP = originalCp;
        ClassPathHandler.resetSingleton();
    }

    @Test
    void entryThatIsOnlyAPrefixOfAnotherIsStillAdded() throws IOException {
        File longer = Files.createDirectory(dir.resolve("classes-long")).toFile();
        File shorter = Files.createDirectory(dir.resolve("classes")).toFile();
        ClassPathHandler handler = ClassPathHandler.getInstance();
        handler.changeTargetClassPath(new String[]{longer.getPath()});

        handler.addElementToTargetProjectClassPath(shorter.getPath());

        assertEquals(longer.getPath() + File.pathSeparator + shorter.getPath(),
                handler.getTargetProjectClasspath());
    }

    @Test
    void aliasOfAnExistingEntryIsNotAddedAgain() throws IOException {
        File classes = Files.createDirectory(dir.resolve("classes")).toFile();
        File other = Files.createDirectory(dir.resolve("other")).toFile();
        ClassPathHandler handler = ClassPathHandler.getInstance();
        handler.changeTargetClassPath(new String[]{classes.getPath(), other.getPath()});
        String before = handler.getTargetProjectClasspath();

        handler.addElementToTargetProjectClassPath(
                new File(other.getPath(), ".." + File.separator + "classes").getPath());

        assertEquals(before, handler.getTargetProjectClasspath());
    }

    @Test
    void firstEntryOnEmptyClassPathIsReflectedInProperties() throws IOException {
        File classes = Files.createDirectory(dir.resolve("classes")).toFile();
        File other = Files.createDirectory(dir.resolve("other")).toFile();
        Properties.CP = "";
        ClassPathHandler handler = ClassPathHandler.getInstance();

        handler.addElementToTargetProjectClassPath(classes.getPath());
        assertEquals(classes.getPath(), Properties.CP);

        handler.addElementToTargetProjectClassPath(other.getPath());
        assertEquals(classes.getPath() + File.pathSeparator + other.getPath(), Properties.CP);
    }
}
