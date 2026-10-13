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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("removal")
public class SandboxJvmExitRefusedTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported(),
                "Security Manager is not supported in this JVM (Java 24+)");
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());
        assertNull(System.getSecurityManager());
    }

    @AfterEach
    public void tearDown() {
        Sandbox.setJvmExitRefused(false);
        while (Sandbox.isSecurityManagerInitialized()) {
            Sandbox.resetDefaultSecurityManager();
        }
    }

    @Test
    public void privilegedThreadMayNotExitWhileRefused() {
        // the sandbox a generated test brings up makes the threads alive at that moment privileged
        Sandbox.initializeSecurityManagerForSUT();
        Sandbox.setJvmExitRefused(true);
        assertThrows(SecurityException.class, () -> System.getSecurityManager().checkExit(1));
    }

    @Test
    public void takingTheSandboxDownLeavesAManagerThatRefusesToExit() {
        Sandbox.setJvmExitRefused(true);
        Sandbox.initializeSecurityManagerForSUT();
        Sandbox.resetDefaultSecurityManager();

        SecurityManager standIn = System.getSecurityManager();
        assertNotNull(standIn, "no manager while the sandbox is down");
        assertThrows(SecurityException.class, () -> standIn.checkExit(1));
        // everything else is up to the manager it stands in for, here none
        standIn.checkPropertyAccess("user.dir");

        // a sandbox brought up again replaces the stand-in, and restores what it stood in for
        Sandbox.initializeSecurityManagerForSUT();
        Sandbox.setJvmExitRefused(false);
        Sandbox.resetDefaultSecurityManager();
        assertNull(System.getSecurityManager());
    }

    @Test
    public void lettingExitAgainRemovesTheStandIn() {
        Sandbox.setJvmExitRefused(true);
        Sandbox.initializeSecurityManagerForSUT();
        Sandbox.resetDefaultSecurityManager();
        assertSame(ExitRefusingSecurityManager.class, System.getSecurityManager().getClass());

        Sandbox.setJvmExitRefused(false);
        assertNull(System.getSecurityManager());
    }
}
