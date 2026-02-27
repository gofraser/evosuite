/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.util.random;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.random.RandomGenerator;

/**
 * Deterministic replacement for {@link RandomGenerator}.
 */
public class MockRandomGenerator implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return RandomGenerator.class.getName();
    }

    public static RandomGenerator of(String name) {
        return deterministic();
    }

    public static int nextInt(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextInt();
    }

    public static int nextInt(RandomGenerator generator, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextInt(bound);
    }

    public static int nextInt(RandomGenerator generator, int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + org.evosuite.runtime.Random.nextInt(bound - origin);
    }

    public static long nextLong(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextLong();
    }

    public static long nextLong(RandomGenerator generator, long bound) {
        if (bound <= 0L) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return Math.floorMod(org.evosuite.runtime.Random.nextLong(), bound);
    }

    public static long nextLong(RandomGenerator generator, long origin, long bound) {
        long delta = bound - origin;
        if (delta <= 0L) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + Math.floorMod(org.evosuite.runtime.Random.nextLong(), delta);
    }

    public static double nextDouble(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextDouble();
    }

    public static double nextDouble(RandomGenerator generator, double bound) {
        if (!(bound > 0.0d)) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextDouble() * bound;
    }

    public static double nextDouble(RandomGenerator generator, double origin, double bound) {
        if (!(origin < bound)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + (org.evosuite.runtime.Random.nextDouble() * (bound - origin));
    }

    public static boolean nextBoolean(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextBoolean();
    }

    public static float nextFloat(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextFloat();
    }

    public static double nextGaussian(RandomGenerator generator) {
        return org.evosuite.runtime.Random.nextGaussian();
    }

    public static void nextBytes(RandomGenerator generator, byte[] bytes) {
        org.evosuite.runtime.Random.nextBytes(bytes);
    }

    static RandomGenerator deterministic() {
        return new DeterministicRandomGenerator();
    }

    static RandomGenerator deterministic(long seed) {
        return new SeededDeterministicRandomGenerator(seed);
    }

    private static final class DeterministicRandomGenerator implements RandomGenerator {
        @Override
        public long nextLong() {
            return org.evosuite.runtime.Random.nextLong();
        }
    }

    private static final class SeededDeterministicRandomGenerator implements RandomGenerator {
        private long state;

        private SeededDeterministicRandomGenerator(long seed) {
            this.state = seed;
        }

        @Override
        public long nextLong() {
            return state++;
        }
    }
}
