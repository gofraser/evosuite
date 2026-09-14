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
     * True once the resolver has committed to building the current dependency chain with real
     * (non-mock) objects. See {@link #withConcreteChain()} for why this exists and how it is
     * used.
     */
    private final boolean concreteChain;

    /**
     * Constructor for fresh context.
     */
    public GenerationContext() {
        this(Collections.emptySet(), 0, false);
    }

    /**
     * Create a context with given depth and empty visited set.
     * @param depth the depth.
     * @return a new context.
     */
    public static GenerationContext fromDepth(int depth) {
        return new GenerationContext(Collections.emptySet(), depth, false);
    }

    private GenerationContext(Set<GenericAccessibleObject<?>> visited, int depth, boolean concreteChain) {
        this.visited = Collections.unmodifiableSet(new LinkedHashSet<>(visited));
        this.depth = depth;
        this.concreteChain = concreteChain;
    }

    /**
     * Create a new context with increased depth. The concrete-chain flag is carried along
     * unchanged, since going one level deeper never undoes a commitment made higher up.
     *
     * @return a new context.
     */
    public GenerationContext deeper() {
        return new GenerationContext(visited, depth + 1, concreteChain);
    }

    /**
     * Create a new context with added visited object. The concrete-chain flag is carried along
     * unchanged.
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
        return new GenerationContext(nextVisited, depth, concreteChain);
    }

    /**
     * Create a new context with the concrete-chain flag set. VariableResolver.createObject()
     * calls this when it resolves a deep, non-functional interface/abstract dependency to a real
     * generator instead of a mock: everything built to satisfy THAT generator's own parameters
     * (depth+1, depth+2, ...) should keep being built concretely too, rather than each level
     * re-rolling its own independent mock/concrete coin. See the comment on effectiveMockProb in
     * VariableResolver for the full rationale.
     *
     * @return a new context with concreteChain set to true.
     */
    public GenerationContext withConcreteChain() {
        if (concreteChain) {
            return this;
        }
        return new GenerationContext(visited, depth, true);
    }

    /**
     * Return true if this context is inside a dependency chain that the resolver has already
     * committed to building with real objects rather than mocks.
     *
     * @return true if inside a concrete chain.
     */
    public boolean isConcreteChain() {
        return concreteChain;
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
