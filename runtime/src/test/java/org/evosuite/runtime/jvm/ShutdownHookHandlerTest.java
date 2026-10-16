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
package org.evosuite.runtime.jvm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class ShutdownHookHandlerTest {

    @BeforeEach
    public void init() {
        ShutdownHookHandler.getInstance().initHandler();
    }

    @AfterEach
    public void tearDown() {
        //be sure no hook is left
        ShutdownHookHandler.getInstance().processWasHalted();
    }

    @Test
    public void testAddHook() {

        int n = ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks();

        Runtime.getRuntime().addShutdownHook(new Thread() {
        });

        Assertions.assertEquals(n + 1, ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks());
    }

    @Test
    public void testDoubleInit() {
        int n = ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks();

        Runtime.getRuntime().addShutdownHook(new Thread() {
        });

        //this should remove the above hook thread
        ShutdownHookHandler.getInstance().initHandler();

        Assertions.assertEquals(n, ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks());
    }

    @Test
    public void testNormalExecution() {

        final int[] array = new int[1];
        final int value = 42;

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                array[0] = value;
            }
        });

        //value not modified yet
        Assertions.assertNotEquals(value, array[0]);

        ShutdownHookHandler.getInstance().executeAddedHooks();

        //hook should had modified the value by now
        Assertions.assertEquals(value, array[0]);
    }

    @Test
    public void testExecutionWithException() {
        int n = ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                throw new IllegalStateException();
            }
        });

        try {
            ShutdownHookHandler.getInstance().executeAddedHooks();
            Assertions.fail();
        } catch (IllegalStateException e) {
            //expected
        }

        //even if failed, hook should had been removed
        Assertions.assertEquals(n, ShutdownHookHandler.getInstance().getNumberOfAllExistingHooks());
    }

    @Test
    @SuppressWarnings("removal")
    public void testSafeExecutionWithSandboxOnButNoTestCaseExecuting() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        final int[] array = new int[1];
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                array[0] = 42;
            }
        });

        Sandbox.initializeSecurityManagerForSUT();
        try {
            // The sandbox is on, but nobody has called goingToExecuteSUTCode():
            // this is the state an abandoned JUnit check runner leaves behind.
            Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());

            int n = ShutdownHookHandler.getInstance().safeExecuteAddedHooks();

            Assertions.assertEquals(1, n);
            Assertions.assertEquals(42, array[0]);
        } finally {
            Sandbox.resetDefaultSecurityManager();
        }
    }

    @Test
    @SuppressWarnings("removal")
    public void testIdleSandboxIsExpectedOnlyOnAWorkerThatWasGivenUp() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        // Sandbox off: nothing to report either way.
        Assertions.assertFalse(ShutdownHookHandler.isUnexpectedIdleSandbox(() -> false));

        Sandbox.initializeSecurityManagerForSUT();
        try {
            // Sandbox on, no test case marked as executing: this is what a test
            // thread sees after the thread waiting for it timed out and cleared
            // the flag.
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
            Assertions.assertTrue(ShutdownHookHandler.isUnexpectedIdleSandbox(() -> false));
            Assertions.assertFalse(ShutdownHookHandler.isUnexpectedIdleSandbox(() -> true));

            // While a test case is marked as executing the state is always fine.
            Sandbox.goingToExecuteSUTCode();
            try {
                Assertions.assertFalse(ShutdownHookHandler.isUnexpectedIdleSandbox(() -> false));
            } finally {
                Sandbox.doneWithExecutingSUTCode();
            }
        } finally {
            Sandbox.resetDefaultSecurityManager();
        }
    }
}
