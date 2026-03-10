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
package org.evosuite.runtime.mock;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class MockFrameworkTest {

    @Test
    void isEnabledShouldNotRecurseWhenPropertyLookupReentersMockFramework() {
        boolean initialState = MockFramework.isEnabled();
        Properties original = java.lang.System.getProperties();

        try {
            java.lang.System.setProperties(new Properties(original) {
                @Override
                public String getProperty(String key) {
                    if ("evosuite.mock.enabled".equals(key)) {
                        // Simulates app servers that re-enter during property lookup.
                        return MockFramework.isEnabled() ? "true" : "false";
                    }
                    return super.getProperty(key);
                }
            });

            boolean resolved = MockFramework.isEnabled();
            Assertions.assertEquals(initialState, resolved);
        } finally {
            java.lang.System.setProperties(original);
            if (initialState) {
                MockFramework.enable();
            } else {
                MockFramework.disable();
            }
        }
    }
}
