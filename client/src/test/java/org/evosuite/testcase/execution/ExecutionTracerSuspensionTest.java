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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTracerSuspensionTest {

    private boolean wasEnabled;

    @BeforeEach
    void remember() {
        wasEnabled = ExecutionTracer.isEnabled();
    }

    @AfterEach
    void restore() {
        if (wasEnabled) {
            ExecutionTracer.enable();
        } else {
            ExecutionTracer.disable();
        }
    }

    @Test
    void enabledTracerIsDisabledInsideAndEnabledAfter() {
        ExecutionTracer.enable();
        try (ExecutionTracer.Suspension suspended = ExecutionTracer.disableTemporarily()) {
            assertFalse(ExecutionTracer.isEnabled());
        }
        assertTrue(ExecutionTracer.isEnabled());
    }

    @Test
    void disabledTracerStaysDisabled() {
        ExecutionTracer.disable();
        try (ExecutionTracer.Suspension suspended = ExecutionTracer.disableTemporarily()) {
            assertFalse(ExecutionTracer.isEnabled());
        }
        assertFalse(ExecutionTracer.isEnabled());
    }

    @Test
    void tracerIsEnabledAgainWhenTheBodyThrows() {
        ExecutionTracer.enable();
        assertThrows(IllegalStateException.class, () -> {
            try (ExecutionTracer.Suspension suspended = ExecutionTracer.disableTemporarily()) {
                throw new IllegalStateException();
            }
        });
        assertTrue(ExecutionTracer.isEnabled());
    }
}
