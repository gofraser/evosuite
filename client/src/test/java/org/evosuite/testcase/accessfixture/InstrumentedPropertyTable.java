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

import org.evosuite.runtime.instrumentation.InstrumentedClass;

/**
 * {@link PropertyTable} as instrumentation leaves it: the private inner class implements the
 * {@link InstrumentedClass} marker.
 */
public class InstrumentedPropertyTable<T> {

    private class Property implements InstrumentedClass {
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
        return new Property(name);
    }
}
