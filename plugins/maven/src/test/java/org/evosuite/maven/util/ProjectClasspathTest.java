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
package org.evosuite.maven.util;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProjectClasspathTest {

    @Test
    public void targetsOnlyTheOwnExistingClassFolders(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("module").resolve("target").resolve("classes"));
        Path other = Files.createDirectories(tmp.resolve("other").resolve("target").resolve("classes"));
        Path jar = Files.createFile(tmp.resolve("lib.jar"));
        Path missing = tmp.resolve("module").resolve("target").resolve("missing");

        ProjectClasspath classpath = ProjectClasspath.of(
                project(classes, list(classes, other, jar, missing), list(classes, jar)), new SystemStreamLog());

        assertEquals(classes.toString(), classpath.getTarget());
        assertEquals(classes + File.pathSeparator + jar, classpath.getClasspath());
    }

    @Test
    public void classpathLeavesOutPowerMockAndJMockitAndDuplicates(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path junit = Files.createFile(tmp.resolve("junit.jar"));
        Path powermock = Files.createFile(tmp.resolve("powermock-core.jar"));
        Path jmockit = Files.createFile(tmp.resolve("JMockit.jar"));

        ProjectClasspath classpath = ProjectClasspath.of(
                project(classes, list(classes), list(classes, junit, powermock, jmockit, junit, classes)),
                new SystemStreamLog());

        assertEquals(classes + File.pathSeparator + junit, classpath.getClasspath());
    }

    @Test
    public void nothingToRunOnWithoutClassFolders(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");

        ProjectClasspath classpath = ProjectClasspath.of(
                project(classes, list(classes), list(classes)), new SystemStreamLog());

        assertNull(classpath.getTarget());
        assertNull(classpath.getClasspath());
        assertTrue(classpath.isEmpty());
    }

    @Test
    public void classpathFileHoldsTheClasspath(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));

        ProjectClasspath classpath = ProjectClasspath.of(
                project(classes, list(classes), list(classes)), new SystemStreamLog());

        List<String> lines = Files.readAllLines(classpath.writeClasspathToFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(java.util.Collections.singletonList(classes.toString()), lines);
    }

    private static List<String> list(Path... elements) {
        String[] paths = new String[elements.length];
        for (int i = 0; i < elements.length; i++) {
            paths[i] = elements[i].toString();
        }
        return Arrays.asList(paths);
    }

    private static MavenProject project(Path output, List<String> compile, List<String> test) {
        MavenProject project = new MavenProject() {
            @Override
            public List<String> getCompileClasspathElements() {
                return compile;
            }

            @Override
            public List<String> getTestClasspathElements() {
                return test;
            }
        };
        Build build = new Build();
        build.setOutputDirectory(output.toString());
        project.setBuild(build);
        return project;
    }
}
