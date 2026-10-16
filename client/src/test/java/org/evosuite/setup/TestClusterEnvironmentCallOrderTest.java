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
package org.evosuite.setup;

import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestClusterEnvironmentCallOrderTest {

    @AfterEach
    public void resetCluster() {
        TestCluster.reset();
    }

    @Test
    public void environmentCallsAreShuffledWithTheSearchSeed() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        for (String name : new String[]{"length", "isEmpty", "trim", "toLowerCase", "toUpperCase",
                "hashCode", "toString", "intern", "strip", "isBlank"}) {
            Method m = String.class.getMethod(name);
            cluster.addEnvironmentTestCall(new GenericMethod(m, String.class));
        }

        Randomness.setSeed(42);
        List<GenericAccessibleObject<?>> first = cluster.getRandomizedCallsToEnvironment();
        Randomness.setSeed(42);
        List<GenericAccessibleObject<?>> second = cluster.getRandomizedCallsToEnvironment();

        assertEquals(first.toString(), second.toString());
    }
}
