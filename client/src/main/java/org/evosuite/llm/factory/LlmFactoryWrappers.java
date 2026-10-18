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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a search's base test factory with the LLM factories selected by the configuration.
 * With neither selected the base factory is returned unchanged.
 */
public final class LlmFactoryWrappers {

    private static final Logger logger = LoggerFactory.getLogger(LlmFactoryWrappers.class);

    private LlmFactoryWrappers() {
    }

    /**
     * Adds the LLM-seeded initial population and the LLM test factory around {@code baseFactory},
     * as selected by {@code llm_seed_initial_population}, {@code llm_test_factory} and
     * {@code test_factory}.
     */
    public static ChromosomeFactory<TestChromosome> wrap(ChromosomeFactory<TestChromosome> baseFactory) {
        ChromosomeFactory<TestChromosome> factory = baseFactory;
        if (Properties.LLM_SEED_INITIAL_POPULATION) {
            factory = new LlmSeededPopulationFactory(factory);
        }
        if (Properties.LLM_TEST_FACTORY || Properties.TEST_FACTORY == Properties.TestFactory.LLM) {
            logger.info("LLM test factory wraps fallback factory {}; fallback remains active",
                    factory.getClass().getSimpleName());
            factory = new LlmTestChromosomeFactory(factory);
        }
        return factory;
    }
}
