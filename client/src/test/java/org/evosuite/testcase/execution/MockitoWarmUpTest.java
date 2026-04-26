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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MockitoWarmUpTest {

    @AfterEach
    public void tearDown() {
        MockitoWarmUp.resetForTests();
    }

    @Test
    public void testWarmUpInitializesMockitoOnce() {
        MockitoWarmUp.warmUp();
        MockitoWarmUp.warmUp();

        assertTrue(MockitoWarmUp.isWarmedUpForTests());
        assertEquals(1, MockitoWarmUp.getWarmUpExecutionCountForTests());

        LocalMockTarget mock = Mockito.mock(LocalMockTarget.class);
        assertNotNull(mock);
    }

    static class LocalMockTarget {
        String ping() {
            return "pong";
        }
    }
}
