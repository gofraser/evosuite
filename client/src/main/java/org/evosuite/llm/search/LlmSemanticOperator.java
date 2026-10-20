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
import org.evosuite.llm.LlmWaitBudget;
import org.evosuite.llm.QueryResult;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.llm.search.operator.OperatorTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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

    /** Seconds the search has left, negative for no limit; bounds {@link #complete(PromptResult, int)}. */
    private volatile LongSupplier remainingSearchSeconds = () -> -1L;

    /** The loop of {@link #complete(PromptResult, int)}; see {@link #repairLoop()}. */
    private final Supplier<TestRepairLoop> repairLoop =
            lazily(() -> newRepairLoop(true, Properties.LLM_OPERATOR_REPAIR_ATTEMPTS));

    /**
     * The loop that must never expand the test cluster itself; see {@link
     * #completeDeferringExpansion(PromptResult, int)}.
     */
    private final Supplier<TestRepairLoop> deferringRepairLoop =
            lazily(() -> newRepairLoop(false, Properties.LLM_OPERATOR_REPAIR_ATTEMPTS));

    /**
     * The loop of {@link #reparseWithExpansion}: expansion allowed (it runs on
     * the search thread), but zero LLM repair round-trips.
     */
    private final Supplier<TestRepairLoop> reparseRepairLoop = lazily(() -> newRepairLoop(true, 0));

    protected LlmSemanticOperator(LlmService llmService, LlmFeature feature) {
        this(llmService, feature, new TargetedPromptFactory());
    }

    protected LlmSemanticOperator(LlmService llmService, LlmFeature feature, TargetedPromptFactory promptFactory) {
        this.llmService = llmService;
        this.feature = feature;
        this.promptFactory = promptFactory;
    }

    /**
     * Bounds the calls of {@link #complete(PromptResult, int)}, and so of {@link
     * #applyMapped(List)}, by the search budget: the call and its repair turns
     * give up once {@code remainingSearchSeconds} have passed. A supplier
     * returning a negative value (the default) sets no bound.
     */
    public void setRemainingSearchSeconds(LongSupplier remainingSearchSeconds) {
        this.remainingSearchSeconds = remainingSearchSeconds == null ? () -> -1L : remainingSearchSeconds;
    }

    /** Whether a call could go out right now: the service is configured and has budget left. */
    public boolean canCall() {
        return llmService.isAvailable() && llmService.hasBudget(feature);
    }

    /**
     * Applies this operator to a batch of targets: builds one prompt, makes
     * one LLM call and returns the {@link TargetedResponse}, so a caller can
     * credit individual targets. The response is empty when there is nothing
     * to do, the service is unavailable, or the call fails.
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
    public abstract OperatorTarget.Kind kind();

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
        return complete(prompt, targetCount, repairLoop, null,
                LlmWaitBudget.deadlineNanos(remainingSearchSeconds));
    }

    /**
     * Same as {@link #complete(PromptResult, int)}, but for a caller that runs
     * on a background worker and must never mutate the shared {@link
     * org.evosuite.setup.TestCluster}: its repair loop is built
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
        return complete(prompt, targetCount, deferringRepairLoop, "worker", Long.MAX_VALUE);
    }

    /**
     * Shared body of the two {@code complete} variants: one call, then one
     * parse with the loop {@code loop} supplies (resolved after the call, so a
     * failure to build it counts as a delivered-but-unparsed response like any
     * other parse failure). {@code stage} names the caller for parse-failure
     * logging, or is null to log nothing. {@code deadlineNanos} bounds the call
     * and its repair turns, {@link Long#MAX_VALUE} for no bound.
     */
    private TargetedResponse complete(PromptResult prompt, int targetCount, Supplier<TestRepairLoop> loop,
                                      String stage, long deadlineNanos) {
        if (!canCall()) {
            return TargetedResponse.noBudget();
        }
        QueryResult queryResult;
        try {
            queryResult = deadlineNanos == Long.MAX_VALUE
                    ? llmService.queryWithUsage(prompt, feature)
                    : llmService.queryWithUsage(prompt, feature, deadlineNanos);
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
        TargetedResponse.CallUsage usage = queryResult.getUsage();
        try {
            RepairResult result = loop.get().attemptParse(queryResult.getText(), prompt, feature,
                    deadlineNanos);
            if (stage != null) {
                logParseOutcome(stage, result);
            }
            return TargetedResponse.map(result, targetCount, usage, queryResult.getText());
        } catch (RuntimeException e) {
            // The call was delivered and its tokens were spent; a failure past
            // this point (parsing or mapping) is a legitimate zero-gain outcome
            // for the targets, not an undelivered call to release.
            logger.debug("LLM {} response delivered but could not be parsed: {}", feature, e.getMessage());
            return TargetedResponse.deliveredUnparsed(usage, queryResult.getText());
        }
    }

    /**
     * Finishes a response that {@link #completeDeferringExpansion} could not
     * because it needed the test cluster expanded first. Runs a repair loop
     * with expansion allowed but zero LLM repair round-trips over
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
        RepairResult result = reparseRepairLoop.get().attemptParse(rawResponse, prompt, feature);
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
     * Returns the repair loop, building it on first use: construction
     * summarises the test cluster, which is too expensive to repeat per
     * application. {@link TestRepairLoop#attemptParse} keeps its conversation
     * state per call, so the search thread and the workers share one loop.
     *
     * <p>Package-private for tests.
     */
    TestRepairLoop repairLoop() {
        return repairLoop.get();
    }

    /**
     * Returns the cluster-expansion-disallowed repair loop, used by {@link
     * #completeDeferringExpansion(PromptResult, int)}.
     *
     * <p>Package-private for tests.
     */
    TestRepairLoop deferringRepairLoop() {
        return deferringRepairLoop.get();
    }

    /** Returns a supplier that builds its value once, on the first call from any thread. */
    private static <T> Supplier<T> lazily(Supplier<T> factory) {
        return new Supplier<T>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) {
                    value = factory.get();
                }
                return value;
            }
        };
    }

    private TestRepairLoop newRepairLoop(boolean expandClusterOnDemand, int repairAttempts) {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        return TestRepairLoop.createDefault(llmService,
                TestRepairLoop.RepairOptions.parseOnly(keepAssertions, expandClusterOnDemand),
                repairAttempts);
    }
}
