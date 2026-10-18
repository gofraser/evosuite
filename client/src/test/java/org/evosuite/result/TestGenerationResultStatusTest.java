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
package org.evosuite.result;

import org.junit.jupiter.api.Test;

import static org.evosuite.result.TestGenerationResult.Status.ERROR;
import static org.evosuite.result.TestGenerationResult.Status.SUCCESS;
import static org.evosuite.result.TestGenerationResult.Status.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestGenerationResultStatusTest {

    @Test
    void worseStatusFollowsSeverity() {
        assertEquals(TIMEOUT, TestGenerationResult.Status.worse(SUCCESS, TIMEOUT));
        assertEquals(ERROR, TestGenerationResult.Status.worse(ERROR, TIMEOUT));
        assertEquals(ERROR, TestGenerationResult.Status.worse(TIMEOUT, ERROR));
        assertEquals(SUCCESS, TestGenerationResult.Status.worse(SUCCESS, SUCCESS));
    }

    @Test
    void worseStatusIgnoresNull() {
        assertEquals(TIMEOUT, TestGenerationResult.Status.worse(null, TIMEOUT));
        assertEquals(TIMEOUT, TestGenerationResult.Status.worse(TIMEOUT, null));
        assertNull(TestGenerationResult.Status.worse(null, null));
    }
}
