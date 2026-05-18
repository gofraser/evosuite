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
package org.evosuite.testparser.fixtures;

/**
 * Abstract class declaring one abstract method. Used to verify that the
 * parser preserves {@code new AbstractWithAbstractMethodTarget() { ... }}
 * when the anonymous body overrides every abstract method, but falls back
 * to a typed null when the body leaves an abstract method unimplemented.
 */
public abstract class AbstractWithAbstractMethodTarget {

    public abstract int compute(int input);

    public int doubled(int input) {
        return compute(input) * 2;
    }
}
