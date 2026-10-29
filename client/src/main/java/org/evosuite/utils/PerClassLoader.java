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
package org.evosuite.utils;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One lazily created value per class loader, safe to use from several threads. A {@code null}
 * loader (the bootstrap loader) is stored under the system class loader.
 *
 * <p>The pools that keep instrumentation results per class loader use this, so that they create,
 * release and clear their instances in the same way.
 *
 * @param <T> the per-loader value
 */
public final class PerClassLoader<T> {

    private final Map<ClassLoader, T> values = new ConcurrentHashMap<>();

    /**
     * Returns the value of the given class loader, creating it on first use.
     *
     * @param classLoader the class loader, or {@code null} for the bootstrap loader
     * @param factory     creates the value of a loader that has none yet
     * @return the value of that loader
     */
    public T get(ClassLoader classLoader, Function<ClassLoader, T> factory) {
        ClassLoader key = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        return values.computeIfAbsent(key, factory);
    }

    /**
     * Drops the value of the given class loader, so that it and, through it, the loader can be
     * garbage collected. The system class loader is never released.
     *
     * @param classLoader a class loader that is no longer used
     */
    public void release(ClassLoader classLoader) {
        if (classLoader != null) {
            values.remove(classLoader);
        }
    }

    /**
     * Drops the values of all class loaders.
     */
    public void clear() {
        values.clear();
    }

    /**
     * Returns the values of all class loaders that have one.
     *
     * @return the values
     */
    public Collection<T> values() {
        return values.values();
    }
}
