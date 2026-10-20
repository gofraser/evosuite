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
package org.evosuite.testcase.accessfixture.other;

/** Public members whose values are of a private nested class. */
public class SecretHolder {
    private static class Secret implements Runnable {
        @Override
        public void run() {
        }
    }

    /** Its nearest accessible supertype is Serializable, which is not a collection. */
    private static class SecretBag extends java.util.AbstractList<String>
            implements java.io.Serializable {
        @Override
        public String get(int index) {
            throw new IndexOutOfBoundsException();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    public Secret secret = new Secret();

    public SecretBag bag = new SecretBag();

    public Object anything = new Secret();

    public Secret make() {
        return new Secret();
    }
}
