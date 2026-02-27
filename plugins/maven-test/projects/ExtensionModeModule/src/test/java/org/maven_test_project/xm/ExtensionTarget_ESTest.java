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
package org.maven_test_project.xm;

import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EvoRunnerParameters
public class ExtensionTarget_ESTest {

    @RegisterExtension
    static EvoSuiteExtension runner = new EvoSuiteExtension(ExtensionTarget_ESTest.class);

    @Test
    public void testTwice() {
        assertEquals(10, ExtensionTarget.twice(5));
    }

    @Test
    public void testExtensionEnvTargetClassIsCovered() {
        ExtensionEnvTarget target = new ExtensionEnvTarget();
        assertNotNull(target);
        assertFalse(ExtensionEnvTarget.check());
    }

    @Test
    public void testExtensionProfileTargetClassIsCovered() {
        assertEquals(0, ExtensionProfileTarget.clampToNonNegative(-7));
        assertEquals(5, ExtensionProfileTarget.clampToNonNegative(5));
    }
}
