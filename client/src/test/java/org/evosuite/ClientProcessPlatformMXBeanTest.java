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
package org.evosuite;

import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The sandbox refuses an unprivileged thread the first {@link ManagementFactory} call, and
 * {@code ManagementFactory} then fails for the rest of the JVM. The Mockito warm-up thread can
 * make that first call after the search has installed the sandbox. Each scenario runs in a fresh
 * JVM, because {@code ManagementFactory} is already initialized in this one.
 */
class ClientProcessPlatformMXBeanTest {

    private static final String AVAILABLE = "thread MXBean available";
    private static final String UNSUPPORTED = "security manager unsupported";

    @TempDir
    Path scratch;

    @Test
    void anUnprivilegedFirstCallUnderTheSandboxBreaksManagementFactory() throws Exception {
        String log = runFixture("uninitialized");
        assumeFalse(log.contains(UNSUPPORTED), "this JVM cannot install a security manager");
        assertTrue(log.contains("thread MXBean unavailable"), log);
    }

    @Test
    void initializingThePlatformMXBeansFirstKeepsThemAvailable() throws Exception {
        String log = runFixture("initialized");
        assumeFalse(log.contains(UNSUPPORTED), "this JVM cannot install a security manager");
        assertTrue(log.contains(AVAILABLE), log);
    }

    private String runFixture(String scenario) throws Exception {
        Path output = scratch.resolve(scenario + ".log");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process child = new ProcessBuilder(java, "-Djava.security.manager=allow", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), scenario)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "fixture did not finish; log: " + output);
            String log = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), log);
            return log;
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Runs in the child JVM. */
    public static final class Fixture {

        private Fixture() {
        }

        public static void main(String[] args) throws Exception {
            if (!Sandbox.isSecurityManagerSupported()) {
                System.out.println(UNSUPPORTED);
                return;
            }
            if ("initialized".equals(args[0])) {
                ClientProcess.initializePlatformMXBeans();
            }
            Sandbox.initializeSecurityManagerForSUT();
            try {
                Thread warmUp = new Thread(() -> {
                    try {
                        ManagementFactory.getRuntimeMXBean();
                    } catch (Throwable refused) {
                        // The sandbox refuses this thread the native library.
                    }
                }, "evosuite-mockito-warmup");
                warmUp.start();
                warmUp.join();
                ManagementFactory.getThreadMXBean().getThreadCount();
                System.out.println(AVAILABLE);
            } catch (Throwable t) {
                System.out.println("thread MXBean unavailable: " + t);
            } finally {
                Sandbox.resetDefaultSecurityManager();
            }
        }
    }
}
