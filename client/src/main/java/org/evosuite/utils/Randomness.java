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
package org.evosuite.utils;

import org.evosuite.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Unique random number accessor.
 *
 * @author Gordon Fraser
 */
public class Randomness implements Serializable {

    private static final long serialVersionUID = -5934455398558935937L;

    private static final Logger logger = LoggerFactory.getLogger(Randomness.class);

    private static long seed = 0;

    private static Random random = null;

    private static Randomness instance = new Randomness();

    private Randomness() {
        Long seedParameter = Properties.RANDOM_SEED;
        if (seedParameter != null) {
            seed = seedParameter;
            logger.info("Random seed: {}", seed);
        } else {
            seed = System.currentTimeMillis();
            logger.info("No seed given. Using {}.", seed);
        }
        random = new MersenneTwister(seed);
    }

    /**
     * <p>
     * Getter for the field <code>instance</code>.
     * </p>
     *
     * @return a {@link org.evosuite.utils.Randomness} object.
     */
    public static Randomness getInstance() {
        return instance;
    }

    /**
     * <p>
     * nextBoolean.
     * </p>
     *
     * @return a boolean.
     */
    public static boolean nextBoolean() {
        return random.nextBoolean();
    }

    /**
     * Returns a pseudorandom, uniformly distributed int value between 0 (inclusive) and the
     * specified value {@code max} (exclusive).
     *
     * @param max the upper bound
     * @return a random number between 0 and {@code max - 1}
     * @see Random#nextInt(int)
     */
    public static int nextInt(int max) {
        return random.nextInt(max);
    }

    public static double nextGaussian() {
        return random.nextGaussian();
    }

    /**
     * Returns a pseudorandom, uniformly distributed int value between the lower bound {@code min}
     * (inclusive) and the upper bound {@code max} (exclusive).
     *
     * @param min the lower bound
     * @param max the upper bound
     * @return a random number between {@code min} and {@code max}
     */
    public static int nextInt(int min, int max) {
        return random.nextInt(max - min) + min;
    }

    /**
     * <p>
     * nextInt.
     * </p>
     *
     * @return a int.
     */
    public static int nextInt() {
        return random.nextInt();
    }

    /**
     * <p>
     * nextChar.
     * </p>
     *
     * @return a char.
     */
    public static char nextChar() {
        return (char) (nextInt(32, 128));
    }

    /**
     * <p>
     * nextShort.
     * </p>
     *
     * @return a short.
     */
    public static short nextShort() {
        return (short) (random.nextInt(2 * 32767) - 32767);
    }

    /**
     * <p>
     * nextLong.
     * </p>
     *
     * @return a long.
     */
    public static long nextLong() {
        return random.nextLong();
    }

    /**
     * <p>
     * nextByte.
     * </p>
     *
     * @return a byte.
     */
    public static byte nextByte() {
        return (byte) (random.nextInt(256) - 128);
    }

    /**
     * <p>
     * returns a randomly generated double in the range [0,1].
     * </p>
     *
     * @return a double between 0.0 and 1.0
     */
    public static double nextDouble() {
        return random.nextDouble();
    }

    /**
     * <p>
     * nextDouble.
     * </p>
     *
     * @param min a double.
     * @param max a double.
     * @return a double.
     */
    public static double nextDouble(double min, double max) {
        return min + (random.nextDouble() * (max - min));
    }

    /**
     * <p>
     * nextFloat.
     * </p>
     *
     * @return a float.
     */
    public static float nextFloat() {
        return random.nextFloat();
    }

    /**
     * <p>
     * Setter for the field <code>seed</code>.
     * </p>
     *
     * @param seed a long.
     */
    public static void setSeed(long seed) {
        Randomness.seed = seed;
        random.setSeed(seed);
    }

    /**
     * <p>
     * Getter for the field <code>seed</code>.
     * </p>
     *
     * @return a long.
     */
    public static long getSeed() {
        return seed;
    }

    /**
     * <p>
     * choice.
     * </p>
     *
     * @param list a {@link java.util.List} object.
     * @param <T>  a T object.
     * @return a T object or <code>null</code> if <code>list</code> is empty.
     */
    public static <T> T choice(List<T> list) {
        if (list.isEmpty()) {
            return null;
        }

        int position = random.nextInt(list.size());
        return list.get(position);
    }

    /**
     * <p>
     * choice.
     * </p>
     *
     * @param set a {@link java.util.Collection} object.
     * @param <T> a T object.
     * @return a T object or <code>null</code> if <code>set</code> is empty.
     */
    @SuppressWarnings("unchecked")
    public static <T> T choice(Collection<T> set) {
        if (set.isEmpty()) {
            return null;
        }

        if (set instanceof List) {
            return ((List<T>) set).get(random.nextInt(set.size()));
        }

        List<T> list = new java.util.ArrayList<>(set);
        // Stabilize iteration order for non-List collections when possible.
        list.sort(Randomness::deterministicCompare);
        return list.get(random.nextInt(list.size()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int deterministicCompare(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }

        if (a instanceof Class && b instanceof Class) {
            return ((Class<?>) a).getName().compareTo(((Class<?>) b).getName());
        }

        if (a instanceof java.lang.reflect.Type && b instanceof java.lang.reflect.Type) {
            return ((java.lang.reflect.Type) a).getTypeName()
                    .compareTo(((java.lang.reflect.Type) b).getTypeName());
        }

        if (a instanceof java.lang.reflect.Member && b instanceof java.lang.reflect.Member) {
            java.lang.reflect.Member ma = (java.lang.reflect.Member) a;
            java.lang.reflect.Member mb = (java.lang.reflect.Member) b;
            int c = ma.getDeclaringClass().getName().compareTo(mb.getDeclaringClass().getName());
            if (c != 0) {
                return c;
            }
            c = ma.getName().compareTo(mb.getName());
            if (c != 0) {
                return c;
            }
            return ma.toString().compareTo(mb.toString());
        }

        if (a instanceof Enum && b instanceof Enum) {
            Enum<?> ea = (Enum<?>) a;
            Enum<?> eb = (Enum<?>) b;
            int c = ea.getDeclaringClass().getName().compareTo(eb.getDeclaringClass().getName());
            if (c != 0) {
                return c;
            }
            return ea.name().compareTo(eb.name());
        }

        if (a instanceof CharSequence && b instanceof CharSequence) {
            return a.toString().compareTo(b.toString());
        }

        if (a.getClass().equals(b.getClass()) && a instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }

        return a.toString().compareTo(b.toString());
    }

    /**
     * <p>
     * choice.
     * </p>
     *
     * @param elements a T object.
     * @param <T>      a T object.
     * @return a T object or <code>null</code> if <code>elements.length</code> is zero.
     */
    public static <T> T choice(T... elements) {
        if (elements.length == 0) {
            return null;
        }

        int position = random.nextInt(elements.length);
        return elements[position];
    }

    /**
     * <p>
     * shuffle.
     * </p>
     *
     * @param list a {@link java.util.List} object.
     */
    public static void shuffle(List<?> list) {
        Collections.shuffle(list, random);
    }

    /**
     * <p>
     * nextString.
     * </p>
     *
     * @param length a int.
     * @return a {@link java.lang.String} object.
     */
    public static String nextString(int length) {
        char[] characters = new char[length];
        for (int i = 0; i < length; i++) {
            characters[i] = nextChar();
        }
        return new String(characters);
    }
}
