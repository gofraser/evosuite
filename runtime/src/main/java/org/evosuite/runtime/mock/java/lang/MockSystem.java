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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.Console;

/**
 * Deterministic replacement hooks for {@link java.lang.System}.
 */
public class MockSystem implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return java.lang.System.class.getName();
    }

    /**
     * Replacement for {@link java.lang.System#console()}.
     *
     * @return the console
     */
    public static Console console() {
        if (!MockFramework.isEnabled()) {
            return java.lang.System.console();
        }
        // Deterministic policy: no real console device in unit tests.
        return null;
    }
}
