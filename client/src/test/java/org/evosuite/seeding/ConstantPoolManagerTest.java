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
package org.evosuite.seeding;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstantPoolManagerTest {

    private final boolean defaultRestrictPool = Properties.RESTRICT_POOL;
    private final int defaultMaxInt = Properties.MAX_INT;
    private final long defaultMaxSeededConstant = Properties.MAX_SEEDED_CONSTANT;
    private final boolean defaultAdaptiveOnOom = Properties.ADAPTIVE_SEEDED_CONSTANTS_ON_OOM;
    private final long defaultAdaptiveInitial = Properties.ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT;
    private final long defaultAdaptiveMin = Properties.ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT;

    @AfterEach
    public void restoreProperties() {
        Properties.RESTRICT_POOL = defaultRestrictPool;
        Properties.MAX_INT = defaultMaxInt;
        Properties.MAX_SEEDED_CONSTANT = defaultMaxSeededConstant;
        Properties.ADAPTIVE_SEEDED_CONSTANTS_ON_OOM = defaultAdaptiveOnOom;
        Properties.ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT = defaultAdaptiveInitial;
        Properties.ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT = defaultAdaptiveMin;
        ConstantPoolManager.getInstance().reset();
    }

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

    @Test
    public void testShouldKeepNumericConstantWhenUnrestricted() {
        Properties.RESTRICT_POOL = false;
        Properties.MAX_SEEDED_CONSTANT = 0L;

        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(Integer.MIN_VALUE));
        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(Long.MIN_VALUE));
        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(1.0e30d));
    }

    @Test
    public void testShouldKeepNumericConstantWithRestrictPool() {
        Properties.RESTRICT_POOL = true;
        Properties.MAX_INT = 2048;

        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(2047));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(2048));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(Integer.MIN_VALUE));

        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(2047L));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(2048L));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(Long.MIN_VALUE));

        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(2047.0d));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(2048.0d));
    }

    @Test
    public void testShouldKeepNumericConstantWithSeededCapOnly() {
        Properties.RESTRICT_POOL = false;
        Properties.MAX_SEEDED_CONSTANT = 1000L;

        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(999));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(1000));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(-1000L));
        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(999.9f));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(1000.0f));
    }

    @Test
    public void testAdaptiveSeedLimitAfterOomTightensAndPrunes() {
        Properties.RESTRICT_POOL = false;
        Properties.MAX_SEEDED_CONSTANT = 0L;
        Properties.ADAPTIVE_SEEDED_CONSTANTS_ON_OOM = true;
        Properties.ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT = 1000L;
        Properties.ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT = 100L;

        ConstantPoolManager manager = ConstantPoolManager.getInstance();
        manager.reset();
        manager.addDynamicConstant(5000);
        manager.addDynamicConstant(10);

        assertTrue(manager.getDynamicConstantPool().toString().contains("5000"));

        ConstantPoolManager.AdaptiveSeedLimitUpdate first = manager.tightenSeededNumericLimitAfterOom();
        assertTrue(first.isEnabled());
        assertTrue(first.getLimit() == 1000L);
        assertFalse(manager.getDynamicConstantPool().toString().contains("5000"));
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(5000));
        assertTrue(ConstantPoolManager.shouldKeepNumericConstant(999));

        ConstantPoolManager.AdaptiveSeedLimitUpdate second = manager.tightenSeededNumericLimitAfterOom();
        assertTrue(second.getLimit() == 500L);
        assertFalse(ConstantPoolManager.shouldKeepNumericConstant(600));
    }

    @Test
    public void testSanitizeNumericLiteralsAfterAdaptiveLimit() {
        Properties.RESTRICT_POOL = false;
        Properties.MAX_SEEDED_CONSTANT = 0L;
        Properties.ADAPTIVE_SEEDED_CONSTANTS_ON_OOM = true;
        Properties.ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT = 1000L;
        Properties.ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT = 100L;

        ConstantPoolManager manager = ConstantPoolManager.getInstance();
        manager.reset();
        manager.tightenSeededNumericLimitAfterOom();

        DefaultTestCase testCase = new DefaultTestCase();
        IntPrimitiveStatement statement = new IntPrimitiveStatement(testCase, 5000);
        testCase.addStatement(statement);

        int changed = ConstantPoolManager.sanitizeTestCaseNumericLiterals(testCase);
        assertTrue(changed == 1);
        assertTrue(statement.getValue() == 999);
    }
}
