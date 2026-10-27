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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by gordon on 06/01/2017.
 */
public class TestVariableConstantPool {

    @Test
    public void testBasicProbabilities() {
        ConstantPool pool1 = ConstantPoolManager.newStaticPool(true);
        ConstantPool pool2 = ConstantPoolManager.newStaticPool(true);
        for (int i = 0; i < 99; i++) {
            pool1.add("Foo");
            pool2.add("Bar");
        }
        pool1.add("Bar");
        pool2.add("Foo");
        int count1 = 0;
        int count2 = 0;
        for (int i = 0; i < 100; i++) {
            if (pool1.getRandomString().equals("Bar"))
                count1++;
            if (pool2.getRandomString().equals("Bar"))
                count2++;
        }
        assertTrue(count1 < count2);
    }

    @Test
    public void testBasicProbabilitiesDynamic() {
        ConstantPool pool1 = ConstantPoolManager.newDynamicPool(true);
        ConstantPool pool2 = ConstantPoolManager.newDynamicPool(true);
        for (int i = 0; i < 99; i++) {
            pool1.add("Foo");
            pool2.add("Bar");
        }
        pool1.add("Bar");
        pool2.add("Foo");
        int count1 = 0;
        int count2 = 0;
        for (int i = 0; i < 100; i++) {
            if (pool1.getRandomString().equals("Bar"))
                count1++;
            if (pool2.getRandomString().equals("Bar"))
                count2++;
        }
        assertTrue(count1 < count2);
    }


    @Test
    public void testBasicProbabilitiesDynamicUpdate() {
        ConstantPool pool = ConstantPoolManager.newDynamicPool(true);
        for (int i = 0; i < 99; i++) {
            pool.add("Foo");
        }
        pool.add("Bar");
        int count1 = 0;
        for (int i = 0; i < 100; i++) {
            if (pool.getRandomString().equals("Bar"))
                count1++;
        }

        for (int i = 0; i < 99; i++) {
            pool.add("Bar");
        }

        int count2 = 0;
        for (int i = 0; i < 100; i++) {
            if (pool.getRandomString().equals("Bar"))
                count2++;
        }
        assertTrue(count1 < count2);
    }
}
