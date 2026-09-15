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

    private TargetedResponse(Map<Integer, TestCase> matched, List<TestCase> unmatched, Status status) {
        this.matched = Collections.unmodifiableMap(matched);
        this.unmatched = Collections.unmodifiableList(unmatched);
        this.status = status;
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
        Map<Integer, TestCase> matched = new TreeMap<>();
        List<TestCase> unmatched = new ArrayList<>();
        if (result == null || !result.isSuccess()) {
            return new TargetedResponse(matched, unmatched, Status.DELIVERED);
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
        return new TargetedResponse(matched, unmatched, Status.DELIVERED);
    }

    /** An empty, {@link Status#NO_BUDGET} response: no call was made, budget ran out first. */
    public static TargetedResponse noBudget() {
        return new TargetedResponse(new TreeMap<Integer, TestCase>(), new ArrayList<TestCase>(), Status.NO_BUDGET);
    }

    /** An empty, {@link Status#FAILED} response: the call went out but never came back. */
    public static TargetedResponse failed() {
        return new TargetedResponse(new TreeMap<Integer, TestCase>(), new ArrayList<TestCase>(), Status.FAILED);
    }

    public Status getStatus() {
        return status;
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
}
