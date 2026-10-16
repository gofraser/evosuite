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

import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A suite whose {@code @After} throws before {@code Sandbox.doneWithExecutingSUTCode()} leaves
 * the JUnit check runner inside the sandbox. The check has to hand that state back itself, or
 * the {@code @AfterClass} security manager restore is denied and the dead runner's lock stalls
 * every later test execution.
 */
public class JUnitAnalyzerSandboxLeakTest {

    @Test
    public void runnerLeftInsideSandboxIsReleasedAfterTheCheck() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported(),
                "Security Manager is not supported in this JVM (Java 24+)");
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Method restore = JUnitAnalyzer.class.getDeclaredMethod("restoreSandboxStateAfterJUnitRun",
                boolean.class, Set.class);
        restore.setAccessible(true);

        Sandbox.initializeSecurityManagerForSUT();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean stillInitialized;
        try {
            Thread runner = new Thread(() -> {
                try {
                    // the suite's @Before, whose matching @After never finished
                    Sandbox.goingToExecuteSUTCode();
                    restore.invoke(null, true, null);
                    // the check's way out; denied while the thread is still downgraded
                    Sandbox.resetDefaultSecurityManager();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "evosuite-junit-check-runner");
            Sandbox.addPrivilegedThread(runner);
            runner.start();
            runner.join();
        } finally {
            stillInitialized = Sandbox.isSecurityManagerInitialized();
            if (stillInitialized) {
                Sandbox.resetDefaultSecurityManager();
            }
        }
        assertNull(failure.get());
        assertFalse(stillInitialized,
                "the leaked runner kept the sandbox downgraded, so the manager could not be restored");
    }
}
