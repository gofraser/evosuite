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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public class MethodCallReplacementCacheTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testStaticReplacementRequiresMethodInMockClass() throws Exception {
        Method console = java.lang.System.class.getMethod("console");
        String consoleKey = console.getName() + Type.getMethodDescriptor(console);

        Method arraycopy = java.lang.System.class.getMethod(
                "arraycopy",
                Object.class,
                int.class,
                Object.class,
                int.class,
                int.class);
        String arraycopyKey = arraycopy.getName() + Type.getMethodDescriptor(arraycopy);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/System", consoleKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/lang/System", arraycopyKey));
    }
}
