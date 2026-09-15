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
 * MOSA-level LLM crossover operator. The target — which parent, its crossover
 * partner, and which goal — is chosen once per generation by {@code
 * OperatorTargetSelector} and handed in as a {@link SyncTargetPlan}; this
 * hook only decides, via {@link Properties#LLM_CROSSOVER_PROBABILITY},
 * whether a targeted parent's offspring is actually sent to the LLM, and
 * falls back gracefully if the call is unavailable or fails.
 *
 * <p>When successful, {@code offspring1} receives the recombined test case.
 */
public class LanguageModelCrossover extends SyncOperatorHook {

    private final LlmSemanticCrossover semanticCrossover;

    public LanguageModelCrossover() {
        this(new LlmSemanticCrossover(), null);
    }

    public LanguageModelCrossover(LlmSemanticCrossover semanticCrossover) {
        this(semanticCrossover, null);
    }

    public LanguageModelCrossover(LlmSemanticCrossover semanticCrossover, RepeatedInjectionMemory memory) {
        super(memory);
        this.semanticCrossover = semanticCrossover;
    }

    /**
     * Attempt LLM semantic crossover of {@code offspring1}, using whatever
     * crossover target this generation's {@code plan} holds for
     * {@code parent1}.
     */
    public OperatorAttemptResult tryCrossoverWithResult(TestChromosome offspring1, TestChromosome parent1,
                                                        SyncTargetPlan plan) {
        return tryApply(offspring1, parent1, plan);
    }

    @Override
    protected double probability() {
        return Properties.LLM_CROSSOVER_PROBABILITY;
    }

    @Override
    protected boolean canCall() {
        return semanticCrossover.canCall();
    }

    @Override
    protected OperatorTarget take(SyncTargetPlan plan, TestChromosome parent) {
        return plan.takeCrossover(parent);
    }

    @Override
    protected TestChromosome invoke(OperatorTarget target) {
        return semanticCrossover.crossover(target);
    }
}
