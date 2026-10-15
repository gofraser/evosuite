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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EvoSuiteRunnerTest {

    @Test
    public void outputReaderStopsWhenProcessEnds() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process process = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();

        List<String> lines = new ArrayList<>();
        Thread reader = EvoSuiteRunner.startOutputReader(process, new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                lines.add(content.toString());
            }
        });

        assertEquals(0, process.waitFor());
        reader.join(5_000);

        assertFalse(reader.isAlive(), "reader thread kept running after the process ended");
        assertFalse(lines.isEmpty(), "process output was not forwarded");
    }

    @Test
    public void shadedMasterJarComesFirstAndReplacesTheJarsItContains() {
        List<Artifact> artifacts = Arrays.asList(
                artifact("org.evosuite", "evosuite-runtime"),
                artifact("org.evosuite", "evosuite-client"),
                artifact("commons-io", "commons-io"),
                artifact("org.evosuite", "evosuite-master"));

        String[] cp = EvoSuiteRunner.getEvoSuiteClasspath(artifacts).split(File.pathSeparator);

        assertEquals(2, cp.length);
        assertTrue(cp[0].endsWith("evosuite-master.jar"), cp[0]);
        assertTrue(cp[1].endsWith("commons-io.jar"), cp[1]);
    }

    @Test
    public void moduleFlagsFollowTheToolchainJvmVersion(@TempDir Path tmp) throws Exception {
        List<Artifact> artifacts = Arrays.asList(artifact("org.evosuite", "evosuite-master"));

        String java8 = fakeJava(tmp, "java8", "1.8");
        List<String> on8 = new EvoSuiteRunner(new SystemStreamLog(), artifacts, java8).getCommandToRunEvoSuite();
        assertEquals(java8, on8.get(0));
        assertFalse(on8.contains("--add-opens"), "Java 8 does not start with module flags: " + on8);

        String java17 = fakeJava(tmp, "java17", "17");
        List<String> on17 = new EvoSuiteRunner(new SystemStreamLog(), artifacts, java17).getCommandToRunEvoSuite();
        assertTrue(on17.contains("--add-opens"), on17.toString());
    }

    /**
     * A stand-in for a toolchain's java executable that only answers the version question.
     */
    private static String fakeJava(Path tmp, String name, String specificationVersion) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path bin = Files.createDirectories(tmp.resolve(name).resolve("bin"));
        Path java = bin.resolve("java");
        Files.write(java, Arrays.asList("#!/bin/sh",
                "echo 'Property settings:'",
                "echo '    java.specification.version = " + specificationVersion + "'"));
        assertTrue(java.toFile().setExecutable(true));
        return java.toString();
    }

    private static Artifact artifact(String groupId, String artifactId) {
        Artifact artifact = new DefaultArtifact(groupId, artifactId, "1.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(new File("/repo/" + artifactId + ".jar"));
        return artifact;
    }
}
