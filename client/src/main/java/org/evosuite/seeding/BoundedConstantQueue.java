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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * Insertion-ordered queue of constants that evicts the oldest entry once it exceeds the dynamic
 * pool size. Subclasses keep the structure from which constants are drawn in step with the queue.
 */
abstract class BoundedConstantQueue<T> implements ConstantBag<T> {

    private final Queue<T> queue = new ArrayDeque<>();

    private final int maxSize;

    /**
     * @param maxSize the number of constants to keep; the oldest one is dropped beyond that
     */
    BoundedConstantQueue(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    /**
     * Registers a non-null constant for drawing.
     *
     * @return whether the constant is to be appended to the queue
     */
    protected abstract boolean register(T value);

    /**
     * Unregisters one queued occurrence of a constant that left the queue.
     */
    protected abstract void unregister(T value);

    @Override
    public synchronized void add(T value) {
        if (value == null) {
            return;
        }
        if (register(value)) {
            queue.add(value);
            if (queue.size() > maxSize) {
                unregister(queue.poll());
            }
        }
    }

    @Override
    public synchronized int removeIf(Predicate<? super T> predicate) {
        int removed = 0;
        Iterator<T> iterator = queue.iterator();
        while (iterator.hasNext()) {
            T value = iterator.next();
            if (predicate.test(value)) {
                iterator.remove();
                unregister(value);
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
