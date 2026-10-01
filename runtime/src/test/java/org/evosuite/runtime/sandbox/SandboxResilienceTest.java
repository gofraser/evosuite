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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

@SuppressWarnings("removal")
public class SandboxResilienceTest {

    @AfterEach
    public void cleanupSandboxState() throws Exception {
        setCounter(0);
        setManager(null);
        try {
            System.setSecurityManager(null);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // If a foreign manager denies changes or SecurityManager is not supported
            // (Java 24+), tests should still leave Sandbox static state clean.
        }
    }

    @Test
    public void resetShouldRetainInternalStateWhenRestoreIsDenied() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        setManager(new ThrowingRestoreManager());
        setCounter(1);

        Assertions.assertDoesNotThrow(Sandbox::resetDefaultSecurityManager);
        Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
        Assertions.assertEquals(1, getCounter());
    }

    @Test
    public void deniedResetKeepsInstalledManagerAndPrivilegedThreadsUsable() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        SecurityManager originalManager = System.getSecurityManager();
        MSecurityManager manager = null;
        boolean downgraded = false;
        try {
            Sandbox.initializeSecurityManagerForSUT();
            manager = getManager();
            Thread currentThread = Thread.currentThread();
            manager.goingToExecuteUnsafeCodeOnSameThread();
            downgraded = true;

            Assertions.assertDoesNotThrow(Sandbox::resetDefaultSecurityManager);

            Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
            Assertions.assertSame(manager, System.getSecurityManager());
            Assertions.assertDoesNotThrow(() -> Sandbox.addPrivilegedThread(currentThread));
            Thread additionalThread = new Thread(() -> { }, "sandbox-regression-privileged");
            Assertions.assertDoesNotThrow(() -> Sandbox.addPrivilegedThread(additionalThread));
            Assertions.assertTrue(manager.getPrivilegedThreads().contains(additionalThread));

            manager.doneWithExecutingUnsafeCodeOnSameThread();
            downgraded = false;
            Sandbox.resetDefaultSecurityManager();

            Assertions.assertFalse(Sandbox.isSecurityManagerInitialized());
            Assertions.assertSame(originalManager, System.getSecurityManager());
        } finally {
            if (downgraded && manager != null) {
                manager.doneWithExecutingUnsafeCodeOnSameThread();
            }
            if (Sandbox.isSecurityManagerInitialized()) {
                Sandbox.resetDefaultSecurityManager();
            }
            if (System.getSecurityManager() != originalManager) {
                System.setSecurityManager(originalManager);
            }
        }
    }

    @Test
    public void initializeShouldRecoverFromDesynchronizedState() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        setManager(new MSecurityManager());
        setCounter(1);

        Sandbox.initializeSecurityManagerForSUT();

        Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
        Assertions.assertNotNull(System.getSecurityManager());
        Assertions.assertSame(getManager(), System.getSecurityManager());

        Sandbox.resetDefaultSecurityManager();
        Assertions.assertFalse(Sandbox.isSecurityManagerInitialized());
    }

    @Test
    public void privilegedThreadShouldBeSandboxedDuringSutExecution() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        Sandbox.initializeSecurityManagerForSUT();
        try {
            Sandbox.goingToExecuteSUTCode();
            Assertions.assertThrows(SecurityException.class,
                    () -> System.setSecurityManager(null),
                    "SUT execution on privileged thread must still deny setSecurityManager");
        } finally {
            Sandbox.doneWithExecutingSUTCode();
            Sandbox.resetDefaultSecurityManager();
        }
    }

    private static MSecurityManager getManager() throws Exception {
        Field field = Sandbox.class.getDeclaredField("manager");
        field.setAccessible(true);
        return (MSecurityManager) field.get(null);
    }

    private static void setManager(MSecurityManager value) throws Exception {
        Field field = Sandbox.class.getDeclaredField("manager");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static int getCounter() throws Exception {
        Field field = Sandbox.class.getDeclaredField("counter");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setCounter(int value) throws Exception {
        Field field = Sandbox.class.getDeclaredField("counter");
        field.setAccessible(true);
        field.setInt(null, value);
    }

    private static class ThrowingRestoreManager extends MSecurityManager {
        @Override
        public void restoreDefaultManager() throws SecurityException {
            throw new SecurityException("Permission Denied");
        }
    }
}
