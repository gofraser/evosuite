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
    private final ThreadLocal<TestRepairLoop> repairLoops =
            ThreadLocal.withInitial(() -> newRepairLoop(true));

    /**
     * One loop per calling thread that must never expand the test cluster
     * itself; see {@link #completeDeferringExpansion(PromptResult, int)}.
     */
    private final ThreadLocal<TestRepairLoop> deferringRepairLoops =
            ThreadLocal.withInitial(() -> newRepairLoop(false));

    /**
     * One loop per calling thread for {@link #reparseWithExpansion}: expansion
     * allowed (it runs on the search thread), but zero LLM repair round-trips.
     */
    private final ThreadLocal<TestRepairLoop> reparseRepairLoops =
            ThreadLocal.withInitial(this::newReparseRepairLoop);

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

    /** Whether a call could go out right now: the service is configured and has budget left. */
    public boolean canCall() {
        return llmService.isAvailable() && llmService.hasBudget(feature);
    }

    /**
     * Same as {@link #apply(List)} but exposes the {@link TargetedResponse}
     * so a caller can credit individual targets rather than just consuming
     * the flat chromosome list.
     */
    public TargetedResponse applyMapped(List<OperatorTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return TargetedResponse.map(null, 0);
        }
        if (!canCall()) {
            return TargetedResponse.noBudget();
        }
        return complete(prepare(targets), targets.size());
    }

    /**
     * Renders {@code targets} into a prompt. Reads the parent test(s) and
     * their last execution result, so must run on the search thread that
     * owns those chromosomes, never on a background worker.
     *
     * @throws IllegalArgumentException when {@code targets} is empty, as
     *     {@link TargetedPromptFactory#build} requires, or when one of them
     *     is not this operator's {@link #kind()}
     */
    public PromptResult prepare(List<OperatorTarget> targets) {
        if (targets != null) {
            for (OperatorTarget target : targets) {
                if (target != null && target.getKind() != kind()) {
                    throw new IllegalArgumentException(
                            getClass().getSimpleName() + " cannot process " + target.getKind() + " target");
                }
            }
        }
        return promptFactory.build(targets);
    }

    /** The only {@link OperatorTarget.Kind} this operator accepts. */
    protected abstract OperatorTarget.Kind kind();

    /**
     * Makes the LLM call for an already-rendered prompt and maps the response
     * back to targets. Safe to run on a background worker: it touches no
     * chromosome state, only the prompt built by {@link #prepare(List)}; the
     * repair loop it uses is parse-only, so the returned tests are executed
     * by the search, never by this call.
     *
     * @param targetCount the number of targets the prompt was built for
     */
    public TargetedResponse complete(PromptResult prompt, int targetCount) {
        if (!canCall()) {
            return TargetedResponse.noBudget();
        }
        try {
            LlmService.QueryResult queryResult = llmService.queryWithUsage(prompt, feature);
            RepairResult result = repairLoop().attemptParse(queryResult.getText(), prompt.getMessages(), feature);
            TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(
                    queryResult.getInputTokens(), queryResult.getCachedInputTokens(),
                    queryResult.getOutputTokens(), queryResult.getLatencyMs());
            return TargetedResponse.map(result, targetCount, usage);
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM {} budget exceeded", feature);
            return TargetedResponse.noBudget();
        } catch (LlmCallFailedException e) {
            logger.debug("LLM {} call failed: {}", feature, e.getMessage());
            return TargetedResponse.failed();
        } catch (RuntimeException e) {
            logger.debug("LLM {} error: {}", feature, e.getMessage());
            return TargetedResponse.failed();
        }
    }

    /**
     * Same as {@link #complete(PromptResult, int)}, but for a caller that runs
     * on a background worker and must never mutate the shared {@link
     * org.evosuite.setup.TestCluster}: its per-thread repair loop is built
     * with expansion disallowed, so a response that hits unresolved symbols
     * is handed back unfinished rather than expanded here. The returned
     * {@link TargetedResponse} is then flagged {@link
     * TargetedResponse#needsClusterExpansion()} and carries the raw LLM text
     * ({@link TargetedResponse#getRawResponse()}) so the search thread can
     * expand the cluster and finish the parse via {@link
     * #reparseWithExpansion(PromptResult, String, int, TargetedResponse.CallUsage)}.
     *
     * @param targetCount the number of targets the prompt was built for
     */
    public TargetedResponse completeDeferringExpansion(PromptResult prompt, int targetCount) {
        if (!canCall()) {
            return TargetedResponse.noBudget();
        }
        try {
            LlmService.QueryResult queryResult = llmService.queryWithUsage(prompt, feature);
            RepairResult result = deferringRepairLoops.get()
                    .attemptParse(queryResult.getText(), prompt.getMessages(), feature);
            logParseOutcome("worker", result);
            TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(
                    queryResult.getInputTokens(), queryResult.getCachedInputTokens(),
                    queryResult.getOutputTokens(), queryResult.getLatencyMs());
            return TargetedResponse.map(result, targetCount, usage, queryResult.getText());
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM {} budget exceeded", feature);
            return TargetedResponse.noBudget();
        } catch (LlmCallFailedException e) {
            logger.debug("LLM {} call failed: {}", feature, e.getMessage());
            return TargetedResponse.failed();
        } catch (RuntimeException e) {
            logger.debug("LLM {} error: {}", feature, e.getMessage());
            return TargetedResponse.failed();
        }
    }

    /**
     * Finishes a response that {@link #completeDeferringExpansion} could not
     * because it needed the test cluster expanded first. Runs a per-thread
     * repair loop with expansion allowed but zero LLM repair round-trips over
     * the same raw text ({@code rawResponse}), and maps the result with
     * {@code usage} rather than issuing a new call: those tokens were already
     * spent by the call that produced {@code rawResponse}. A response whose
     * parse needed expansion therefore gets no further repair turns. Must run
     * on the search thread — the only one allowed to expand the cluster.
     *
     * @param targetCount the number of targets the original prompt was built for
     * @param usage the usage of the call that produced {@code rawResponse}
     */
    public TargetedResponse reparseWithExpansion(PromptResult prompt, String rawResponse, int targetCount,
                                                 TargetedResponse.CallUsage usage) {
        RepairResult result = reparseRepairLoops.get().attemptParse(rawResponse, prompt.getMessages(), feature);
        logParseOutcome("search-thread re-parse", result);
        return TargetedResponse.map(result, targetCount, usage, rawResponse);
    }

    private void logParseOutcome(String stage, RepairResult result) {
        if (logger.isDebugEnabled() && result != null && !result.isSuccess()) {
            logger.debug("LLM {} {} parse failed (needsClusterExpansion={}): {}", feature, stage,
                    result.needsClusterExpansion(), result.getDiagnostics());
        }
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

    private TestRepairLoop newRepairLoop(boolean expandClusterOnDemand) {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        return TestRepairLoop.createDefault(llmService,
                TestRepairLoop.RepairOptions.parseOnly(keepAssertions, expandClusterOnDemand),
                Properties.LLM_OPERATOR_REPAIR_ATTEMPTS);
    }

    private TestRepairLoop newReparseRepairLoop() {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        return TestRepairLoop.createDefault(llmService,
                TestRepairLoop.RepairOptions.parseOnly(keepAssertions, true),
                0);
    }
}
