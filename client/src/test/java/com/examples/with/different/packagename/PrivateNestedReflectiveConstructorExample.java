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
 * Mirrors {@code com.eteks.sweethome3d.swing.IconManager.IconProxy}: a private
 * static nested class with a private, parameterized constructor, implementing a
 * public interface declared by its public enclosing class. A generated test can
 * only reach {@link PrivateProxy} reflectively, but the interface it implements
 * ({@link Proxy}) is a legitimate declared/cast type for the resulting variable.
 */
public class PrivateNestedReflectiveConstructorExample {

    public interface Proxy {
        String identify();
    }

    public static class ProxyHolder {
        public String describe(Proxy proxy) {
            return proxy.identify();
        }
    }

    private static class PrivateProxy implements Proxy {
        private final String label;

        private PrivateProxy(String label) {
            this.label = label;
        }

        @Override
        public String identify() {
            return label;
        }
    }
}
