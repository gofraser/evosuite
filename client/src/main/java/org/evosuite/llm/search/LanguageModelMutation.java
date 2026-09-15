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

import org.evosuite.Properties;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.llm.search.operator.SyncTargetPlan;
import org.evosuite.testcase.TestChromosome;

/**
 * MOSA-level LLM mutation operator. The target — which parent, which goal,
 * and any crossover partner — is chosen once per generation by {@code
 * OperatorTargetSelector} and handed in as a {@link SyncTargetPlan}; this
 * hook only decides, via {@link Properties#LLM_MUTATION_PROBABILITY}, whether
 * a targeted parent's offspring is actually sent to the LLM, and falls back
 * gracefully if the call is unavailable or fails.
 *
 * <p>This operator is additive: the caller should apply standard mutation
 * when this operator returns a non-applied result.
 */
public class LanguageModelMutation extends SyncOperatorHook {

    private final LlmSemanticMutation semanticMutation;

    public LanguageModelMutation() {
        this(new LlmSemanticMutation(), null);
    }

    public LanguageModelMutation(LlmSemanticMutation semanticMutation) {
        this(semanticMutation, null);
    }

    public LanguageModelMutation(LlmSemanticMutation semanticMutation, RepeatedInjectionMemory memory) {
        super(memory);
        this.semanticMutation = semanticMutation;
    }

    /**
     * Attempt LLM semantic mutation of {@code offspring}, using whatever
     * target this generation's {@code plan} holds for {@code parent}.
     */
    public OperatorAttemptResult tryMutateWithResult(TestChromosome offspring, TestChromosome parent,
                                                     SyncTargetPlan plan) {
        return tryApply(offspring, parent, plan);
    }

    @Override
    protected double probability() {
        return Properties.LLM_MUTATION_PROBABILITY;
    }

    @Override
    protected boolean canCall() {
        return semanticMutation.canCall();
    }

    @Override
    protected OperatorTarget take(SyncTargetPlan plan, TestChromosome parent) {
        return plan.takeMutation(parent);
    }

    @Override
    protected TestChromosome invoke(OperatorTarget target) {
        return semanticMutation.mutate(target);
    }
}
