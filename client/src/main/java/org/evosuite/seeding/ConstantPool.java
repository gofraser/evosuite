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

import org.objectweb.asm.Type;

import java.util.Collection;
import java.util.Collections;

/**
 * Interface for constant pools.
 *
 * @author Gordon Fraser
 */
public interface ConstantPool {

    /**
     * Returns a random string.
     *
     * @return a {@link java.lang.String} object.
     */
    String getRandomString();

    /**
     * Returns a random type.
     *
     * @return a {@link org.objectweb.asm.Type} object.
     */
    Type getRandomType();

    /**
     * Returns a random int.
     *
     * @return a int.
     */
    int getRandomInt();

    /**
     * Returns a random float.
     *
     * @return a float.
     */
    float getRandomFloat();

    /**
     * Returns a random double.
     *
     * @return a double.
     */
    double getRandomDouble();

    /**
     * Returns a random long.
     *
     * @return a long.
     */
    long getRandomLong();

    /**
     * Adds an object to the pool.
     *
     * @param object a {@link java.lang.Object} object.
     */
    void add(Object object);

    /**
     * Removes numeric constants whose absolute value is not smaller than {@code maxAbsExclusive}.
     *
     * @param maxAbsExclusive exclusive absolute bound; <= 0 means no pruning.
     * @return number of removed constants.
     */
    default int pruneOversizedNumericConstants(long maxAbsExclusive) {
        return 0;
    }

    /**
     * Snapshot of strings currently in the pool. Default returns empty so
     * implementations without efficient iteration don't have to opt in.
     */
    default Collection<String> getStrings() {
        return Collections.emptyList();
    }

    /** Snapshot of int constants currently in the pool. */
    default Collection<Integer> getInts() {
        return Collections.emptyList();
    }

    /** Snapshot of long constants currently in the pool. */
    default Collection<Long> getLongs() {
        return Collections.emptyList();
    }

    /** Snapshot of float constants currently in the pool. */
    default Collection<Float> getFloats() {
        return Collections.emptyList();
    }

    /** Snapshot of double constants currently in the pool. */
    default Collection<Double> getDoubles() {
        return Collections.emptyList();
    }

    String toString();
}
