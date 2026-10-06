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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License along
 * with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures the JUnit4 generated-test-shaped boundary is executed by Surefire. */
public class EvoSelectorInstrumentedForkTest {

    @Test
    public void instrumentedBoundaryRunsInFreshJvm() throws Exception {
        Path agentJar = createAgentJar();
        Path output = Files.createTempFile("evosuite-selector-fork", ".log");
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-javaagent:" + agentJar);
            command.add("-Djdk.attach.allowAttachSelf=true");
            command.add("-Xshare:off");
            if (!System.getProperty("java.specification.version").startsWith("1.8")) {
                command.add("--add-opens");
                command.add("java.base/java.util=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.lang=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.net=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.text=ALL-UNNAMED");
            }
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("org.junit.runner.JUnitCore");
            command.add(EvoSelectorInstrumentedTest.class.getName());

            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            assertTrue(process.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS),
                    "selector boundary child did not finish");
            String log = new String(Files.readAllBytes(output), "UTF-8");
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("OK (6 tests)"), log);
            System.out.println("Instrumented selector child:\n" + log);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);
            }
            Files.deleteIfExists(agentJar);
            Files.deleteIfExists(output);
        }
    }

    private static Path createAgentJar() throws IOException {
        Path classes = Paths.get(InstrumentingAgent.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath());
        Path agentJar = Files.createTempFile("evosuite-selector-agent", ".jar");
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Premain-Class", InstrumentingAgent.class.getName());
        attributes.putValue("Agent-Class", InstrumentingAgent.class.getName());
        attributes.putValue("Can-Redefine-Classes", "true");
        attributes.putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(agentJar), manifest);
             Stream<Path> paths = Files.walk(classes)) {
            for (java.util.Iterator<Path> iterator = paths.iterator(); iterator.hasNext();) {
                Path file = iterator.next();
                if (Files.isDirectory(file)) {
                    continue;
                }
                String entryName = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if ("META-INF/MANIFEST.MF".equals(entryName)) {
                    continue;
                }
                jar.putNextEntry(new JarEntry(entryName));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        return agentJar;
    }
}
