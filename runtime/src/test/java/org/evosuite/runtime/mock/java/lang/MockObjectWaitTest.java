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

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class MockObjectWaitTest {

    @Before
    public void setUp() {
        MockFramework.enable();
        MockObjectWait.reset();
    }

    @After
    public void tearDown() {
        MockFramework.disable();
        MockObjectWait.reset();
    }

    @Test
    public void testWaitReturnsFastWhenMocked() throws InterruptedException {
        Object lock = new Object();
        long start = java.lang.System.currentTimeMillis();
        synchronized (lock) {
            // Without mocking, wait() with no timeout would block forever.
            // The mock caps it to 50ms.
            MockObjectWait.wait(lock);
        }
        long elapsed = java.lang.System.currentTimeMillis() - start;
        // Should return within a reasonable time (capped to 50ms + scheduling).
        assertTrue("Expected fast return, but took " + elapsed + "ms", elapsed < 500);
    }

    @Test
    public void testWaitWithTimeoutIsCapped() throws InterruptedException {
        Object lock = new Object();
        long start = java.lang.System.currentTimeMillis();
        synchronized (lock) {
            // Request 10s wait — mock should cap to 50ms
            MockObjectWait.wait(lock, 10_000);
        }
        long elapsed = java.lang.System.currentTimeMillis() - start;
        assertTrue("Expected capped wait, but took " + elapsed + "ms", elapsed < 500);
    }

    @Test
    public void testCumulativeBudgetEnforced() {
        Object lock = new Object();
        boolean exceededBudget = false;
        synchronized (lock) {
            for (int i = 0; i < 1_000; i++) {
                try {
                    MockObjectWait.wait(lock);
                } catch (TooManyResourcesException e) {
                    exceededBudget = true;
                    break;
                } catch (InterruptedException e) {
                    fail("Unexpected InterruptedException");
                }
            }
        }
        assertTrue("Expected TooManyResourcesException after exhausting budget", exceededBudget);
    }

    @Test
    public void testResetClearsBudget() throws InterruptedException {
        Object lock = new Object();
        // Consume some budget
        synchronized (lock) {
            for (int i = 0; i < 50; i++) {
                MockObjectWait.wait(lock);
            }
        }
        // Reset should allow fresh budget
        MockObjectWait.reset();
        // Should not throw
        synchronized (lock) {
            MockObjectWait.wait(lock);
        }
    }

    @Test
    public void testNotifyDelegatesToReal() {
        Object lock = new Object();
        // Should not throw — notify on a held monitor is always safe
        synchronized (lock) {
            MockObjectWait.notify(lock);
        }
    }

    @Test
    public void testNotifyAllDelegatesToReal() {
        Object lock = new Object();
        synchronized (lock) {
            MockObjectWait.notifyAll(lock);
        }
    }

    @Test
    public void testDelegatesWhenFrameworkDisabled() throws InterruptedException {
        MockFramework.disable();
        Object lock = new Object();
        synchronized (lock) {
            // With framework disabled, wait(1) should call real Object.wait(1)
            // and return after ~1ms timeout
            MockObjectWait.wait(lock, 1);
        }
    }
}
