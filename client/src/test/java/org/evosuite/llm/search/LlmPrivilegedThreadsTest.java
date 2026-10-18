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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.search;

import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPrivilegedThreadsTest {

    private static void activateSandbox() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        try {
            Sandbox.initializeSecurityManagerForSUT();
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("Security Manager is unavailable on this JVM");
        }
    }

    @Test
    void workerCreatedByPrivilegedWorkerIsPrivileged() throws Exception {
        activateSandbox();
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> child = new AtomicReference<>();
        Set<Thread> privileged;
        try {
            Thread parent = LlmPrivilegedThreads.newThread(() -> {
                Thread c = LlmPrivilegedThreads.newThread(() -> awaitQuietly(release), "child");
                child.set(c);
                c.start();
                done.countDown();
            }, "parent");
            parent.start();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            privileged = Sandbox.resetDefaultSecurityManager();
            release.countDown();
        }
        assertTrue(privileged.contains(child.get()),
                "a worker started from a privileged LLM worker must be privileged");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
