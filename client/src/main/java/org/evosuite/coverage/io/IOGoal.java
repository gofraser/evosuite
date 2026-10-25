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
package org.evosuite.coverage.io;

import org.objectweb.asm.Type;

/**
 * A goal of input or output coverage: a value category that a method argument or return value
 * should fall into.
 *
 * @param <G> the goal type, which is only comparable to goals of the same criterion
 */
public interface IOGoal<G extends IOGoal<G>> extends Comparable<G> {

    String getClassName();

    String getMethodName();

    Type getType();

    String getValueDescriptor();

    /**
     * Gets the numeric value that distances are computed from.
     *
     * @return the value, or null if the covered category has none
     */
    Number getNumericValue();

    /**
     * Checks if this goal refers to the same argument or return value as the other goal, ignoring
     * the value descriptor.
     *
     * @param other the other goal
     * @return true if both goals target the same value of the same method
     */
    boolean isSameArgument(G other);
}
