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
package org.evosuite.eclipse.launch;

/** What a run does with a test suite that already exists for a class. */
public enum SuiteAction {

    /** EvoSuite writes the new suite over the existing one. */
    REPLACE,

    /**
     * The tests of the new suite that the existing one lacks are added to it; the existing tests
     * stay as they are. Falls back to {@link #COMPARE} when the suites cannot be merged.
     */
    ADD,

    /**
     * The existing suite and the new one open side by side in a compare editor, where the user
     * takes over what they want; the existing file changes only when they save.
     */
    COMPARE
}
