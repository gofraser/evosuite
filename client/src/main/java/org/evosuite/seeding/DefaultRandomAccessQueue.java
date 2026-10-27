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

import org.evosuite.Properties;
import org.evosuite.utils.Randomness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded queue of distinct constants drawn uniformly. The oldest constant is evicted once the
 * queue exceeds the dynamic pool size.
 *
 * @author Gordon Fraser
 */
public class DefaultRandomAccessQueue<T> implements ConstantBag<T> {

    private final Set<T> valueSet = new HashSet<>();

    private final Queue<T> queue = new ArrayDeque<>();

    @Override
    public synchronized void add(T value) {
        if (value == null) {
            return;
        }
        if (!valueSet.contains(value)) {
            queue.add(value);
            valueSet.add(value);
            reduceSize();
        }
    }

    private void reduceSize() {
        if (queue.size() > Math.max(1, Properties.DYNAMIC_POOL_SIZE)) {
            T value = queue.poll();
            valueSet.remove(value);
        }
    }

    @Override
    public synchronized T random() {
        return Randomness.choice(valueSet);
    }

    @Override
    public synchronized int removeIf(Predicate<? super T> predicate) {
        int removed = 0;
        Iterator<T> iterator = queue.iterator();
        while (iterator.hasNext()) {
            T value = iterator.next();
            if (predicate.test(value)) {
                iterator.remove();
                valueSet.remove(value);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public synchronized List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    @Override
    public synchronized String toString() {
        String res = "[ ";
        for (final Object element : queue) {
            res += element + " ";
        }
        res += "]";
        return res;
    }

}
