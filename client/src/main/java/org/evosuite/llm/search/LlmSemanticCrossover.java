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

    /** Recombines the target's parent and partner towards the target's goal. */
    public TestChromosome crossover(OperatorTarget target) {
        if (target != null && target.getKind() != OperatorTarget.Kind.CROSSOVER) {
            throw new IllegalArgumentException("LlmSemanticCrossover cannot crossover " + target.getKind() + " target");
        }
        List<TestChromosome> result = apply(Collections.singletonList(target));
        return result.isEmpty() ? null : result.get(0);
    }
}
