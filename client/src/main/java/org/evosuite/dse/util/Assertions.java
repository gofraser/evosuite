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
package org.evosuite.dse.util;

/*
    This class is taken and adapted from the DSC tool developed by Christoph Csallner.
    Link at :
    http://ranger.uta.edu/~csallner/dsc/index.html
 */

/**
 * Makes assertion checking more elegant than explicit if statements. Avoids the
 * -disableassertions problem of Java's assert statements.
 *
 * @author csallner@uta.edu (Christoph Csallner)
 */
public class Assertions {

    /**
     * Checks if the given object is not null.
     *
     * @param t the object to check
     * @param <T> the type of the object
     * @return the object if it is not null
     * @throws NullPointerException if the object is null
     */
    public static <T> T notNull(final T t) {
        if (t == null) {
            NullPointerException npe = new NullPointerException();
            npe.printStackTrace();
            throw npe;
        }

        return t;
    }

    /**
     * Checks if two given objects are not null.
     *
     * @param a first object to check
     * @param b second object to check
     */
    public static void notNull(final Object a, final Object b) {
        notNull(a);
        notNull(b);
    }

    /**
     * Checks if three given objects are not null.
     *
     * @param a first object to check
     * @param b second object to check
     * @param c third object to check
     */
    public static void notNull(final Object a, final Object b, final Object c) {
        notNull(a);
        notNull(b);
        notNull(c);
    }

    /**
     * Checks if the given integer is not negative.
     *
     * @param i the integer to check
     * @return the integer if it is not negative
     * @throws IndexOutOfBoundsException if the integer is negative
     */
    public static int notNegative(final int i) {
        if (i < 0) {
            IndexOutOfBoundsException e = new IndexOutOfBoundsException();
            e.printStackTrace();
            throw e;
        }

        return i;
    }

    /**
     * Checks if two given integers are not negative.
     *
     * @param a first integer to check
     * @param b second integer to check
     */
    public static void notNegative(final int a, final int b) {
        notNegative(a);
        notNegative(b);
    }

    /**
     * Checks if a boolean condition holds.
     *
     * <p>Checks if b holds. Call this method to check assertions like
     * pre- and post-conditions.</p>
     *
     * @param b the condition to check
     * @throws IllegalStateException if the condition is false
     */
    public static void check(final boolean b) {
        check(b, "");
    }

    /**
     * Checks if a boolean condition holds and wraps a throwable.
     *
     * @param b the condition to check
     * @param t the throwable to wrap in case the condition is false
     * @throws IllegalStateException if the condition is false
     */
    public static void check(final boolean b, Throwable t) {
        if (!b) {
            IllegalStateException ise = new IllegalStateException(t);
            throw ise;
        }
    }

    /**
     * Checks if a boolean condition holds and provides an error message.
     *
     * @param b the condition to check
     * @param msg for exception, in case b==false
     * @throws IllegalStateException if the condition is false
     */
    public static void check(final boolean b, String msg) {
        if (!b) {
            IllegalStateException ise = new IllegalStateException(msg);
            throw ise;
        }
    }
}
