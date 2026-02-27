/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.setup;

import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import org.evosuite.utils.generic.GenericAccessibleObject;

import static org.junit.jupiter.api.Assertions.*;

public class RemoveDirectCycleTest {

    public static class Y {}

    public static class X {
        public X() {}
        public X(Y y) {}
        public Y getY() { return new Y(); }
    }

    @BeforeEach
    public void setUp() {
        TestCluster.reset();
    }

    @Test
    public void testRemoveDirectCycleAggressiveness() throws Exception {
        TestCluster cluster = TestCluster.getInstance();

        GenericClass<?> typeX = GenericClassFactory.get(X.class);
        GenericClass<?> typeY = GenericClassFactory.get(Y.class);

        // Generators for X
        GenericConstructor xCtor1 = new GenericConstructor(X.class.getConstructor(), typeX);
        GenericConstructor xCtor2 = new GenericConstructor(X.class.getConstructor(Y.class), typeX);

        cluster.addGenerator(typeX, xCtor1);
        cluster.addGenerator(typeX, xCtor2);

        // Generator for Y: x.getY()
        GenericMethod yGen = new GenericMethod(X.class.getMethod("getY"), typeX);
        cluster.addGenerator(typeY, yGen);

        // Verification before clean up
        assertTrue(cluster.hasGenerator(typeX));
        assertTrue(cluster.hasGenerator(typeY));

        // Run cleanup
        cluster.removeUnusableGenerators();

        // Verification after clean up
        assertTrue(cluster.hasGenerator(typeX), "X should still have generators");

        // This is expected to FAIL with current buggy implementation
        assertTrue(cluster.hasGenerator(typeY), "Y should still have generator because X has a no-arg constructor");
    }
}
