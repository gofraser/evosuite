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
package org.evosuite.runtime.classhandling;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A class that the JVM loads while verifying the test class, before EvoRunner has activated the
 * agent, used to stay uninstrumented, so its file access reached the real file system and was
 * rejected by the sandbox. This runs a generated-style JUnit 4 test through JUnitCore in a fresh
 * JVM, the order of events a build tool produces, and expects it to pass.
 */
public class LoadedBeforeAgentTest {

    @Test
    public void classLoadedWhileVerifyingTheTestClassIsStillMocked() throws Exception {
        // The sandbox needs a SecurityManager, which JDK 24 no longer has.
        Assumptions.assumeTrue(Runtime.version().feature() < 24);

        File workDir = Files.createTempDirectory("loaded-before-agent").toFile();
        File agentJar = new File(workDir, "evosuite-agent-for-test.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Agent-Class", InstrumentingAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        new JarOutputStream(Files.newOutputStream(agentJar.toPath()), manifest).close();

        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Djdk.attach.allowAttachSelf=true");
        command.add("-Djava.security.manager=allow");
        command.add("-cp");
        command.add(agentJar.getPath() + File.pathSeparator + System.getProperty("java.class.path"));
        command.add("org.junit.runner.JUnitCore");
        command.add("latefixture.LateFixture");

        Process process = new ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        Assertions.assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child JVM timed out");

        Assertions.assertEquals(0, process.exitValue(), output.toString());
        Assertions.assertTrue(output.toString().contains("OK (2 tests)"), output.toString());
        Assertions.assertFalse(new File(workDir, "late_fixture_output.txt").exists(),
                "the file was written to the real file system");
    }
}
