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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testparser.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TargetedResponse} maps parsed tests back to the
 * targets named in {@link org.evosuite.llm.prompt.TargetedPromptFactory#testMethodName},
 * and that anything else (a second claim on a target, a differently-named
 * method, a null method name, a null test case) is treated as unmatched
 * rather than silently dropped or mis-attributed.
 */
class TargetedResponseTest {

    private static TestCase testCase(int marker) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, marker));
        return test;
    }

    private static ParseResult parsed(TestCase testCase, String methodName) {
        return new ParseResult(testCase, methodName);
    }

    // ---- happy path -------------------------------------------------------------

    @Test
    void mapsTestsToTargetsRegardlessOfParseOrder() {
        TestCase test1 = testCase(1);
        TestCase test2 = testCase(2);
        RepairResult result = RepairResult.success(
                Arrays.asList(parsed(test2, "test_target_2"), parsed(test1, "test_target_1")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 2);

        assertEquals(java.util.Optional.of(test1), response.forTarget(1));
        assertEquals(java.util.Optional.of(test2), response.forTarget(2));
        assertEquals(Arrays.asList(test1, test2), response.all(), "all() must be in target order regardless of parse order");
        assertTrue(response.unmatched().isEmpty());
    }

    @Test
    void secondTestClaimingSameTargetIsUnmatched() {
        TestCase first = testCase(1);
        TestCase second = testCase(2);
        RepairResult result = RepairResult.success(
                Arrays.asList(parsed(first, "test_target_1"), parsed(second, "test_target_1")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertSame(first, response.forTarget(1).get());
        assertEquals(1, response.unmatched().size());
        assertSame(second, response.unmatched().get(0));
    }

    // ---- name matching edge cases -------------------------------------------------

    @Test
    void methodNameWithSuffixSeparatorStillMatchesTarget() {
        TestCase test = testCase(1);
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(test, "test_target_1_variant")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertSame(test, response.forTarget(1).get());
        assertTrue(response.unmatched().isEmpty());
    }

    @Test
    void methodNameWithDigitSuffixDoesNotMatchADifferentTarget() {
        TestCase test = testCase(1);
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(test, "test_target_10")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertFalse(response.forTarget(1).isPresent(),
                "test_target_10 must not be mistaken for target 1 just because the name starts with it");
        assertEquals(1, response.unmatched().size());
        assertSame(test, response.unmatched().get(0));
    }

    @Test
    void unrelatedMethodNameIsUnmatched() {
        TestCase test = testCase(1);
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(test, "testSomething")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertFalse(response.forTarget(1).isPresent());
        assertEquals(1, response.unmatched().size());
    }

    @Test
    void nullMethodNameIsUnmatched() {
        TestCase test = testCase(1);
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(test, null)),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertFalse(response.forTarget(1).isPresent());
        assertEquals(1, response.unmatched().size());
    }

    // ---- null test case is skipped entirely ---------------------------------------

    @Test
    void parseResultWithNullTestCaseIsSkippedEntirely() {
        TestCase real = testCase(1);
        List<ParseResult> parseResults = new ArrayList<>();
        parseResults.add(new ParseResult(null, "test_target_1"));
        parseResults.add(parsed(real, "test_target_2"));
        RepairResult result = RepairResult.success(parseResults, Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 2);

        assertFalse(response.forTarget(1).isPresent(), "a null test case must not occupy target 1's slot");
        assertSame(real, response.forTarget(2).get());
        assertTrue(response.unmatched().isEmpty());
        assertEquals(1, response.all().size());
    }

    @Test
    void emptyTestCaseIsSkippedEntirely() {
        // The response parser synthesises an empty method for a code-less
        // (typically truncated) response; it must credit no target and never
        // reach the population as an "unmatched" test.
        RepairResult result = RepairResult.success(
                Arrays.asList(parsed(new DefaultTestCase(), "test_target_1"), parsed(new DefaultTestCase(), "generatedTest")),
                Collections.emptyList(), 0, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertTrue(response.isEmpty());
        assertFalse(response.forTarget(1).isPresent());
    }

    // ---- failure / null result ------------------------------------------------------

    @Test
    void failureResultMapsToEmptyResponse() {
        RepairResult failure = RepairResult.failure(Collections.singletonList("boom"), 1, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(failure, 3);

        assertTrue(response.isEmpty());
        assertTrue(response.all().isEmpty());
    }

    @Test
    void nullResultMapsToEmptyResponse() {
        TargetedResponse response = TargetedResponse.map(null, 3);

        assertTrue(response.isEmpty());
        assertTrue(response.all().isEmpty());
    }

    // ---- status ---------------------------------------------------------------------

    @Test
    void mapAlwaysYieldsDeliveredEvenForAFailedOrNullResult() {
        RepairResult failure = RepairResult.failure(Collections.singletonList("boom"), 1, Collections.emptyList());

        assertEquals(TargetedResponse.Status.DELIVERED,
                TargetedResponse.map(RepairResult.success(Collections.emptyList(), Collections.emptyList(), 0,
                        Collections.emptyList()), 1).getStatus());
        assertEquals(TargetedResponse.Status.DELIVERED, TargetedResponse.map(failure, 3).getStatus());
        assertEquals(TargetedResponse.Status.DELIVERED, TargetedResponse.map(null, 3).getStatus());
    }

    @Test
    void noBudgetIsEmptyAndCarriesTheNoBudgetStatus() {
        TargetedResponse response = TargetedResponse.noBudget();

        assertTrue(response.isEmpty());
        assertTrue(response.all().isEmpty());
        assertEquals(TargetedResponse.Status.NO_BUDGET, response.getStatus());
    }

    @Test
    void failedIsEmptyAndCarriesTheFailedStatus() {
        TargetedResponse response = TargetedResponse.failed();

        assertTrue(response.isEmpty());
        assertTrue(response.all().isEmpty());
        assertEquals(TargetedResponse.Status.FAILED, response.getStatus());
    }

    // ---- repair turns / parse status / usage -----------------------------------------

    @Test
    void mapCarriesRepairTurnsAndParseSucceededFromASuccessfulResult() {
        // attemptsUsed counts the initial parse as attempt 1, so 3 attempts means 2 repair turns.
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(testCase(1), "test_target_1")),
                Collections.emptyList(), 3, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertEquals(2, response.getRepairTurns());
        assertTrue(response.isParseSucceeded());
    }

    @Test
    void mapCarriesRepairTurnsButNotParseSucceededFromAFailedResult() {
        RepairResult failure = RepairResult.failure(Collections.singletonList("boom"), 2, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(failure, 1);

        assertEquals(1, response.getRepairTurns());
        assertFalse(response.isParseSucceeded());
    }

    @Test
    void mapOfNullResultHasZeroRepairTurnsAndNoParseSuccess() {
        TargetedResponse response = TargetedResponse.map(null, 1);

        assertEquals(0, response.getRepairTurns());
        assertFalse(response.isParseSucceeded());
    }

    @Test
    void noBudgetAndFailedCarryZeroRepairTurnsAndNoUsage() {
        assertEquals(0, TargetedResponse.noBudget().getRepairTurns());
        assertFalse(TargetedResponse.noBudget().isParseSucceeded());
        assertEquals(TargetedResponse.CallUsage.NONE, TargetedResponse.noBudget().getUsage());

        assertEquals(0, TargetedResponse.failed().getRepairTurns());
        assertFalse(TargetedResponse.failed().isParseSucceeded());
        assertEquals(TargetedResponse.CallUsage.NONE, TargetedResponse.failed().getUsage());
    }

    @Test
    void twoArgMapDelegatesToThreeArgMapWithNoUsage() {
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(testCase(1), "test_target_1")),
                Collections.emptyList(), 1, Collections.emptyList());

        TargetedResponse response = TargetedResponse.map(result, 1);

        assertEquals(TargetedResponse.CallUsage.NONE, response.getUsage());
    }

    @Test
    void threeArgMapCarriesUsageThrough() {
        RepairResult result = RepairResult.success(
                Collections.singletonList(parsed(testCase(1), "test_target_1")),
                Collections.emptyList(), 1, Collections.emptyList());
        TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(10, 4, 20, 123L);

        TargetedResponse response = TargetedResponse.map(result, 1, usage);

        assertSame(usage, response.getUsage());
    }

    @Test
    void callUsageNoneIsAllZero() {
        TargetedResponse.CallUsage none = TargetedResponse.CallUsage.NONE;
        assertEquals(0, none.getInputTokens());
        assertEquals(0, none.getCachedInputTokens());
        assertEquals(0, none.getOutputTokens());
        assertEquals(0L, none.getLatencyMs());
    }

    @Test
    void callUsageExposesConstructorValues() {
        TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(1, 2, 3, 4L);
        assertEquals(1, usage.getInputTokens());
        assertEquals(2, usage.getCachedInputTokens());
        assertEquals(3, usage.getOutputTokens());
        assertEquals(4L, usage.getLatencyMs());
    }

    // ---- targetIndex direct cases ---------------------------------------------------

    @Test
    void targetIndexDirectCases() {
        assertEquals(0, TargetedResponse.targetIndex(null, 3));
        assertEquals(1, TargetedResponse.targetIndex("test_target_1", 3));
        assertEquals(2, TargetedResponse.targetIndex("test_target_2", 3));
        assertEquals(0, TargetedResponse.targetIndex("test_target_4", 3), "out of range target must not match");
        assertEquals(0, TargetedResponse.targetIndex("test_target_10", 1));
        assertEquals(1, TargetedResponse.targetIndex("test_target_1_extra", 1));
        assertEquals(0, TargetedResponse.targetIndex("unrelated", 3));
    }
}
