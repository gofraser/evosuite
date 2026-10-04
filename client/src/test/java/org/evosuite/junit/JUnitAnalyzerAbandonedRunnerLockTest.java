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

import org.evosuite.runtime.sandbox.MSecurityManager;
import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JUnit check can be left while its runner is still inside the sandbox through more than
 * the grace-window abandonment: an interrupt or an Error (OutOfMemoryError under heap pressure)
 * escaping the wait, or a runner that dies without reaching its own release. In every case the
 * runner's sandbox lock must be handed back, or the next test execution stalls for the whole
 * acquisition timeout.
 */
public class JUnitAnalyzerAbandonedRunnerLockTest {

    static final CountDownLatch INSIDE_SANDBOX = new CountDownLatch(1);
    static final CountDownLatch LET_GO = new CountDownLatch(1);

    /** Generated suites enter the sandbox in a @BeforeClass and leave it in the @AfterClass. */
    public static class HoldsTheSandbox {
        @org.junit.Test
        public void entersTheSandboxAndStays() throws Exception {
            Sandbox.initializeSecurityManagerForSUT();
            Sandbox.goingToExecuteSUTCode();
            INSIDE_SANDBOX.countDown();
            // wedged: ignores the interrupt the way a runner stuck under heap pressure does
            while (LET_GO.getCount() > 0) {
                try {
                    LET_GO.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // keep going
                }
            }
        }
    }

    @Test
    public void lockOfRunnerLeftBehindByInterruptedCheckIsReclaimed() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported(),
                "Security Manager is not supported in this JVM (Java 24+)");
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        long previousTimeout = MSecurityManager.getSandboxLockAcquireTimeoutMs();
        Method run = JUnitAnalyzer.class.getDeclaredMethod("runJUnitOnCurrentProcess", Class[].class);
        run.setAccessible(true);

        Sandbox.initializeSecurityManagerForSUT();
        Thread master = new Thread(() -> {
            try {
                run.invoke(null, (Object) new Class<?>[]{HoldsTheSandbox.class});
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }, "check-master");
        Sandbox.addPrivilegedThread(master);
        try {
            master.start();
            assertTrue(INSIDE_SANDBOX.await(60, TimeUnit.SECONDS), "the runner never entered the sandbox");
            // the check is abandoned by its master while the runner is still inside the sandbox
            master.interrupt();
            master.join(60_000);
            assertTrue(!master.isAlive(), "the check did not give up on being interrupted");

            // The next test execution must get the sandbox promptly, not after the lock timeout.
            MSecurityManager.setSandboxLockAcquireTimeoutMs(3_000);
            AtomicLong waitedMs = new AtomicLong(-1);
            Thread next = new Thread(() -> {
                long start = System.nanoTime();
                try {
                    Sandbox.goingToExecuteSUTCode();
                    waitedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                } catch (RuntimeException e) {
                    waitedMs.set(Long.MAX_VALUE);
                } finally {
                    Sandbox.doneWithExecutingSUTCode();
                }
            }, "next-test-execution");
            Sandbox.addPrivilegedThread(next);
            next.start();
            next.join(30_000);
            assertTrue(waitedMs.get() >= 0 && waitedMs.get() < 1_500,
                    "the next acquisition waited " + waitedMs.get() + " ms for the abandoned runner's lock");
        } finally {
            LET_GO.countDown();
            MSecurityManager.setSandboxLockAcquireTimeoutMs(previousTimeout);
            while (Sandbox.isSecurityManagerInitialized()) {
                Sandbox.resetDefaultSecurityManager();
            }
        }
    }
}
