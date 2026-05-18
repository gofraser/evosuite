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
package org.evosuite.rmi;

import java.net.URLClassLoader;

/**
 * Test-only stub used by MasterClassLoaderBridge reflective lookups.
 */
public final class MasterClassLoader {

    private static volatile boolean masterProcess = false;
    private static volatile URLClassLoader instance = null;

    private MasterClassLoader() {
    }

    public static URLClassLoader getIfInitialized() {
        return instance;
    }

    public static boolean isMasterProcess() {
        return masterProcess;
    }

    public static void markMasterProcess() {
        masterProcess = true;
    }

    public static void clearForTest() {
        masterProcess = false;
        instance = null;
    }
}
