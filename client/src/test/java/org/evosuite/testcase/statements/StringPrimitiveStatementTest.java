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
package org.evosuite.testcase.statements;

import org.evosuite.Properties;
import org.evosuite.seeding.ConstantPool;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;

class StringPrimitiveStatementTest {

    @Test
    void randomizeShouldHandleNullCandidateFromConstantPool() throws Exception {
        ConstantPoolManager manager = ConstantPoolManager.getInstance();
        Field poolsField = ConstantPoolManager.class.getDeclaredField("pools");
        Field probabilitiesField = ConstantPoolManager.class.getDeclaredField("probabilities");
        poolsField.setAccessible(true);
        probabilitiesField.setAccessible(true);

        ConstantPool[] originalPools = ((ConstantPool[]) poolsField.get(manager)).clone();
        double[] originalProbabilities = ((double[]) probabilitiesField.get(manager)).clone();
        double originalPrimitivePool = Properties.PRIMITIVE_POOL;

        ConstantPool nullStringPool = new ConstantPool() {
            @Override
            public String getRandomString() {
                return null;
            }

            @Override
            public Type getRandomType() {
                return Type.getType(Object.class);
            }

            @Override
            public int getRandomInt() {
                return 0;
            }

            @Override
            public float getRandomFloat() {
                return 0.0f;
            }

            @Override
            public double getRandomDouble() {
                return 0.0;
            }

            @Override
            public long getRandomLong() {
                return 0L;
            }

            @Override
            public void add(Object object) {
                // No-op for this test.
            }
        };

        try {
            Properties.PRIMITIVE_POOL = 1.0;
            poolsField.set(manager, new ConstantPool[]{nullStringPool});
            probabilitiesField.set(manager, new double[]{1.0});

            TestCase testCase = new DefaultTestCase();
            StringPrimitiveStatement statement = new StringPrimitiveStatement(testCase);

            statement.randomize();

            Assertions.assertNotNull(statement.getValue());
        } finally {
            Properties.PRIMITIVE_POOL = originalPrimitivePool;
            poolsField.set(manager, originalPools);
            probabilitiesField.set(manager, originalProbabilities);
        }
    }
}
