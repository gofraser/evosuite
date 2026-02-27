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

import org.evosuite.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAnonymousLocalClass {

    @Test
    public void testAnonymousClass() {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = "org.evosuite.setup.TestAnonymousLocalClass";
        Object o = new Object() {};
        boolean result = TestUsageChecker.canUse(o.getClass());
        Assertions.assertFalse(result);
    }

    @Test
    public void testLocalClass() {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = "org.evosuite.setup.TestAnonymousLocalClass";
        class Local {}
        boolean result = TestUsageChecker.canUse(Local.class);
        Assertions.assertFalse(result);
    }
}
