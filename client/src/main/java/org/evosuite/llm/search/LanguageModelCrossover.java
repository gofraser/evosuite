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
 * MOSA-level LLM crossover operator. Probabilistically applies semantic
 * crossover via LLM; falls back gracefully if LLM is unavailable or fails.
 *
 * <p>When successful, offspring1 receives the recombined test case.
 */
public class LanguageModelCrossover {

    private static final Logger logger = LoggerFactory.getLogger(LanguageModelCrossover.class);

    private final LlmSemanticCrossover semanticCrossover;
    private final AtomicInteger appliedCount = new AtomicInteger(0);
    private final AtomicInteger fallbackCount = new AtomicInteger(0);

    public LanguageModelCrossover() {
        this(new LlmSemanticCrossover());
    }

    public LanguageModelCrossover(LlmSemanticCrossover semanticCrossover) {
        this.semanticCrossover = semanticCrossover;
    }

    /**
     * Attempt LLM semantic crossover with configured probability.
     *
     * @param offspring1 first offspring (will receive recombined test on success)
     * @param offspring2 second offspring (used as second parent context)
     * @param goals      uncovered goals for prompt context
     * @return true if LLM crossover was successfully applied;
     *         false if standard crossover should be applied instead
     */
    public boolean tryCrossover(TestChromosome offspring1,
                                TestChromosome offspring2,
                                Collection<TestFitnessFunction> goals) {
        return tryCrossoverWithResult(offspring1, offspring2, goals).isAppliedSemantic();
    }

    /**
     * Attempt LLM semantic crossover, returning rich metadata about
     * whether the semantic path was attempted, applied, or skipped.
     */
    public OperatorAttemptResult tryCrossoverWithResult(TestChromosome offspring1,
                                                        TestChromosome offspring2,
                                                        Collection<TestFitnessFunction> goals) {
        if (!Properties.LLM_OPERATOR_ENABLED) {
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.DISABLED);
        }
        if (Randomness.nextDouble() > Properties.LLM_CROSSOVER_PROBABILITY) {
            return OperatorAttemptResult.standardOnly(OperatorAttemptResult.SkipReason.PROBABILITY);
        }

        try {
            TestChromosome child = semanticCrossover.crossoverSemantically(
                    offspring1, offspring2, goals);
            if (child != null) {
                offspring1.setTestCase(child.getTestCase());
                offspring1.setChanged(true);
                appliedCount.incrementAndGet();
                return OperatorAttemptResult.semanticApplied();
            }
        } catch (Exception e) {
            logger.debug("LLM crossover failed unexpectedly: {}", e.getMessage());
        }
        fallbackCount.incrementAndGet();
        return OperatorAttemptResult.semanticFallback();
    }

    public int getAppliedCount() {
        return appliedCount.get();
    }

    public int getFallbackCount() {
        return fallbackCount.get();
    }
}
