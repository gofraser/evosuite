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
package com.examples.with.different.packagename.enumcollision.one;

/**
 * Deliberately shares its simple name, {@code Outer}, with
 * {@link com.examples.with.different.packagename.enumcollision.two.Outer} in a
 * different package. Declares a public, perfectly nameable nested enum whose
 * constant is reached through an {@code Object}-typed accessor, so the
 * generated test must both import {@code Outer} (this one, not the other) and
 * resolve the simple-name clash between the two -- see
 * {@link com.examples.with.different.packagename.enumcollision.two.Outer} for
 * the fixture that forces the collision.
 */
public class Outer {

    public enum E {
        X, Y
    }

    public Object get() {
        return E.X;
    }

    public static Object value() {
        return E.X;
    }
}
