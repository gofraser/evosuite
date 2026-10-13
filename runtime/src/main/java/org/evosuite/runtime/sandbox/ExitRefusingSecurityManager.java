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
package org.evosuite.runtime.sandbox;

import java.security.Permission;

/**
 * Stands in for the manager the sandbox replaced while the sandbox is down and
 * {@link Sandbox#isJvmExitRefused()} holds: it refuses to let any thread exit the JVM, and
 * otherwise defers to the manager it stands in for, if any.
 */
final class ExitRefusingSecurityManager extends SecurityManager {

    private final SecurityManager replacedManager;

    ExitRefusingSecurityManager(SecurityManager replacedManager) {
        this.replacedManager = replacedManager;
    }

    /**
     * @return the manager this one stands in for, null if there was none
     */
    SecurityManager getReplacedManager() {
        return replacedManager;
    }

    @Override
    public void checkPermission(Permission perm) {
        refuseExit(perm);
        if (replacedManager != null) {
            replacedManager.checkPermission(perm);
        }
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        refuseExit(perm);
        if (replacedManager != null) {
            replacedManager.checkPermission(perm, context);
        }
    }

    private static void refuseExit(Permission perm) {
        if (MSecurityManager.isJvmExitRefused() && MSecurityManager.isExitPermission(perm)) {
            throw new SecurityException("Exiting the JVM is refused: " + perm);
        }
    }
}
