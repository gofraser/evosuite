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
 * Several fields declared in an order that does not match alphabetical order, used to pin that
 * {@link org.evosuite.setup.TestClusterUtils#getFields(Class)}'s iteration order is deterministic
 * (name-sorted, since the merge map is a {@code TreeMap<String, Field>}) rather than an accident
 * of declaration or hash order.
 */
public class MultiFieldOrderingExample {

    public int zebra;
    public int apple;
    public int mango;
    public int banana;
}
