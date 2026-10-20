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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A thread the SUT left behind keeps running while the JUnit check runs the suite, and once the
 * generated test has turned the mock framework off it reaches the real {@code System.exit}
 * (fixsuite's LibrarySuite does, from the thread its constructor starts on the event dispatch
 * thread). The check takes the client's sandbox down for the run, and the suite's own sandbox
 * only covers the time between its class set-up and clean-up, and makes every thread alive at
 * set-up privileged. No thread may be allowed to exit the JVM at any point of the check, or the
 * client dies and the suite it generated is lost.
 */
public class JUnitAnalyzerExitGuardTest {

    private Properties.OutputFormat savedFormat;

    @BeforeEach
    public void setUp() {
        savedFormat = Properties.TEST_FORMAT;
    }

    @AfterEach
    public void tearDown() {
        Properties.TEST_FORMAT = savedFormat;
    }

    /** JUnit 4 flavour, with the class level sandbox handling of the generated scaffolding. */
    public static class SandboxedSuite4 {
        @org.junit.BeforeClass
        public static void initEvoSuiteFramework() {
            Sandbox.initializeSecurityManagerForSUT();
        }

        @org.junit.AfterClass
        public static void clearEvoSuiteFramework() {
            Sandbox.resetDefaultSecurityManager();
        }

        @org.junit.Test
        public void test0() {
        }
    }

    /** JUnit 5 flavour, with the class level sandbox handling of the EvoSuite extension. */
    public static class SandboxedSuite5 {
        @org.junit.jupiter.api.BeforeAll
        static void initEvoSuiteFramework() {
            Sandbox.initializeSecurityManagerForSUT();
        }

        @org.junit.jupiter.api.AfterAll
        static void clearEvoSuiteFramework() {
            Sandbox.resetDefaultSecurityManager();
        }

        @org.junit.jupiter.api.Test
        void test0() {
        }
    }

    private static void assertNoThreadCanExitDuringTheCheck(Class<?> suite) throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported(),
                "Security Manager is not supported in this JVM (Java 24+)");
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Method run = JUnitCheckRunner.class.getDeclaredMethod("run", Class[].class);
        run.setAccessible(true);

        // the client's sandbox, as during the whole generation
        Sandbox.initializeSecurityManagerForSUT();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> unguarded = new AtomicReference<>();
        // A leftover SUT thread: it exists before the check, so it is not one of the client's
        // privileged threads, but the suite's sandbox makes it privileged. It asks what the JVM
        // asks in System.exit and Runtime.halt, without exiting if the answer is yes.
        Thread leftover = new Thread(() -> {
            while (!stop.get()) {
                SecurityManager sm = System.getSecurityManager();
                if (sm == null) {
                    unguarded.compareAndSet(null, "no security manager installed");
                } else {
                    try {
                        sm.checkExit(1);
                        unguarded.compareAndSet(null, "exitVM granted by " + sm.getClass().getName());
                    } catch (SecurityException expected) {
                        // refused, as it has to be
                    }
                }
                Thread.onSpinWait();
            }
        }, "leftover-sut-thread");
        leftover.setDaemon(true);
        try {
            leftover.start();
            JUnitResult result = (JUnitResult) run.invoke(null, (Object) new Class<?>[]{suite});
            assertTrue(result.wasSuccessful());
        } finally {
            stop.set(true);
            leftover.join(10_000L);
            if (Sandbox.isSecurityManagerInitialized()) {
                Sandbox.resetDefaultSecurityManager();
            }
        }
        assertNull(System.getSecurityManager(), "the check left a security manager behind");
        assertNull(unguarded.get(), "a leftover thread could have exited the JVM during the JUnit check");
    }

    @Test
    public void junit4CheckLetsNoThreadExitTheJvm() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        assertNoThreadCanExitDuringTheCheck(SandboxedSuite4.class);
    }

    @Test
    public void junit5CheckLetsNoThreadExitTheJvm() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        assertNoThreadCanExitDuringTheCheck(SandboxedSuite5.class);
    }
}
