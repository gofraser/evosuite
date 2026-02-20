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
package org.evosuite.testcase;

import org.evosuite.utils.generic.GenericAccessibleObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Context for test case generation, replacing class-level recursion state.
 *
 * @author Gordon Fraser
 */
public final class GenerationContext {

    private final Set<GenericAccessibleObject<?>> visited;

    private final int depth;

    /**
     * Constructor for fresh context.
     */
    public GenerationContext() {
        this(Collections.emptySet(), 0);
    }

    /**
     * Create a context with given depth and empty visited set.
     * @param depth the depth.
     * @return a new context.
     */
    public static GenerationContext fromDepth(int depth) {
        return new GenerationContext(Collections.emptySet(), depth);
    }

    private GenerationContext(Set<GenericAccessibleObject<?>> visited, int depth) {
        this.visited = Collections.unmodifiableSet(new LinkedHashSet<>(visited));
        this.depth = depth;
    }

    /**
     * Create a new context with increased depth.
     *
     * @return a new context.
     */
    public GenerationContext deeper() {
        return new GenerationContext(visited, depth + 1);
    }

    /**
     * Create a new context with added visited object.
     *
     * @param o the object to add.
     * @return a new context.
     */
    public GenerationContext withVisited(GenericAccessibleObject<?> o) {
        if (o == null) {
            return this;
        }
        Set<GenericAccessibleObject<?>> nextVisited = new LinkedHashSet<>(visited);
        nextVisited.add(o);
        return new GenerationContext(nextVisited, depth);
    }

    /**
     * Return true if the object has already been visited.
     *
     * @param o the object.
     * @return true if visited.
     */
    public boolean isVisited(GenericAccessibleObject<?> o) {
        return visited.contains(o);
    }

    /**
     * Return current recursion depth.
     *
     * @return depth.
     */
    public int getDepth() {
        return depth;
    }

    /**
     * Return set of visited objects.
     *
     * @return visited set.
     */
    public Set<GenericAccessibleObject<?>> getVisited() {
        return visited;
    }
}
