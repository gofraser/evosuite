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
package com.examples.with.different.packagename;

/**
 * A <em>public</em> nested enum whose constants have their own class bodies
 * (so each constant's runtime class is an anonymous subclass, unlike
 * {@link PrivateNestedEnumFixture.Status}), reached through an accessor
 * declared to return {@code Object}. Unlike {@link PrivateNestedEnumFixture},
 * {@code Mode} itself is perfectly nameable -- this fixture isolates the
 * {@code Class#isEnum()} vs {@code instanceof Enum} distinction (false for a
 * constant-specific-body instance) from the private-nested-enum case.
 */
public class NestedConstantBodyEnumFixture {

    public enum Mode {
        FAST {
            @Override
            int weight() {
                return 1;
            }
        },
        SLOW {
            @Override
            int weight() {
                return 2;
            }
        };

        abstract int weight();
    }

    private Mode mode = Mode.FAST;

    public Object mode() {
        return mode;
    }

    public static Object fastMode() {
        return Mode.FAST;
    }
}
