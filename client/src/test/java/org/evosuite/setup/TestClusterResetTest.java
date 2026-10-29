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

import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestClusterResetTest {

    @AfterEach
    public void resetCluster() {
        TestCluster.reset();
    }

    @Test
    public void resetDiscardsAllClusterState() throws Exception {
        TestCluster.reset();
        TestCluster first = TestCluster.getInstance();
        first.addTestCall(new GenericMethod(String.class.getMethod("length"), String.class));
        first.getAnalyzedClasses().add(String.class);
        assertEquals(1, first.getNumTestCalls());

        TestCluster.reset();
        TestCluster second = TestCluster.getInstance();

        assertNotSame(first, second);
        assertEquals(0, second.getNumTestCalls());
        assertTrue(second.getAnalyzedClasses().isEmpty());
    }
}
