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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvoSuite registers a privileged helper thread for many short tasks (RMI notifications, JUnit
 * check runners). The set of privileged threads must not keep the ones that have terminated,
 * or it grows without bound and is copied into every new security manager.
 */
public class MSecurityManagerPrivilegedThreadsTest {

    @Test
    public void terminatedThreadsAreNotKept() throws InterruptedException {
        MSecurityManager manager = new MSecurityManager();
        for (int i = 0; i < 50; i++) {
            Thread t = new Thread(() -> { });
            manager.addPrivilegedThread(t);
            t.start();
            t.join();
        }

        Set<Thread> privileged = manager.getPrivilegedThreads();

        assertEquals(1, privileged.size());
        assertTrue(privileged.contains(Thread.currentThread()));
    }

    @Test
    public void threadRegisteredBeforeStartStaysPrivileged() throws InterruptedException {
        MSecurityManager manager = new MSecurityManager();
        Thread notYetStarted = new Thread(() -> { });
        manager.addPrivilegedThread(notYetStarted);

        Thread other = new Thread(() -> { });
        manager.addPrivilegedThread(other);
        other.start();
        other.join();
        manager.addPrivilegedThread(new Thread(() -> { }));

        assertTrue(manager.getPrivilegedThreads().contains(notYetStarted));
    }

    @Test
    public void terminatedThreadIsNotAdded() throws InterruptedException {
        MSecurityManager manager = new MSecurityManager();
        Thread t = new Thread(() -> { });
        t.start();
        t.join();

        manager.addPrivilegedThread(t);

        assertFalse(manager.getPrivilegedThreads().contains(t));
    }
}
