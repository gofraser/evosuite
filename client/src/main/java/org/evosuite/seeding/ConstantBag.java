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
import java.util.function.Predicate;

/**
 * Container for the constants of one type in a {@link TypedConstantPool}. Each implementation
 * keeps its own selection and eviction rules, so the draws made from it stay unchanged.
 *
 * @param <T> the constant type
 */
interface ConstantBag<T> {

    /** Adds a constant; {@code null} is ignored. */
    void add(T value);

    /** Draws a constant using the bag's own selection rule. */
    T random();

    /**
     * Removes all constants matching a predicate.
     *
     * @return the number of constants removed, counted the way the bag counts them
     */
    int removeIf(Predicate<? super T> predicate);

    boolean isEmpty();

    /** Unmodifiable snapshot of the distinct constants in the bag. */
    List<T> snapshot();
}
