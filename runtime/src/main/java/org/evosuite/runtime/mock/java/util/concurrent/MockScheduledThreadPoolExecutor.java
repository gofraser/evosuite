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
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.mock.OverrideMock;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/**
 * Mock for {@link ScheduledThreadPoolExecutor}, clamping {@code corePoolSize} the same
 * way {@link MockThreadPoolExecutor} clamps its parent's core/maximum pool size -- see
 * that class for why. {@code ScheduledThreadPoolExecutor} always runs with an unbounded
 * work queue and {@code maximumPoolSize} fixed at {@code Integer.MAX_VALUE} internally,
 * so {@code corePoolSize} alone determines how many worker threads it can grow to.
 */
public class MockScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor implements OverrideMock {

    public MockScheduledThreadPoolExecutor(int corePoolSize) {
        super(MockThreadPoolExecutor.clamp(corePoolSize));
    }

    public MockScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(MockThreadPoolExecutor.clamp(corePoolSize), threadFactory);
    }

    public MockScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler) {
        super(MockThreadPoolExecutor.clamp(corePoolSize), handler);
    }

    public MockScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory,
                                           RejectedExecutionHandler handler) {
        super(MockThreadPoolExecutor.clamp(corePoolSize), threadFactory, handler);
    }
}
