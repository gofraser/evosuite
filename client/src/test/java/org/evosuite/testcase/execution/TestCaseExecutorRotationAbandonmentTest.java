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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each initializer gets a fresh JVM because failed class initialization cannot be retried. */
public class TestCaseExecutorRotationAbandonmentTest {

    @TempDir
    Path scratch;

    @Test
    public void rotationLeavesAbandonedInitializerUninterrupted() throws Exception {
        runFork("initializer");
    }

    @Test
    public void ordinaryStalledWorkerStillGetsInterruptingShutdown() throws Exception {
        runFork("ordinary");
    }

    /** The streams the abandoned worker muted are handed back by the executor, not left muted. */
    @Test
    public void giveUpOnStalledWorkerHandsTheStreamsBack() throws Exception {
        runFork("streams");
    }

    private void runFork(String scenario) throws Exception {
        Path output = scratch.resolve(scenario + ".log");
        String java = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        Process child = new ProcessBuilder(java, "-ea", "-Djava.awt.headless=true", "-cp",
                System.getProperty("java.class.path"),
                ExecutorRotationAbandonmentFixture.class.getName(), scenario)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS),
                    "executor rotation fixture did not finish; log: " + output);
            String log = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), log);
            assertTrue(log.contains("rotation scenario passed: " + scenario), log);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
