/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.lang.ScopedValue;
import java.util.Objects;

/**
 * Static replacement hooks for {@link java.lang.ScopedValue.Carrier}.
 */
public class MockScopedValueCarrier implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ScopedValue.Carrier.class.getName();
    }

    public static <T> ScopedValue.Carrier where(ScopedValue.Carrier carrier, ScopedValue<T> key, T value) {
        Objects.requireNonNull(carrier);
        Objects.requireNonNull(key);
        return carrier.where(key, value);
    }

    public static <T> T get(ScopedValue.Carrier carrier, ScopedValue<T> key) {
        Objects.requireNonNull(carrier);
        Objects.requireNonNull(key);
        return carrier.get(key);
    }

    public static <R, X extends Throwable> R call(
            ScopedValue.Carrier carrier,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        Objects.requireNonNull(carrier);
        Objects.requireNonNull(operation);
        return carrier.call(operation);
    }

    public static void run(ScopedValue.Carrier carrier, Runnable runnable) {
        Objects.requireNonNull(carrier);
        Objects.requireNonNull(runnable);
        carrier.run(runnable);
    }
}
