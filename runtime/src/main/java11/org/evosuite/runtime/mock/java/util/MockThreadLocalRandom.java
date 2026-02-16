/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic replacement for {@link ThreadLocalRandom}.
 */
public class MockThreadLocalRandom implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ThreadLocalRandom.class.getName();
    }

    public static ThreadLocalRandom current() {
        // The runtime never uses this instance directly once methods are instrumented.
        return ThreadLocalRandom.current();
    }

    public static int nextInt(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextInt();
    }

    public static int nextInt(ThreadLocalRandom random, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextInt(bound);
    }

    public static int nextInt(ThreadLocalRandom random, int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + org.evosuite.runtime.Random.nextInt(bound - origin);
    }

    public static long nextLong(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextLong();
    }

    public static long nextLong(ThreadLocalRandom random, long bound) {
        if (bound <= 0L) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return Math.floorMod(org.evosuite.runtime.Random.nextLong(), bound);
    }

    public static long nextLong(ThreadLocalRandom random, long origin, long bound) {
        long delta = bound - origin;
        if (delta <= 0L) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        long next = Math.floorMod(org.evosuite.runtime.Random.nextLong(), delta);
        return origin + next;
    }

    public static double nextDouble(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextDouble();
    }

    public static double nextDouble(ThreadLocalRandom random, double bound) {
        if (!(bound > 0.0d)) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return org.evosuite.runtime.Random.nextDouble() * bound;
    }

    public static double nextDouble(ThreadLocalRandom random, double origin, double bound) {
        if (!(origin < bound)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + (org.evosuite.runtime.Random.nextDouble() * (bound - origin));
    }

    public static boolean nextBoolean(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextBoolean();
    }

    public static float nextFloat(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextFloat();
    }

    public static double nextGaussian(ThreadLocalRandom random) {
        return org.evosuite.runtime.Random.nextGaussian();
    }

    public static void nextBytes(ThreadLocalRandom random, byte[] bytes) {
        org.evosuite.runtime.Random.nextBytes(bytes);
    }
}
