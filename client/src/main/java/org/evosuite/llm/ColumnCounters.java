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
package org.evosuite.llm;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A process-wide counter per constant of an enum, each published as its own runtime variable, plus
 * an optional total. The variable of a constant is found by name, a prefix followed by the
 * constant in camel case, once at construction: a constant without a matching
 * {@link RuntimeVariable} fails the class that declares the table instead of publishing under a
 * neighbouring column.
 *
 * @param <E> the enum whose constants are counted
 */
final class ColumnCounters<E extends Enum<E>> {

    private final RuntimeVariable totalVariable;
    private final AtomicLong total = new AtomicLong();
    private final Map<E, RuntimeVariable> columns;
    private final Map<E, AtomicLong> counters;

    /**
     * @param keyType       the enum to count
     * @param totalVariable the variable of the total, or null if the table has none
     * @param prefix        the start of the name of each constant's variable
     */
    ColumnCounters(Class<E> keyType, RuntimeVariable totalVariable, String prefix) {
        this.totalVariable = totalVariable;
        this.columns = columns(keyType, prefix, "");
        Map<E, AtomicLong> byKey = new EnumMap<>(keyType);
        for (E key : keyType.getEnumConstants()) {
            byKey.put(key, new AtomicLong());
        }
        this.counters = Collections.unmodifiableMap(byKey);
    }

    /**
     * Resolves the variable of every constant of {@code keyType} as {@code prefix}, the constant in
     * camel case, then {@code suffix}.
     *
     * @throws IllegalStateException if a constant has no variable of that name
     */
    static <K extends Enum<K>> Map<K, RuntimeVariable> columns(Class<K> keyType, String prefix, String suffix) {
        Map<K, RuntimeVariable> columns = new EnumMap<>(keyType);
        for (K key : keyType.getEnumConstants()) {
            String name = prefix + camelCase(key) + suffix;
            try {
                columns.put(key, RuntimeVariable.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("No runtime variable " + name + " for " + key, e);
            }
        }
        return Collections.unmodifiableMap(columns);
    }

    private static String camelCase(Enum<?> constant) {
        StringBuilder camel = new StringBuilder();
        for (String word : constant.name().split("_")) {
            if (!word.isEmpty()) {
                camel.append(word.substring(0, 1)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return camel.toString();
    }

    Set<E> keys() {
        return counters.keySet();
    }

    RuntimeVariable column(E key) {
        return columns.get(key);
    }

    RuntimeVariable totalColumn() {
        return totalVariable;
    }

    long get(E key) {
        AtomicLong counter = key == null ? null : counters.get(key);
        return counter == null ? 0L : counter.get();
    }

    long total() {
        return total.get();
    }

    /** Adds {@code count} to the counter of {@code key} and publishes its new value. */
    void add(E key, long count) {
        AtomicLong counter = key == null ? null : counters.get(key);
        if (counter != null) {
            ClientServices.track(columns.get(key), counter.addAndGet(count));
        }
    }

    /** Adds {@code count} to the total and publishes its new value. */
    void addToTotal(long count) {
        ClientServices.track(totalVariable, total.addAndGet(count));
    }

    void reset() {
        total.set(0L);
        for (AtomicLong counter : counters.values()) {
            counter.set(0L);
        }
    }
}
