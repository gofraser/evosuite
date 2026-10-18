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
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class LlmFactoryWrappersTest {

    private boolean seedInitialPopulation;
    private boolean testFactory;
    private Properties.TestFactory selectedFactory;

    @BeforeEach
    void save() {
        seedInitialPopulation = Properties.LLM_SEED_INITIAL_POPULATION;
        testFactory = Properties.LLM_TEST_FACTORY;
        selectedFactory = Properties.TEST_FACTORY;
    }

    @AfterEach
    void restore() {
        Properties.LLM_SEED_INITIAL_POPULATION = seedInitialPopulation;
        Properties.LLM_TEST_FACTORY = testFactory;
        Properties.TEST_FACTORY = selectedFactory;
    }

    @Test
    void returnsTheBaseFactoryWhenNoLlmFactoryIsSelected() {
        Properties.LLM_SEED_INITIAL_POPULATION = false;
        Properties.LLM_TEST_FACTORY = false;
        Properties.TEST_FACTORY = Properties.TestFactory.RANDOM;
        ChromosomeFactory<TestChromosome> base = new RandomLengthTestFactory();

        assertSame(base, LlmFactoryWrappers.wrap(base));
    }

    @Test
    void wrapsInTheLlmTestFactoryWhenSelected() {
        Properties.LLM_SEED_INITIAL_POPULATION = false;
        Properties.LLM_TEST_FACTORY = true;
        ChromosomeFactory<TestChromosome> base = new RandomLengthTestFactory();

        assertInstanceOf(LlmTestChromosomeFactory.class, LlmFactoryWrappers.wrap(base));
    }

    @Test
    void wrapsInTheLlmTestFactoryWhenTheTestFactoryIsLlm() {
        Properties.LLM_SEED_INITIAL_POPULATION = false;
        Properties.LLM_TEST_FACTORY = false;
        Properties.TEST_FACTORY = Properties.TestFactory.LLM;

        assertInstanceOf(LlmTestChromosomeFactory.class,
                LlmFactoryWrappers.wrap(new RandomLengthTestFactory()));
    }
}
