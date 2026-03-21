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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mock for {@link java.util.concurrent.CountDownLatch} that never blocks.
 *
 * <p>When the mock framework is enabled, {@code await()} returns immediately
 * and {@code await(timeout, unit)} returns {@code true} without waiting.
 * This prevents the SUT from hanging when it waits for a latch that would
 * only be counted down by external threads.
 */
public class MockCountDownLatch extends CountDownLatch implements OverrideMock {

    public MockCountDownLatch(int count) {
        super(count);
    }

    @Override
    public void await() throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            super.await();
            return;
        }
        // Don't block — return immediately.
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            return super.await(timeout, unit);
        }
        // Return true immediately as if the latch reached zero.
        return true;
    }
}
