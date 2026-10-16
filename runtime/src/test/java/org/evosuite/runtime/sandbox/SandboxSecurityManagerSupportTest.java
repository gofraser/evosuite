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
package org.evosuite.runtime.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Whether the sandbox can install its security manager, decided from the Java version and the
 * {@code java.security.manager} property instead of by calling {@code setSecurityManager}, which
 * makes Java 17 print a deprecation warning attributed to {@link Sandbox}.
 */
public class SandboxSecurityManagerSupportTest {

    private static boolean supported(String version, String property, boolean installed) {
        return Sandbox.securityManagerUnavailable(version, property, installed) == null;
    }

    @Test
    public void parsesTheFeatureVersion() {
        assertEquals(8, Sandbox.featureVersion("1.8"));
        assertEquals(11, Sandbox.featureVersion("11"));
        assertEquals(17, Sandbox.featureVersion(" 17 "));
        assertEquals(0, Sandbox.featureVersion("unknown"));
        assertEquals(0, Sandbox.featureVersion(null));
    }

    @Test
    public void upToJava17ASecurityManagerIsAllowedUnlessDisallowed() {
        assertTrue(supported("1.8", null, false));
        assertTrue(supported("11", null, false));
        assertTrue(supported("17", null, false));
        assertTrue(supported("17", "allow", false));
        assertEquals("the JVM was started with -Djava.security.manager=disallow.",
                Sandbox.securityManagerUnavailable("17", "disallow", false));
    }

    @Test
    public void fromJava18ItNeedsAllow() {
        assertEquals("Java 21 allows a security manager only when started with -Djava.security.manager=allow.",
                Sandbox.securityManagerUnavailable("21", null, false));
        assertTrue(supported("18", "allow", false));
        assertTrue(supported("23", "allow", false));
        // Started with a security manager (-Djava.security.manager or =default): it may be replaced.
        assertTrue(supported("21", "default", true));
    }

    @Test
    public void fromJava24ItIsGone() {
        assertEquals("Java 24 no longer supports a security manager.",
                Sandbox.securityManagerUnavailable("24", "allow", false));
        assertEquals("Java 27 no longer supports a security manager.",
                Sandbox.securityManagerUnavailable("27", null, false));
    }

    @Test
    public void anUnknownVersionFallsBackToAskingTheJvm() {
        assertEquals(Sandbox.UNKNOWN_VERSION, Sandbox.securityManagerUnavailable("x", null, false));
    }

    /**
     * On the JVM running this test, the decision agrees with what the JVM allows. Asking needs a
     * real manager: a JVM that disallows security managers still accepts setSecurityManager(null),
     * which is how the old check, which passed the current (null) manager, came to the wrong answer.
     */
    @Test
    public void agreesWithTheRunningJvm() {
        boolean jvmAccepts;
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(Permission perm) {
                    // allow everything, so that it can be removed again
                }
            });
            System.setSecurityManager(null);
            jvmAccepts = true;
        } catch (UnsupportedOperationException e) {
            jvmAccepts = false;
        }
        String reason = Sandbox.securityManagerUnavailable(System.getProperty("java.specification.version"),
                System.getProperty("java.security.manager"), false);
        assertEquals(jvmAccepts, reason == null, String.valueOf(reason));
        assertEquals(jvmAccepts, Sandbox.isSecurityManagerSupported());
        if (jvmAccepts) {
            assertNull(reason);
        }
    }

    /**
     * Java 18 to 23 disallow security managers by default, as -Djava.security.manager=disallow
     * does on earlier versions. The sandbox then stays off instead of failing to install: the old
     * check called it supported, and installing it threw UnsupportedOperationException before
     * any generated test ran.
     */
    @Test
    public void staysOffWhereSecurityManagersAreDisallowed() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 12, "disallow exists from Java 12");
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Djava.security.manager=disallow");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(InstallSandbox.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child JVM timed out");

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("supported=false installed=false"), output);
    }

    /** Installs the sandbox the way the JUnit extension does, in a JVM of its own. */
    public static final class InstallSandbox {
        public static void main(String[] args) {
            Sandbox.initializeSecurityManagerForSUT();
            System.out.println("supported=" + Sandbox.isSecurityManagerSupported()
                    + " installed=" + (System.getSecurityManager() != null));
            Sandbox.resetDefaultSecurityManager();
        }
    }
}
