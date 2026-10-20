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
package org.evosuite.llm.response;

import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.QueryResult;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testparser.ParseResult;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One request for generated tests: the prompt goes to {@link LlmService},
 * and the response goes through a {@link TestRepairLoop}, whose repair turns
 * share the request's deadline. Every way this can end is an {@link Outcome}
 * rather than an exception, so a caller decides once, by {@link
 * Outcome.Status}, what an undelivered call or an unparsable response means
 * for it.
 *
 * <p>The request makes no availability or budget check of its own: callers
 * gate on those before building their prompt. Errors are not caught.
 */
public final class GeneratedTestRequest {

    private final PromptResult prompt;
    private final LlmFeature feature;
    private final long deadlineNanos;
    private final Consumer<List<ParseResult>> salvagedTests;
    private final BooleanSupplier stillWanted;

    private GeneratedTestRequest(PromptResult prompt, LlmFeature feature, long deadlineNanos,
                                 Consumer<List<ParseResult>> salvagedTests, BooleanSupplier stillWanted) {
        this.prompt = prompt;
        this.feature = feature;
        this.deadlineNanos = deadlineNanos;
        this.salvagedTests = salvagedTests;
        this.stillWanted = stillWanted;
    }

    /** A request for {@code prompt}, charged to {@code feature}, without a deadline. */
    public static GeneratedTestRequest of(PromptResult prompt, LlmFeature feature) {
        return new GeneratedTestRequest(prompt, feature, LlmRequest.NO_DEADLINE, null, null);
    }

    /**
     * This request bounded by an absolute {@link System#nanoTime()} deadline,
     * for the call and for the repair turns after it.
     */
    public GeneratedTestRequest withDeadlineNanos(long deadlineNanos) {
        return new GeneratedTestRequest(prompt, feature, deadlineNanos, salvagedTests, stillWanted);
    }

    /** This request handing the tests the repair loop salvages along the way to {@code consumer}. */
    public GeneratedTestRequest onSalvagedTests(Consumer<List<ParseResult>> consumer) {
        return new GeneratedTestRequest(prompt, feature, deadlineNanos, consumer, stillWanted);
    }

    /**
     * This request asking {@code stillWanted} once the response has arrived,
     * and leaving it unparsed ({@link Outcome.Status#ABANDONED}) when the
     * answer is false.
     */
    public GeneratedTestRequest parseOnlyIf(BooleanSupplier stillWanted) {
        return new GeneratedTestRequest(prompt, feature, deadlineNanos, salvagedTests, stillWanted);
    }

    /**
     * Sends this request through {@code llmService} and parses the response
     * with the loop {@code repairLoop} supplies. The loop is only asked for
     * once the response has arrived, so a failure to build it counts as a
     * parse failure of a delivered response.
     */
    public Outcome send(LlmService llmService, Supplier<TestRepairLoop> repairLoop) {
        QueryResult queryResult;
        String response;
        try {
            queryResult = llmService.query(LlmRequest.of(prompt, feature).withDeadlineNanos(deadlineNanos));
            response = queryResult.getText();
        } catch (LlmBudgetExceededException e) {
            return new Outcome(Outcome.Status.NO_BUDGET, e, null, null, null);
        } catch (LlmCallFailedException e) {
            return new Outcome(Outcome.Status.CALL_FAILED, e, null, null, null);
        } catch (RuntimeException e) {
            return new Outcome(Outcome.Status.CALL_CRASHED, e, null, null, null);
        }
        TargetedResponse.CallUsage usage = queryResult.getUsage();
        if (stillWanted != null && !stillWanted.getAsBoolean()) {
            return new Outcome(Outcome.Status.ABANDONED, null, response, usage, null);
        }
        try {
            TestRepairLoop loop = repairLoop.get();
            RepairResult result = salvagedTests == null
                    ? loop.attemptParse(response, prompt, feature, deadlineNanos)
                    : loop.attemptParse(response, prompt, feature, deadlineNanos, salvagedTests);
            return new Outcome(Outcome.Status.PARSED, null, response, usage, result);
        } catch (RuntimeException e) {
            return new Outcome(Outcome.Status.PARSE_CRASHED, e, response, usage, null);
        }
    }

    /** How a {@link GeneratedTestRequest} ended. */
    public static final class Outcome {

        /** The stage a request reached. */
        public enum Status {
            /** The service refused the call: the feature's budget is spent. */
            NO_BUDGET,
            /** The call went out and failed (provider error, timeout, deadline, interrupt). */
            CALL_FAILED,
            /** The call threw something other than an LLM call failure. */
            CALL_CRASHED,
            /** The response arrived but was no longer wanted, so it was not parsed. */
            ABANDONED,
            /** The response arrived, but parsing or repairing it threw. */
            PARSE_CRASHED,
            /** The response was parsed; {@link #getResult()} says whether any test came of it. */
            PARSED
        }

        private final Status status;
        private final RuntimeException failure;
        private final String response;
        private final TargetedResponse.CallUsage usage;
        private final RepairResult result;

        Outcome(Status status, RuntimeException failure, String response,
                TargetedResponse.CallUsage usage, RepairResult result) {
            this.status = status;
            this.failure = failure;
            this.response = response;
            this.usage = usage == null ? TargetedResponse.CallUsage.NONE : usage;
            this.result = result;
        }

        public Status getStatus() {
            return status;
        }

        /** Whether the model answered, whatever became of the answer. */
        public boolean isDelivered() {
            return status == Status.ABANDONED || status == Status.PARSE_CRASHED || status == Status.PARSED;
        }

        /** What the call or the parse threw, or null when nothing did. */
        public RuntimeException getFailure() {
            return failure;
        }

        /** The response text, or null when none was delivered. */
        public String getResponse() {
            return response;
        }

        /** The usage of the call, {@link TargetedResponse.CallUsage#NONE} when none was delivered. */
        public TargetedResponse.CallUsage getUsage() {
            return usage;
        }

        /** The parse result, or null unless {@link #getStatus()} is {@link Status#PARSED}. */
        public RepairResult getResult() {
            return result;
        }

        /** Whether the response was parsed successfully. */
        public boolean isSuccess() {
            return result != null && result.isSuccess();
        }

        /** The parsed tests as chromosomes; empty unless {@link #isSuccess()}. */
        public List<TestChromosome> toChromosomes() {
            return isSuccess() ? result.toChromosomes() : Collections.<TestChromosome>emptyList();
        }

        /** Throws {@link #getFailure()}, if there is one. */
        public void rethrowFailure() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
