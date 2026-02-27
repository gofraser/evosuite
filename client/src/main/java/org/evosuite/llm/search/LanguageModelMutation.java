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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MOSA-level LLM mutation operator. Probabilistically applies semantic
 * mutation via LLM; falls back gracefully if LLM is unavailable or fails.
 *
 * <p>This operator is additive: the caller should apply standard mutation
 * when this operator returns false.
 */
public class LanguageModelMutation {

    private static final Logger logger = LoggerFactory.getLogger(LanguageModelMutation.class);

    private final LlmSemanticMutation semanticMutation;
    private final AtomicInteger appliedCount = new AtomicInteger(0);
    private final AtomicInteger fallbackCount = new AtomicInteger(0);

    public LanguageModelMutation() {
        this(new LlmSemanticMutation());
    }

    public LanguageModelMutation(LlmSemanticMutation semanticMutation) {
        this.semanticMutation = semanticMutation;
    }

    /**
     * Attempt LLM semantic mutation with configured probability.
     *
     * @param offspring the offspring to potentially replace with LLM mutation
     * @param goals     uncovered goals for prompt context
     * @return true if LLM mutation was successfully applied (offspring is replaced);
     *         false if standard mutation should be applied instead
     */
    public boolean tryMutate(TestChromosome offspring,
                             Collection<TestFitnessFunction> goals) {
        if (!Properties.LLM_OPERATOR_ENABLED) {
            return false;
        }
        if (Randomness.nextDouble() > Properties.LLM_MUTATION_PROBABILITY) {
            return false;
        }

        try {
            TestChromosome mutant = semanticMutation.mutateSemantically(offspring, goals);
            if (mutant != null) {
                offspring.setTestCase(mutant.getTestCase());
                offspring.setChanged(true);
                appliedCount.incrementAndGet();
                return true;
            }
        } catch (Exception e) {
            logger.debug("LLM mutation failed unexpectedly: {}", e.getMessage());
        }
        fallbackCount.incrementAndGet();
        return false;
    }

    public int getAppliedCount() {
        return appliedCount.get();
    }

    public int getFallbackCount() {
        return fallbackCount.get();
    }
}
