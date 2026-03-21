/**
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
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Mock for {@link java.util.concurrent.Semaphore} that never blocks.
 *
 * <p>When the mock framework is enabled, all blocking {@code acquire} methods
 * return immediately (as if permits are always available), and timed
 * {@code tryAcquire} calls return {@code true} without waiting.  This prevents
 * the SUT from hanging during test generation when it waits on a semaphore
 * that would only be released by external interaction.
 *
 * <p>When the mock framework is disabled, all methods delegate directly to
 * the real {@link Semaphore} implementation.
 */
public class MockSemaphore extends Semaphore implements OverrideMock {

    private static final long serialVersionUID = 1L;

    // -------- constructors --------

    public MockSemaphore(int permits) {
        super(permits);
    }

    public MockSemaphore(int permits, boolean fair) {
        super(permits, fair);
    }

    // -------- blocking methods overridden to never block --------

    @Override
    public void acquire() throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            super.acquire();
            return;
        }
        // Non-blocking: try to acquire, but don't wait if unavailable.
        // The permit count still decrements on success, preserving basic semantics.
        super.tryAcquire();
    }

    @Override
    public void acquire(int permits) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            super.acquire(permits);
            return;
        }
        super.tryAcquire(permits);
    }

    @Override
    public void acquireUninterruptibly() {
        if (!MockFramework.isEnabled()) {
            super.acquireUninterruptibly();
            return;
        }
        super.tryAcquire();
    }

    @Override
    public void acquireUninterruptibly(int permits) {
        if (!MockFramework.isEnabled()) {
            super.acquireUninterruptibly(permits);
            return;
        }
        super.tryAcquire(permits);
    }

    @Override
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            return super.tryAcquire(timeout, unit);
        }
        // Return the result of tryAcquire() immediately without waiting.
        return super.tryAcquire();
    }

    @Override
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            return super.tryAcquire(permits, timeout, unit);
        }
        return super.tryAcquire(permits);
    }
}
