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
package org.evosuite.graphs;

import java.util.Map;

/**
 * Supplies the Graphviz attributes (shape, colour, style, ...) with which a vertex or an
 * edge is rendered by {@link DotWriter}.
 *
 * @param <T> the vertex or edge type
 */
@FunctionalInterface
public interface ComponentAttributeProvider<T> {

    /**
     * Returns the attributes of the given component; a {@code label} entry is used as
     * the label when no other label is given.
     *
     * @param component the vertex or edge
     * @return the attribute names and values, or {@code null} for none
     */
    Map<String, String> getComponentAttributes(T component);
}
