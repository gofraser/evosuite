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

import org.evosuite.llm.prompt.TargetedPromptFactory;
import org.evosuite.testcase.TestCase;
import org.evosuite.testparser.ParseResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the parsed tests of a targeted prompt back to their targets by the
 * method names the prompt asked for ({@link TargetedPromptFactory#testMethodName}).
 * Tests under any other name are kept as unmatched: they were paid for and
 * may still be worth evaluating, they just cannot be credited to a target.
 */
public final class TargetedResponse {

    /**
     * Whether the LLM actually answered. {@link #map} always yields {@code DELIVERED}
     * (even when the response was empty or unparsable — the model got a turn and
     * simply produced nothing usable, which is a legitimate zero-gain outcome for
     * every target); {@link #noBudget()} and {@link #failed()} mark the two cases
     * where no call was ever completed, so no target should be credited a gain of
     * zero — only released, so its repeat-suppression cooldown does not grow on a
     * turn it never had.
     */
    public enum Status { DELIVERED, NO_BUDGET, FAILED }

    private final Map<Integer, TestCase> matched;
    private final List<TestCase> unmatched;
    private final Status status;
    private final int repairTurns;
    private final boolean parseSucceeded;
    private final CallUsage usage;
    private final boolean needsClusterExpansion;
    private final String rawResponse;

    private TargetedResponse(Map<Integer, TestCase> matched, List<TestCase> unmatched, Status status,
                             int repairTurns, boolean parseSucceeded, CallUsage usage,
                             boolean needsClusterExpansion, String rawResponse) {
        this.matched = Collections.unmodifiableMap(matched);
        this.unmatched = Collections.unmodifiableList(unmatched);
        this.status = status;
        this.repairTurns = repairTurns;
        this.parseSucceeded = parseSucceeded;
        this.usage = usage;
        this.needsClusterExpansion = needsClusterExpansion;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
    }

    /**
     * Same as {@link #map(RepairResult, int)}, but also carries the token
     * usage and latency of the call that produced {@code result} so a caller
     * that shares one call across several targets (e.g. a batched operator
     * prompt) can record it against each of them.
     */
    public static TargetedResponse map(RepairResult result, int targetCount, CallUsage usage) {
        return map(result, targetCount, usage, "");
    }

    /**
     * Same as {@link #map(RepairResult, int, CallUsage)}, but also carries the
     * raw LLM text {@code result} was parsed from. A caller whose repair loop
     * deferred cluster expansion ({@link RepairResult#needsClusterExpansion()})
     * needs this text to re-parse the same response once the cluster has been
     * expanded elsewhere; other callers can ignore it.
     */
    public static TargetedResponse map(RepairResult result, int targetCount, CallUsage usage, String rawResponse) {
        Map<Integer, TestCase> matched = new TreeMap<>();
        List<TestCase> unmatched = new ArrayList<>();
        int repairTurns = result == null ? 0 : result.getAttemptsUsed();
        boolean parseSucceeded = result != null && result.isSuccess();
        boolean needsClusterExpansion = result != null && result.needsClusterExpansion();
        CallUsage safeUsage = usage == null ? CallUsage.NONE : usage;
        if (result == null || !result.isSuccess()) {
            return new TargetedResponse(matched, unmatched, Status.DELIVERED, repairTurns, parseSucceeded,
                    safeUsage, needsClusterExpansion, rawResponse);
        }
        for (ParseResult parsed : result.getParseResults()) {
            TestCase test = parsed.getTestCase();
            if (test == null || test.isEmpty()) {
                continue;
            }
            int index = targetIndex(parsed.getOriginalMethodName(), targetCount);
            if (index > 0 && !matched.containsKey(index)) {
                matched.put(index, test);
            } else {
                unmatched.add(test);
            }
        }
        return new TargetedResponse(matched, unmatched, Status.DELIVERED, repairTurns, parseSucceeded,
                safeUsage, needsClusterExpansion, rawResponse);
    }

    /**
     * Assigns each parsed test to the first target (1..{@code targetCount})
     * whose method name it carries; a second test claiming the same target,
     * or a test with any other name, is unmatched. A failed result maps to
     * nothing, and so does an empty test: the parser synthesises an empty
     * method for a response without code (typically a truncated one), which
     * must not replace an offspring or seed the population. Always {@link
     * Status#DELIVERED}: the call went through, whatever came back of it.
     */
    public static TargetedResponse map(RepairResult result, int targetCount) {
        return map(result, targetCount, CallUsage.NONE, "");
    }

    /** An empty, {@link Status#NO_BUDGET} response: no call was made, budget ran out first. */
    public static TargetedResponse noBudget() {
        return new TargetedResponse(new TreeMap<Integer, TestCase>(), new ArrayList<TestCase>(),
                Status.NO_BUDGET, 0, false, CallUsage.NONE, false, "");
    }

    /** An empty, {@link Status#FAILED} response: the call went out but never came back. */
    public static TargetedResponse failed() {
        return new TargetedResponse(new TreeMap<Integer, TestCase>(), new ArrayList<TestCase>(),
                Status.FAILED, 0, false, CallUsage.NONE, false, "");
    }

    public Status getStatus() {
        return status;
    }

    /** The repair loop's attempt count ({@link RepairResult#getAttemptsUsed()}); 0 when no call was delivered. */
    public int getRepairTurns() {
        return repairTurns;
    }

    /** Whether the delivered response was successfully parsed; {@code false} when no call was delivered. */
    public boolean isParseSucceeded() {
        return parseSucceeded;
    }

    /** Token usage and latency of the call behind this response; {@link CallUsage#NONE} when there was none. */
    public CallUsage getUsage() {
        return usage;
    }

    /**
     * Whether the repair loop stopped short because expanding the test cluster
     * could resolve the response, but the loop was not allowed to expand it
     * itself (see {@link org.evosuite.llm.response.TestRepairLoop.RepairOptions#isExpandClusterOnDemand()}).
     * {@link #getRawResponse()} carries the text to re-parse once the cluster
     * has been expanded elsewhere.
     */
    public boolean needsClusterExpansion() {
        return needsClusterExpansion;
    }

    /**
     * The raw LLM text behind this response, for a caller that needs to
     * re-parse it (e.g. after {@link #needsClusterExpansion()}); empty when
     * there was no underlying call ({@link Status#NO_BUDGET} or {@link
     * Status#FAILED}).
     */
    public String getRawResponse() {
        return rawResponse;
    }

    /** The 1-based target number encoded in {@code methodName}, or 0 when it names no target in range. */
    static int targetIndex(String methodName, int targetCount) {
        if (methodName == null) {
            return 0;
        }
        for (int i = 1; i <= targetCount; i++) {
            String expected = TargetedPromptFactory.testMethodName(i);
            if (methodName.equals(expected)
                    || (methodName.startsWith(expected) && !Character.isDigit(methodName.charAt(expected.length())))) {
                return i;
            }
        }
        return 0;
    }

    /** The test produced for target number {@code index} (1-based), if any. */
    public Optional<TestCase> forTarget(int index) {
        return Optional.ofNullable(matched.get(index));
    }

    public List<TestCase> unmatched() {
        return unmatched;
    }

    /** Every parsed test: matched ones in target order, then the unmatched. */
    public List<TestCase> all() {
        List<TestCase> all = new ArrayList<>(matched.values());
        all.addAll(unmatched);
        return all;
    }

    public boolean isEmpty() {
        return matched.isEmpty() && unmatched.isEmpty();
    }

    /**
     * Token usage and latency of one LLM call, shared across every target of
     * the batch that produced it. Immutable.
     */
    public static final class CallUsage {

        /** All-zero usage, for a response with no underlying call. */
        public static final CallUsage NONE = new CallUsage(0, 0, 0, 0L);

        private final int inputTokens;
        private final int cachedInputTokens;
        private final int outputTokens;
        private final long latencyMs;

        public CallUsage(int inputTokens, int cachedInputTokens, int outputTokens, long latencyMs) {
            this.inputTokens = inputTokens;
            this.cachedInputTokens = cachedInputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public int getCachedInputTokens() {
            return cachedInputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }

        public long getLatencyMs() {
            return latencyMs;
        }
    }
}
