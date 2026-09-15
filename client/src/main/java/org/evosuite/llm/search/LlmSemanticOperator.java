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
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared machinery for the LLM variation operators (semantic mutation and
 * crossover): renders a batch of {@link OperatorTarget}s through a single
 * {@link TargetedPromptFactory}, makes one LLM call, and maps the response
 * back to per-target test chromosomes via {@link TargetedResponse}.
 */
public abstract class LlmSemanticOperator {

    private static final Logger logger = LoggerFactory.getLogger(LlmSemanticOperator.class);

    private final LlmService llmService;
    private final LlmFeature feature;
    private final TargetedPromptFactory promptFactory;

    /** One loop per calling thread; see {@link #repairLoop()}. */
    private final ThreadLocal<TestRepairLoop> repairLoops = ThreadLocal.withInitial(this::newRepairLoop);

    protected LlmSemanticOperator(LlmService llmService, LlmFeature feature) {
        this(llmService, feature, new TargetedPromptFactory());
    }

    protected LlmSemanticOperator(LlmService llmService, LlmFeature feature, TargetedPromptFactory promptFactory) {
        this.llmService = llmService;
        this.feature = feature;
        this.promptFactory = promptFactory;
    }

    /**
     * Applies this operator to a batch of targets: builds one prompt, makes
     * one LLM call, and returns one {@link TestChromosome} per parsed test,
     * matched targets (in target order) first, then unmatched ones.
     *
     * @param targets the targets to apply the operator to
     * @return the resulting chromosomes, or an empty list when there is
     *     nothing to do, the service is unavailable, or the call fails
     */
    public List<TestChromosome> apply(List<OperatorTarget> targets) {
        TargetedResponse mapped = applyMapped(targets);
        List<TestChromosome> result = new ArrayList<>();
        for (TestCase test : mapped.all()) {
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(test);
            chromosome.setChanged(true);
            result.add(chromosome);
        }
        return result;
    }

    /**
     * Same as {@link #apply(List)} but exposes the {@link TargetedResponse}
     * so a caller can credit individual targets rather than just consuming
     * the flat chromosome list.
     */
    protected TargetedResponse applyMapped(List<OperatorTarget> targets) {
        if (targets == null || targets.isEmpty() || !llmService.isAvailable() || !llmService.hasBudget()) {
            return TargetedResponse.map(null, 0);
        }
        try {
            PromptResult prompt = promptFactory.build(targets);
            String response = llmService.query(prompt, feature);
            RepairResult result = repairLoop().attemptParse(response, prompt.getMessages(), feature);
            return TargetedResponse.map(result, targets.size());
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM {} budget exceeded", feature);
        } catch (LlmCallFailedException e) {
            logger.debug("LLM {} call failed: {}", feature, e.getMessage());
        } catch (RuntimeException e) {
            logger.debug("LLM {} error: {}", feature, e.getMessage());
        }
        return TargetedResponse.map(null, 0);
    }

    /**
     * Returns this thread's repair loop, building it on first use: construction
     * summarises the test cluster, which is too expensive to repeat per
     * application, while {@link TestRepairLoop#attemptParse} keeps per-call
     * conversation state in the instance, so threads must not share one.
     *
     * <p>Package-private for tests.
     */
    TestRepairLoop repairLoop() {
        return repairLoops.get();
    }

    private TestRepairLoop newRepairLoop() {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        return TestRepairLoop.createDefault(llmService,
                TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions),
                Properties.LLM_OPERATOR_REPAIR_ATTEMPTS);
    }
}
