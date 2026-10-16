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
package com.examples.with.different.packagename.enumcollision.two;

/**
 * Deliberately shares its simple name, {@code Outer}, with
 * {@link com.examples.with.different.packagename.enumcollision.one.Outer}.
 * Constructing an instance of <em>this</em> class first, in the same
 * generated test, forces {@code TestCodeVisitor} to claim the unqualified
 * name {@code Outer} for this class, so the other {@code Outer}'s nested enum
 * constant can no longer be rendered as a bare {@code Outer.E.X} -- it must
 * be qualified instead.
 */
public class Outer {
}
