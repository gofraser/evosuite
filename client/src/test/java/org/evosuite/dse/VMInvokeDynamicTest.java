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
package org.evosuite.dse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Listener failures during INVOKEDYNAMIC dispatch follow the same policy as
 * every other callback.
 */
public class VMInvokeDynamicTest {

    @AfterEach
    public void resetVm() {
        VM.clearInstance();
        VM.enableCallBacks();
    }

    @Test
    public void stopSignalStopsVmAndCleansUp() {
        IVM listener = mock(IVM.class);
        doThrow(new org.evosuite.symbolic.ConstraintTooLongException(1)).when(listener).INVOKEDYNAMIC(any(), anyString());
        VM.getInstance().setListeners(Collections.singletonList(listener));

        VM.INVOKEDYNAMIC(new Object(), "Owner");

        assertTrue(VM.getInstance().isStopped());
        verify(listener).cleanUp();

        // the listeners were dropped and callbacks are enabled again
        VM.INVOKEDYNAMIC(new Object(), "Owner");
        verify(listener, times(1)).INVOKEDYNAMIC(any(), anyString());
    }

    @Test
    public void otherFailureIsReported() {
        IVM listener = mock(IVM.class);
        doThrow(new IllegalStateException("boom")).when(listener)
                .INVOKEDYNAMIC(anyString(), anyString(), anyString());
        VM.getInstance().setListeners(Collections.singletonList(listener));

        assertThrows(VMError.class, () -> VM.INVOKEDYNAMIC("a", "Owner", "recipe"));

        assertFalse(VM.getInstance().isStopped());
        verify(listener).cleanUp();
    }
}
