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

import java.util.List;

/**
 * Bounded queue of constants drawn in proportion to how often they were added. The oldest
 * occurrence is evicted once the queue exceeds the dynamic pool size.
 */
public class FrequencyBasedRandomAccessQueue<T> extends BoundedConstantQueue<T> {

    private final FrequencyBasedPool<T> values = new FrequencyBasedPool<>();

    public FrequencyBasedRandomAccessQueue(int maxSize) {
        super(maxSize);
    }

    @Override
    protected boolean register(T value) {
        values.add(value);
        return true;
    }

    @Override
    protected void unregister(T value) {
        values.removeConstant(value);
    }

    @Override
    public synchronized T random() {
        return values.random();
    }

    @Override
    public synchronized boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public synchronized List<T> snapshot() {
        return values.snapshot();
    }

}
