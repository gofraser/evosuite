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
package org.evosuite.runtime;

import org.evosuite.runtime.sandbox.MSecurityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvoAssertionsTest {

    @Test
    void assertThrownByIgnoresShadedRuntimeSystemWrapperFrame() {
        SecurityException ex = new SecurityException("Permission Denied");
        ex.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("shaded.org.evosuite.runtime.System",
                        "setSecurityManager", "System.java", 359),
                new StackTraceElement("shaded.org.evosuite.runtime.sandbox.MSecurityManager",
                        "checkPermission", "MSecurityManager.java", 1200)
        });

        assertDoesNotThrow(() -> EvoAssertions.assertThrownBy(MSecurityManager.class.getName(), ex));
    }

    @Test
    void assertThrownByStillFailsForUnrelatedTopFrame() {
        IllegalStateException ex = new IllegalStateException("boom");
        ex.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 427)
        });

        assertThrows(AssertionError.class,
                () -> EvoAssertions.assertThrownBy(MSecurityManager.class.getName(), ex));
    }
}
