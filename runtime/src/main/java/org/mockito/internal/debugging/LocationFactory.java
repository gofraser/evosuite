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
package org.mockito.internal.debugging;

import org.mockito.invocation.Location;

/**
 * Drop-in replacement for Mockito's {@code LocationFactory} that skips the
 * expensive {@link java.lang.StackWalker} call on every mock invocation.
 *
 * <p>The default Mockito implementation captures a full stack trace each time a
 * mock method is called, purely for diagnostic messages in verification
 * failures.  EvoSuite never uses Mockito verification, so this information is
 * wasted — and when a mock is called in a tight loop (e.g.&nbsp;a mocked
 * {@code Reader} fed to {@code StreamTokenizer}), millions of stack walks
 * completely stall the test execution thread.
 *
 * <p>This class is compiled into the {@code org.mockito.internal.debugging}
 * package so it shadows the original at the class-loader level.  During
 * shading it becomes {@code org.evosuite.shaded.org.mockito.internal.debugging
 * .LocationFactory}, replacing the version from the Mockito jar.
 */
public final class LocationFactory {

    private static final Location DUMMY = new Location() {
        @Override
        public String toString() {
            return "-> at <evosuite-generated test>(Unknown Source)";
        }

        @Override
        public String getSourceFile() {
            return "Unknown Source";
        }
    };

    private LocationFactory() {
    }

    public static Location create() {
        return DUMMY;
    }

    public static Location create(boolean withSource) {
        return DUMMY;
    }
}
