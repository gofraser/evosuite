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
package org.evosuite.llm.provider;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage of {@link RequestCancellation} itself, independent of
 * {@link JsonHttpClient}: whether {@link RequestCancellation#attach(Runnable)}
 * correctly reports whether the handle was already aborted, which is what a
 * caller relies on to decide whether it is still safe to send a request.
 */
class RequestCancellationTest {

    @Test
    void attachReturnsTrueOnAFreshHandle() {
        RequestCancellation cancellation = new RequestCancellation();
        assertTrue(cancellation.attach(() -> { }));
        assertFalse(cancellation.isAborted());
    }

    @Test
    void attachReturnsFalseOnceAlreadyAborted() {
        RequestCancellation cancellation = new RequestCancellation();
        cancellation.abort();
        assertFalse(cancellation.attach(() -> { }));
        assertTrue(cancellation.isAborted());
    }

    @Test
    void aborterRunsExactlyOnceWhenAbortComesBeforeAttach() {
        AtomicInteger runs = new AtomicInteger();
        RequestCancellation cancellation = new RequestCancellation();
        cancellation.abort();
        boolean attachedBeforeAbort = cancellation.attach(runs::incrementAndGet);
        assertFalse(attachedBeforeAbort);
        assertEquals(1, runs.get());
        cancellation.abort();
        assertEquals(1, runs.get());
    }

    @Test
    void aborterRunsExactlyOnceWhenAbortComesAfterAttach() {
        AtomicInteger runs = new AtomicInteger();
        RequestCancellation cancellation = new RequestCancellation();
        boolean attachedBeforeAbort = cancellation.attach(runs::incrementAndGet);
        assertTrue(attachedBeforeAbort);
        assertEquals(0, runs.get());
        cancellation.abort();
        assertEquals(1, runs.get());
        cancellation.abort();
        assertEquals(1, runs.get());
    }

    @Test
    void abortWithoutAnyAttachedAborterIsSafe() {
        RequestCancellation cancellation = new RequestCancellation();
        cancellation.abort();
        assertTrue(cancellation.isAborted());
    }
}
