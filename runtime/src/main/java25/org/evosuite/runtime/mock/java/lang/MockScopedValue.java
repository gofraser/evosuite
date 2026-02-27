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

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.lang.ScopedValue;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Static replacement hooks for Java 25 {@link ScopedValue}.
 */
public class MockScopedValue implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ScopedValue.class.getName();
    }

    public static <T> ScopedValue<T> newInstance() {
        return ScopedValue.newInstance();
    }

    public static <T> ScopedValue.Carrier where(ScopedValue<T> key, T value) {
        Objects.requireNonNull(key);
        return ScopedValue.where(key, value);
    }

    public static <T> T get(ScopedValue<T> scopedValue) {
        Objects.requireNonNull(scopedValue);
        return scopedValue.get();
    }

    public static boolean isBound(ScopedValue<?> scopedValue) {
        Objects.requireNonNull(scopedValue);
        return scopedValue.isBound();
    }

    public static <T> T orElse(ScopedValue<T> scopedValue, T other) {
        Objects.requireNonNull(scopedValue);
        return scopedValue.orElse(other);
    }

    public static <T, X extends Throwable> T orElseThrow(
            ScopedValue<T> scopedValue,
            Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(scopedValue);
        Objects.requireNonNull(exceptionSupplier);
        if (!MockFramework.isEnabled()) {
            return scopedValue.orElseThrow(exceptionSupplier);
        }
        return scopedValue.orElseThrow(exceptionSupplier);
    }
}
