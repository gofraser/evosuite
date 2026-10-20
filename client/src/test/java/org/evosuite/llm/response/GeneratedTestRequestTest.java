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
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.QueryResult;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.evosuite.llm.LlmRequestMatchers.anyRequest;
import static org.evosuite.llm.LlmServiceMocks.mockLlmService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedTestRequestTest {

    private static final LlmFeature FEATURE = LlmFeature.STAGNATION;

    private final PromptResult prompt = new PromptResult.Builder()
            .messages(Collections.singletonList(LlmMessage.user("generate")))
            .build();

    private static Supplier<TestRepairLoop> loopReturning(RepairResult result) {
        TestRepairLoop loop = mock(TestRepairLoop.class);
        when(loop.attemptParse(anyString(), any(PromptResult.class), any(LlmFeature.class), anyLong()))
                .thenReturn(result);
        return () -> loop;
    }

    private static Supplier<TestRepairLoop> unusedLoop() {
        return () -> {
            throw new AssertionError("the loop must not be asked for");
        };
    }

    private static RepairResult successWithOneTest() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 1));
        return RepairResult.success(Collections.singletonList(new ParseResult(test, "test")),
                Collections.<String>emptyList(), 0, Collections.<String>emptyList());
    }

    @Test
    void budgetRefusalIsNoBudgetWithoutAskingForTheLoop() {
        LlmService service = mockLlmService();
        LlmBudgetExceededException refusal = new LlmBudgetExceededException("spent");
        when(service.query(anyRequest())).thenThrow(refusal);

        GeneratedTestRequest.Outcome outcome = GeneratedTestRequest.of(prompt, FEATURE).send(service, unusedLoop());

        assertEquals(GeneratedTestRequest.Outcome.Status.NO_BUDGET, outcome.getStatus());
        assertSame(refusal, outcome.getFailure());
        assertFalse(outcome.isDelivered());
        assertEquals(TargetedResponse.CallUsage.NONE, outcome.getUsage());
        assertTrue(outcome.toChromosomes().isEmpty());
        assertThrows(LlmBudgetExceededException.class, outcome::rethrowFailure);
    }

    @Test
    void callFailureAndCrashAreUndelivered() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenThrow(new LlmCallFailedException("boom", null, false));
        GeneratedTestRequest.Outcome failed = GeneratedTestRequest.of(prompt, FEATURE).send(service, unusedLoop());
        assertEquals(GeneratedTestRequest.Outcome.Status.CALL_FAILED, failed.getStatus());
        assertFalse(failed.isDelivered());

        LlmService crashing = mockLlmService();
        when(crashing.query(anyRequest())).thenThrow(new IllegalStateException("crash"));
        GeneratedTestRequest.Outcome crashed = GeneratedTestRequest.of(prompt, FEATURE).send(crashing, unusedLoop());
        assertEquals(GeneratedTestRequest.Outcome.Status.CALL_CRASHED, crashed.getStatus());
        assertFalse(crashed.isDelivered());
        assertTrue(crashed.getFailure() instanceof IllegalStateException);
    }

    @Test
    void parsedResponseCarriesTextUsageAndTests() {
        LlmService service = mockLlmService();
        TargetedResponse.CallUsage usage = new TargetedResponse.CallUsage(10, 2, 5, 7L);
        when(service.query(anyRequest())).thenReturn(new QueryResult("code", usage));

        GeneratedTestRequest.Outcome outcome = GeneratedTestRequest.of(prompt, FEATURE)
                .send(service, loopReturning(successWithOneTest()));

        assertEquals(GeneratedTestRequest.Outcome.Status.PARSED, outcome.getStatus());
        assertTrue(outcome.isDelivered());
        assertTrue(outcome.isSuccess());
        assertNull(outcome.getFailure());
        assertEquals("code", outcome.getResponse());
        assertSame(usage, outcome.getUsage());
        assertEquals(1, outcome.toChromosomes().size());
    }

    @Test
    void failedParseIsDeliveredWithoutTests() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenReturn(new QueryResult("prose", TargetedResponse.CallUsage.NONE));

        GeneratedTestRequest.Outcome outcome = GeneratedTestRequest.of(prompt, FEATURE)
                .send(service, loopReturning(RepairResult.failure(Collections.singletonList("no code"), 1,
                        Collections.<String>emptyList())));

        assertEquals(GeneratedTestRequest.Outcome.Status.PARSED, outcome.getStatus());
        assertTrue(outcome.isDelivered());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.toChromosomes().isEmpty());
    }

    @Test
    void parseCrashAndLoopConstructionFailureAreDelivered() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenReturn(new QueryResult("code", TargetedResponse.CallUsage.NONE));

        GeneratedTestRequest.Outcome outcome = GeneratedTestRequest.of(prompt, FEATURE).send(service, () -> {
            throw new IllegalStateException("cannot build loop");
        });

        assertEquals(GeneratedTestRequest.Outcome.Status.PARSE_CRASHED, outcome.getStatus());
        assertTrue(outcome.isDelivered());
        assertEquals("code", outcome.getResponse());
        assertTrue(outcome.getFailure() instanceof IllegalStateException);
    }

    @Test
    void unwantedResponseIsNotParsed() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenReturn(new QueryResult("code", TargetedResponse.CallUsage.NONE));
        AtomicBoolean asked = new AtomicBoolean();

        GeneratedTestRequest.Outcome outcome = GeneratedTestRequest.of(prompt, FEATURE)
                .parseOnlyIf(() -> {
                    asked.set(true);
                    return false;
                })
                .send(service, unusedLoop());

        assertTrue(asked.get());
        assertEquals(GeneratedTestRequest.Outcome.Status.ABANDONED, outcome.getStatus());
        assertTrue(outcome.isDelivered());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadlineAndSalvageConsumerReachTheCallAndTheLoop() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenReturn(new QueryResult("code", TargetedResponse.CallUsage.NONE));
        TestRepairLoop loop = mock(TestRepairLoop.class);
        Consumer<List<ParseResult>> consumer = parsed -> { };
        when(loop.attemptParse(anyString(), any(PromptResult.class), any(LlmFeature.class), anyLong(), any()))
                .thenReturn(successWithOneTest());

        GeneratedTestRequest.of(prompt, FEATURE).withDeadlineNanos(42L).onSalvagedTests(consumer)
                .send(service, () -> loop);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(service).query(request.capture());
        assertEquals(42L, request.getValue().getDeadlineNanos());
        assertSame(prompt, request.getValue().getContext());
        assertEquals(FEATURE, request.getValue().getFeature());
        verify(loop).attemptParse(eq("code"), eq(prompt), eq(FEATURE), eq(42L), eq(consumer));
    }

    /** Without a salvage consumer the loop is entered where a subclass overriding the deadline overload sees it. */
    @Test
    void noDeadlineByDefault() {
        LlmService service = mockLlmService();
        when(service.query(anyRequest())).thenReturn(new QueryResult("code", TargetedResponse.CallUsage.NONE));
        TestRepairLoop loop = mock(TestRepairLoop.class);
        when(loop.attemptParse(anyString(), any(PromptResult.class), any(LlmFeature.class), anyLong()))
                .thenReturn(successWithOneTest());

        GeneratedTestRequest.of(prompt, FEATURE).send(service, () -> loop);

        verify(loop).attemptParse(eq("code"), eq(prompt), eq(FEATURE), eq(TestRepairLoop.NO_REPAIR_DEADLINE));
    }
}
