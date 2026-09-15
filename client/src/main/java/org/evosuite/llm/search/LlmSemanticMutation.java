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
 * LLM-driven semantic mutation: asks the LLM to produce a meaningfully
 * mutated version of a test that targets a chosen goal.
 */
public class LlmSemanticMutation extends LlmSemanticOperator {

    public LlmSemanticMutation() {
        this(LlmService.getInstance());
    }

    public LlmSemanticMutation(LlmService llmService) {
        super(llmService, LlmFeature.SEMANTIC_MUTATION);
    }

    public LlmSemanticMutation(LlmService llmService, TargetedPromptFactory promptFactory) {
        super(llmService, LlmFeature.SEMANTIC_MUTATION, promptFactory);
    }

    /**
     * Attempt a semantic mutation of the given test chromosome, targeting
     * whichever goal is closest to being covered on it (see
     * {@link OperatorTarget#closestGoal}).
     *
     * @param test  the test to mutate
     * @param goals current goals for context
     * @return the mutated chromosome, or null if LLM mutation failed
     */
    public TestChromosome mutateSemantically(TestChromosome test, Collection<TestFitnessFunction> goals) {
        OperatorTarget target = OperatorTarget.closestGoal(test, null, goals);
        if (target == null) {
            return null;
        }
        return mutate(target);
    }

    /** Mutates the target's parent towards its goal. */
    public TestChromosome mutate(OperatorTarget target) {
        if (target != null && target.getKind() != OperatorTarget.Kind.MUTATION) {
            throw new IllegalArgumentException("LlmSemanticMutation cannot mutate " + target.getKind() + " target");
        }
        List<TestChromosome> result = apply(Collections.singletonList(target));
        return result.isEmpty() ? null : result.get(0);
    }
}
