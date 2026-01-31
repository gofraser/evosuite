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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ObjectPoolTest {

    @Test
    public void testAddAndGetSequence() {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);
        TestCase testCase = new DefaultTestCase(); // minimal test case

        pool.addSequence(clazz, testCase);

        assertTrue(pool.hasSequence(clazz));
        assertEquals(1, pool.getNumberOfClasses());
        assertEquals(1, pool.getNumberOfSequences());

        TestCase retrieved = pool.getRandomSequence(clazz);
        assertNotNull(retrieved);
        // Equality might not be implemented for TestCase, so we check if it is the same object or we rely on logic
        // getRandomSequence returns one of the added sequences.
    }

    @Test
    public void testSerialization() throws IOException {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);
        TestCase testCase = new DefaultTestCase();
        pool.addSequence(clazz, testCase);

        File tempFile = File.createTempFile("objectpool", ".ser");
        tempFile.deleteOnExit();
        String path = tempFile.getAbsolutePath();

        pool.writePool(path);

        ObjectPool loadedPool = ObjectPool.getPoolFromFile(path);
        assertNotNull(loadedPool);
        assertTrue(loadedPool.hasSequence(clazz));
        assertEquals(1, loadedPool.getNumberOfSequences());
    }
}
