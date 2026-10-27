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
package org.evosuite.seeding;

import org.evosuite.utils.Randomness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Set of constants drawn uniformly. The draw list is sorted deterministically after every
 * change so that a seed selects the same constant regardless of insertion order.
 */
class SortedSetBag<T> implements ConstantBag<T> {

    private final Set<T> set = new LinkedHashSet<>();
    private final List<T> list = new ArrayList<>();
    private boolean dirty = false;

    @Override
    public synchronized void add(T element) {
        if (element == null) {
            return;
        }
        if (set.add(element)) {
            list.add(element);
            dirty = true;
        }
    }

    @Override
    public synchronized T random() {
        if (list.isEmpty()) {
            return null;
        }
        if (dirty) {
            list.sort(Randomness::deterministicCompare);
            dirty = false;
        }
        return Randomness.choice(list);
    }

    @Override
    public synchronized int removeIf(Predicate<? super T> predicate) {
        int removed = 0;
        for (T element : new ArrayList<>(list)) {
            if (predicate.test(element)) {
                if (set.remove(element)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            list.removeIf(predicate);
            dirty = true;
        }
        return removed;
    }

    @Override
    public synchronized boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public synchronized List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    @Override
    public synchronized String toString() {
        return list.toString();
    }
}
