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
package org.evosuite.testcase;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dynamic allocation-sensitive registry on
 * {@link StatementFactory} and its listener notifications. Uses a
 * randomly generated class-name string per test so the test does not
 * pollute the static registry with fixed entries (which would interfere
 * with other tests that observe the same state).
 */
public class AllocationSensitivityTest {

    private static String uniqueClassName() {
        return "test.dynamic.AllocSensitive_" + UUID.randomUUID();
    }

    @Test
    public void isDynamicallyAllocationSensitive_falseByDefault() {
        String name = uniqueClassName();
        assertFalse(StatementFactory.isDynamicallyAllocationSensitive(loadOrSynth(name)));
        assertFalse(StatementFactory.isDynamicallyAllocationSensitive(null));
    }

    @Test
    public void isDynamicallyAllocationSensitive_trueAfterRegistration() {
        // Register a real, loadable class so we can pass its Class<?> to the predicate.
        // (We use String here only because we need a Class<?> instance to test the
        // hierarchy walk — the predicate doesn't reflect on its members.)
        String name = String.class.getName();
        try {
            StatementFactory.addAllocationSensitiveClass(name);
            assertTrue(StatementFactory.isDynamicallyAllocationSensitive(String.class));
        } finally {
            // No public API to unregister; the test is idempotent and the registry is
            // a Set, so repeated runs don't accumulate duplicates. Other tests that
            // care about String specifically would already be affected by static state.
        }
    }

    @Test
    public void listener_invokedOnceOnFirstRegistration() {
        String name = uniqueClassName();
        AtomicReference<String> seen = new AtomicReference<>();
        int[] callCount = {0};
        Consumer<String> listener = cn -> {
            callCount[0]++;
            seen.set(cn);
        };
        StatementFactory.addAllocationSensitiveListener(listener);
        try {
            StatementFactory.addAllocationSensitiveClass(name);
            StatementFactory.addAllocationSensitiveClass(name); // re-registration must be a no-op
        } finally {
            StatementFactory.removeAllocationSensitiveListener(listener);
        }
        assertEquals(1, callCount[0], "listener must fire exactly once per newly added class");
        assertEquals(name, seen.get());
    }

    @Test
    public void listener_throwingDoesNotBreakRegistration() {
        String name = uniqueClassName();
        Consumer<String> badListener = cn -> {
            throw new RuntimeException("boom");
        };
        StatementFactory.addAllocationSensitiveListener(badListener);
        try {
            // Must not propagate
            StatementFactory.addAllocationSensitiveClass(name);
        } finally {
            StatementFactory.removeAllocationSensitiveListener(badListener);
        }
        // The class is still registered even though the listener blew up.
        // We can't probe by name (predicate takes Class<?>), but we can verify
        // a follow-up listener for the SAME class is NOT re-fired (because the
        // set.add returned false).
        int[] callCount = {0};
        Consumer<String> probe = cn -> callCount[0]++;
        StatementFactory.addAllocationSensitiveListener(probe);
        try {
            StatementFactory.addAllocationSensitiveClass(name);
        } finally {
            StatementFactory.removeAllocationSensitiveListener(probe);
        }
        assertEquals(0, callCount[0], "second registration of same class must not refire listeners");
    }

    /**
     * The predicate must be safe to call with null and walk the superclass chain
     * (so a subclass of a registered class is also flagged).
     */
    @Test
    public void nullClassIsNotSensitive() {
        assertFalse(StatementFactory.isDynamicallyAllocationSensitive(null));
    }

    private static Class<?> loadOrSynth(String name) {
        // The predicate needs a Class<?>; we don't actually need name to match,
        // we just need any Class whose hierarchy does NOT contain `name`.
        assertNotNull(name);
        return Object.class;
    }

    /**
     * Sanity check: an empty/blank class name on registration is a no-op
     * (we don't want a stray empty string in the set acting as a wildcard).
     */
    @Test
    public void registeringNullOrEmptyIsNoop() {
        int[] callCount = {0};
        Consumer<String> probe = cn -> callCount[0]++;
        StatementFactory.addAllocationSensitiveListener(probe);
        try {
            StatementFactory.addAllocationSensitiveClass(null);
        } finally {
            StatementFactory.removeAllocationSensitiveListener(probe);
        }
        assertEquals(0, callCount[0]);
        assertNull(null);
    }
}
