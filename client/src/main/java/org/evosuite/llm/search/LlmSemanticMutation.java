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

    @Override
    protected OperatorTarget.Kind kind() {
        return OperatorTarget.Kind.MUTATION;
    }
}
