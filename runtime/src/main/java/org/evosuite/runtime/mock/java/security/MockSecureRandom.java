/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.runtime.mock.java.security;

import org.evosuite.runtime.mock.OverrideMock;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * Mock class for {@link java.security.SecureRandom}.
 *
 * <p>This replacement ensures that {@link org.evosuite.runtime.Random} is used instead of the
 * default secure random number generator.
 */
public class MockSecureRandom extends SecureRandom implements OverrideMock {

    private static final long serialVersionUID = 3423648250373734907L;

    /**
     * Constructs a {@code MockSecureRandom} with a default seed.
     */
    public MockSecureRandom() {
        super(new byte[] { 0 });
    }

    /**
     * Constructs a {@code MockSecureRandom} with the given seed.
     *
     * @param seed the seed to use.
     */
    public MockSecureRandom(long seed) {
        super(toBytes(seed));
    }

    private static byte[] toBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    /**
     * Replacement function for {@link SecureRandom#nextInt()}.
     *
     * @return a pseudo-random, uniformly distributed {@code int} value.
     */
    public int nextInt() {
        return org.evosuite.runtime.Random.nextInt();
    }

    /**
     * Replacement function for {@link SecureRandom#nextInt(int)}.
     *
     * @param max the bound on the random number to be returned. Must be positive.
     * @return a pseudo-random, uniformly distributed {@code int} value between 0 (inclusive) and n (exclusive).
     */
    public int nextInt(int max) {
        return org.evosuite.runtime.Random.nextInt(max);
    }

    /**
     * Replacement function for {@link SecureRandom#nextFloat()}.
     *
     * @return a pseudo-random, uniformly distributed {@code float} value between 0.0 and 1.0.
     */
    public float nextFloat() {
        return org.evosuite.runtime.Random.nextFloat();
    }

    /**
     * Replacement function for {@link SecureRandom#nextBytes(byte[])}.
     *
     * @param bytes the array to be filled with random bytes.
     */
    public void nextBytes(byte[] bytes) {
        org.evosuite.runtime.Random.nextBytes(bytes);
    }

    /**
     * Replacement function for {@link SecureRandom#nextDouble()}.
     *
     * @return a pseudo-random, uniformly distributed {@code double} value between 0.0 and 1.0.
     */
    public double nextDouble() {
        return org.evosuite.runtime.Random.nextDouble();
    }

    /**
     * Replacement function for {@link SecureRandom#nextGaussian()}.
     *
     * @return a pseudo-random, Gaussian ("normally") distributed {@code double} value with mean 0.0 and
     *     standard deviation 1.0.
     */
    public double nextGaussian() {
        return org.evosuite.runtime.Random.nextGaussian();
    }

    /**
     * Replacement function for {@link SecureRandom#nextBoolean()}.
     *
     * @return a pseudo-random, uniformly distributed {@code boolean} value.
     */
    public boolean nextBoolean() {
        return org.evosuite.runtime.Random.nextBoolean();
    }

    /**
     * Replacement function for {@link SecureRandom#nextLong()}.
     *
     * @return a pseudo-random, uniformly distributed {@code long} value.
     */
    public long nextLong() {
        return org.evosuite.runtime.Random.nextLong();
    }

}
