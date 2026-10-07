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
package org.evosuite.testcase.accessfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * A generic class with a private inner (non-static) class that a package-private method
 * returns, the shape of Closure's {@code DisambiguateProperties<T>.Property}.
 */
public class PropertyTable<T> {

    private final Map<String, Property> properties = new HashMap<>();

    private class Property {
        private final String name;

        Property(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    Property getProperty(String name) {
        return properties.computeIfAbsent(name, Property::new);
    }

    int size() {
        return properties.size();
    }
}
