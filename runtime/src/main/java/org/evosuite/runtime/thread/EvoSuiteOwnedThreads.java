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
package org.evosuite.runtime.thread;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Registry of EvoSuite-owned background threads (LLM HTTP workers, javac
 * compile threads, ...) that may be born while a test executes and must not
 * be treated as a leaked SUT thread by {@link ThreadStopper}.
 *
 * <p>Membership is by object identity, not by thread name: {@code Thread}
 * does not override {@code equals}/{@code hashCode}, so a SUT cannot opt a
 * thread of its own out of cleanup by naming it after an EvoSuite naming
 * convention.
 */
public final class EvoSuiteOwnedThreads {

    private static final Set<Thread> OWNED =
            Collections.newSetFromMap(new WeakHashMap<Thread, Boolean>());

    private EvoSuiteOwnedThreads() {
    }

    /**
     * Registers {@code t} as an EvoSuite-owned background thread, exempting
     * it from {@link ThreadStopper}'s post-test cleanup regardless of what it
     * is named.
     */
    public static synchronized void register(Thread t) {
        OWNED.add(t);
    }

    /** Returns whether {@code t} was previously {@link #register}ed. */
    public static synchronized boolean isOwned(Thread t) {
        return OWNED.contains(t);
    }
}
