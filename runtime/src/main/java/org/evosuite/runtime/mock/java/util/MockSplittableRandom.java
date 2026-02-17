/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.SplittableRandom;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Deterministic replacement for {@link SplittableRandom}.
 */
@SuppressWarnings("checkstyle:MethodName")
public class MockSplittableRandom implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return SplittableRandom.class.getName();
    }

    // ---------- constructors ----------

    public static SplittableRandom SplittableRandom() {
        return new SplittableRandom(org.evosuite.runtime.Random.nextLong());
    }

    public static SplittableRandom SplittableRandom(long seed) {
        return new SplittableRandom(seed);
    }

    // ---------- instance methods ----------

    public static SplittableRandom split(SplittableRandom random) {
        return new SplittableRandom(org.evosuite.runtime.Random.nextLong());
    }

    public static int nextInt(SplittableRandom random) {
        return org.evosuite.runtime.Random.nextInt();
    }

    /**
     * Replacement for {@link SplittableRandom#nextInt(int)}.
     *
     * @param random the random instance
     * @param bound  the bound
     * @return the random int
     */
    public static int nextInt(SplittableRandom random, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextInt(bound);
    }

    /**
     * Replacement for {@link SplittableRandom#nextInt(int, int)}.
     *
     * @param random the random instance
     * @param origin the origin
     * @param bound  the bound
     * @return the random int
     */
    public static int nextInt(SplittableRandom random, int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + org.evosuite.runtime.Random.nextInt(bound - origin);
    }

    public static long nextLong(SplittableRandom random) {
        return org.evosuite.runtime.Random.nextLong();
    }

    /**
     * Replacement for {@link SplittableRandom#nextLong(long)}.
     *
     * @param random the random instance
     * @param bound  the bound
     * @return the random long
     */
    public static long nextLong(SplittableRandom random, long bound) {
        if (bound <= 0L) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return Math.floorMod(org.evosuite.runtime.Random.nextLong(), bound);
    }

    /**
     * Replacement for {@link SplittableRandom#nextLong(long, long)}.
     *
     * @param random the random instance
     * @param origin the origin
     * @param bound  the bound
     * @return the random long
     */
    public static long nextLong(SplittableRandom random, long origin, long bound) {
        long delta = bound - origin;
        if (delta <= 0L) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + Math.floorMod(org.evosuite.runtime.Random.nextLong(), delta);
    }

    public static double nextDouble(SplittableRandom random) {
        return org.evosuite.runtime.Random.nextDouble();
    }

    /**
     * Replacement for {@link SplittableRandom#nextDouble(double)}.
     *
     * @param random the random instance
     * @param bound  the bound
     * @return the random double
     */
    public static double nextDouble(SplittableRandom random, double bound) {
        if (!(bound > 0.0d)) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextDouble() * bound;
    }

    /**
     * Replacement for {@link SplittableRandom#nextDouble(double, double)}.
     *
     * @param random the random instance
     * @param origin the origin
     * @param bound  the bound
     * @return the random double
     */
    public static double nextDouble(SplittableRandom random, double origin, double bound) {
        if (!(origin < bound)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + (org.evosuite.runtime.Random.nextDouble() * (bound - origin));
    }

    public static void nextBytes(SplittableRandom random, byte[] bytes) {
        org.evosuite.runtime.Random.nextBytes(bytes);
    }

    // ---------- stream helpers ----------

    public static IntStream ints(SplittableRandom random) {
        return IntStream.generate(org.evosuite.runtime.Random::nextInt);
    }

    public static IntStream ints(SplittableRandom random, long streamSize) {
        return ints(random).limit(streamSize);
    }

    public static IntStream ints(SplittableRandom random, int origin, int bound) {
        return IntStream.generate(() -> nextInt(random, origin, bound));
    }

    public static IntStream ints(SplittableRandom random, long streamSize, int origin, int bound) {
        return ints(random, origin, bound).limit(streamSize);
    }

    public static LongStream longs(SplittableRandom random) {
        return LongStream.generate(org.evosuite.runtime.Random::nextLong);
    }

    public static LongStream longs(SplittableRandom random, long streamSize) {
        return longs(random).limit(streamSize);
    }

    public static LongStream longs(SplittableRandom random, long origin, long bound) {
        return LongStream.generate(() -> nextLong(random, origin, bound));
    }

    public static LongStream longs(SplittableRandom random, long streamSize, long origin, long bound) {
        return longs(random, origin, bound).limit(streamSize);
    }

    public static DoubleStream doubles(SplittableRandom random) {
        return DoubleStream.generate(org.evosuite.runtime.Random::nextDouble);
    }

    public static DoubleStream doubles(SplittableRandom random, long streamSize) {
        return doubles(random).limit(streamSize);
    }

    public static DoubleStream doubles(SplittableRandom random, double origin, double bound) {
        return DoubleStream.generate(() -> nextDouble(random, origin, bound));
    }

    public static DoubleStream doubles(SplittableRandom random, long streamSize, double origin, double bound) {
        return doubles(random, origin, bound).limit(streamSize);
    }
}
