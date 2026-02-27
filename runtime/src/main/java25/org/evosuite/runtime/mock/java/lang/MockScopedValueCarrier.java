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
