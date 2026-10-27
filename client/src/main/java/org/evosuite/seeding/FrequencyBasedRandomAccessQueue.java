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

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * Bounded queue of constants drawn in proportion to how often they were added. The oldest
 * occurrence is evicted once the queue exceeds the dynamic pool size.
 */
public class FrequencyBasedRandomAccessQueue<T> implements ConstantBag<T> {

    private final FrequencyBasedPool<T> values = new FrequencyBasedPool<>();

    private final Queue<T> queue = new ArrayDeque<>();

    @Override
    public synchronized void add(T value) {
        if (value == null) {
            return;
        }
        values.add(value);
        queue.add(value);
        reduceSize();
    }

    private void reduceSize() {
        if (queue.size() > Math.max(1, Properties.DYNAMIC_POOL_SIZE)) {
            T value = queue.poll();
            values.removeConstant(value);
        }
    }

    @Override
    public synchronized T random() {
        return values.random();
    }

    @Override
    public synchronized int removeIf(Predicate<? super T> predicate) {
        int removed = 0;
        Iterator<T> iterator = queue.iterator();
        while (iterator.hasNext()) {
            T value = iterator.next();
            if (predicate.test(value)) {
                iterator.remove();
                values.removeConstant(value);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public synchronized List<T> snapshot() {
        return values.snapshot();
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
