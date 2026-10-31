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

import java.util.HashSet;
import java.util.Set;

/**
 * Bounded queue of distinct constants drawn uniformly. The oldest constant is evicted once the
 * queue exceeds the dynamic pool size.
 *
 * @author Gordon Fraser
 */
public class DefaultRandomAccessQueue<T> extends BoundedConstantQueue<T> {

    private final Set<T> valueSet = new HashSet<>();

    public DefaultRandomAccessQueue(int maxSize) {
        super(maxSize);
    }

    @Override
    protected boolean register(T value) {
        return valueSet.add(value);
    }

    @Override
    protected void unregister(T value) {
        valueSet.remove(value);
    }

    @Override
    public synchronized T random() {
        return Randomness.choice(valueSet);
    }

}
