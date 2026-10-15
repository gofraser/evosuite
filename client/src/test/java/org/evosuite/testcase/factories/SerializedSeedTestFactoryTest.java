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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class SerializedSeedTestFactoryTest {

    @TempDir
    Path tmp;

    @AfterEach
    public void reset() {
        Properties.CTG_SEEDS_FILE_IN = null;
    }

    @Test
    public void seedsComeFirstThenTheDelegate() {
        TestChromosome fromDelegate = test();
        ChromosomeFactory<TestChromosome> delegate = () -> fromDelegate;
        TestChromosome first = test();
        TestChromosome second = test();

        SerializedSeedTestFactory factory = new SerializedSeedTestFactory(delegate, Arrays.asList(first, second));

        assertSame(second, factory.getChromosome());
        assertSame(first, factory.getChromosome());
        assertEquals(0, factory.remainingSeeds());
        assertSame(fromDelegate, factory.getChromosome());
    }

    @Test
    public void withoutSeedsTheFactoryIsUnchanged() {
        ChromosomeFactory<TestChromosome> delegate = SerializedSeedTestFactoryTest::test;

        assertSame(delegate, SerializedSeedTestFactory.withCtgSeeds(delegate));

        Properties.CTG_SEEDS_FILE_IN = tmp.resolve("Missing.seed").toString();
        assertSame(delegate, SerializedSeedTestFactory.withCtgSeeds(delegate));
    }

    private static TestChromosome test() {
        TestChromosome test = new TestChromosome();
        test.setTestCase(new DefaultTestCase());
        return test;
    }
}
