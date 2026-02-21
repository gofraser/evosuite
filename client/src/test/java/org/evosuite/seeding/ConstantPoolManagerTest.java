/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.seeding;

import org.evosuite.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstantPoolManagerTest {

    @Test
    public void testSingleton() {
        ConstantPoolManager instance1 = ConstantPoolManager.getInstance();
        ConstantPoolManager instance2 = ConstantPoolManager.getInstance();
        assertNotNull(instance1);
        assertTrue(instance1 == instance2);
    }

    @Test
    public void testGetConstantPool() {
        ConstantPoolManager manager = ConstantPoolManager.getInstance();
        manager.reset();
        ConstantPool pool = manager.getConstantPool();
        assertNotNull(pool);
    }

    @Test
    public void testAddConstants() {
        ConstantPoolManager manager = ConstantPoolManager.getInstance();
        manager.reset();

        manager.addSUTConstant(10);
        manager.addNonSUTConstant(20);
        manager.addDynamicConstant(30);

        // We can't easily verify they were added without inspecting the pools,
        // but we can ensure no exceptions are thrown.
        // Also the selection logic relies on randomness, so it is hard to deterministically test which pool we got.
    }
}
