/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
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
