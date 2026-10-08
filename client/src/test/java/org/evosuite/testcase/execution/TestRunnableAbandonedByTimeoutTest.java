/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.execution;

import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A worker that the executor gave up on after a timeout goes on running its
 * test, but must not be able to change the process-global sandbox state while
 * the next test executes on another worker.
 */
public class TestRunnableAbandonedByTimeoutTest {

    private static class BlockingObserver extends ExecutionObserver {
        final CountDownLatch inObserver = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean afterCalled = new AtomicBoolean();

        @Override
        public void output(int position, String output) {
        }

        @Override
        public void beforeStatement(Statement statement, Scope scope) {
            inObserver.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // what an observer's Inspector call does once the run was given up on
            Sandbox.goingToExecuteSUTCode();
            Sandbox.doneWithExecutingSUTCode();
        }

        @Override
        public void afterStatement(Statement statement, Scope scope, Throwable exception) {
            afterCalled.set(true);
        }

        @Override
        public void testExecutionFinished(ExecutionResult r, Scope s) {
        }

        @Override
        public void clear() {
        }
    }

    @Test
    public void workerGivenUpOnLeavesTheSandboxToTheNextTest() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 5));
        tc.addStatement(new IntPrimitiveStatement(tc, 6));
        BlockingObserver observer = new BlockingObserver();
        TestRunnable runnable = new TestRunnable(tc, new Scope(), Collections.singleton(observer));

        Sandbox.initializeSecurityManagerForSUT();
        Thread worker = null;
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            worker = new Thread(() -> {
                try {
                    runnable.call();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "worker-A");
            worker.start();
            Assertions.assertTrue(observer.inObserver.await(10, TimeUnit.SECONDS));

            // the waiting thread times out, and the next test starts
            runnable.markResultDiscarded();
            Sandbox.goingToExecuteSUTCode();
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());

            observer.release.countDown();
            worker.join(10_000);
            Assertions.assertFalse(worker.isAlive());
            Assertions.assertNull(failure.get());

            Assertions.assertFalse(observer.afterCalled.get(),
                    "observers still run for a run that was given up on");
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode(),
                    "the next test lost its executing flag");
            Sandbox.doneWithExecutingSUTCode();
        } finally {
            observer.release.countDown();
            Sandbox.resetDefaultSecurityManager();
        }
    }
}
