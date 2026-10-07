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

/**
 * Types that must never be handed to {@code Mockito.mock}, whichever code path creates the
 * mock: functional mock statements, lenient default answers (during the search and in
 * generated tests), and DMoN's ephemeral injection.
 */
public final class UnmockableTypes {

    private UnmockableTypes() {
    }

    /**
     * A Mockito mock of a ClassLoader is a ClassLoader subclass whose constructor never ran, so
     * its unnamed module is null. The first class load through it (for instance
     * {@code Class.forName(name, false, mock)}) fails a HotSpot guarantee in
     * {@code ModuleEntry::create_unnamed_module} and aborts the whole JVM with SIGABRT, which
     * no exception handler can contain.
     *
     * @param type the type a mock would be created for
     * @return true if no mock of {@code type} may be created
     */
    public static boolean isUnmockable(Class<?> type) {
        return type != null && ClassLoader.class.isAssignableFrom(type);
    }
}
