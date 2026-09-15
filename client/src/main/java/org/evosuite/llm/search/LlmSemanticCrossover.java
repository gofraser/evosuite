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
package org.evosuite.llm.search;

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * LLM-driven semantic crossover: asks the LLM to produce a recombination
 * of two parent tests that combines useful setup and behaviour from both.
 */
public class LlmSemanticCrossover extends LlmSemanticOperator {

    public LlmSemanticCrossover() {
        this(LlmService.getInstance());
    }

    public LlmSemanticCrossover(LlmService llmService) {
        super(llmService, LlmFeature.SEMANTIC_CROSSOVER);
    }

    public LlmSemanticCrossover(LlmService llmService, TargetedPromptFactory promptFactory) {
        super(llmService, LlmFeature.SEMANTIC_CROSSOVER, promptFactory);
    }

    /**
     * Attempt a semantic crossover of two parent test chromosomes, targeting
     * whichever goal is closest to being covered on {@code parent1} (see
     * {@link OperatorTarget#closestGoal}).
     *
     * @param parent1 first parent
     * @param parent2 second parent
     * @param goals   current goals for context
     * @return the recombined chromosome, or null if LLM crossover failed
     */
    public TestChromosome crossoverSemantically(TestChromosome parent1, TestChromosome parent2,
                                                Collection<TestFitnessFunction> goals) {
        if (parent2 == null) {
            return null;
        }
        OperatorTarget target = OperatorTarget.closestGoal(parent1, parent2, goals);
        if (target == null) {
            return null;
        }
        return crossover(target);
    }

    /** Recombines the target's parent and partner towards the target's goal. */
    public TestChromosome crossover(OperatorTarget target) {
        List<TestChromosome> result = apply(Collections.singletonList(target));
        return result.isEmpty() ? null : result.get(0);
    }
}
