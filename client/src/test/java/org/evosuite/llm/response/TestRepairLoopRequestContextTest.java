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

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.evosuite.llm.LlmRequestMatchers.anyRequest;
import static org.evosuite.llm.LlmRequestMatchers.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a repair turn tells the trace about the prompt the conversation
 * started from.
 */
class TestRepairLoopRequestContextTest {

    @Test
    void repairTurnCarriesTheContextMetadataOfTheInitialPrompt() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyRequest())).thenReturn(text("```java\nbroken\n```"));
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "syntax error", 1, "broken"));
                return Collections.singletonList(error);
            }
        };
        TestRepairLoop loop = new TestRepairLoop(llmService, parser, new LlmResponseParser(),
                mock(ClusterExpansionManager.class), testCase -> new ExecutionResult(testCase),
                1, "system", "");
        PromptResult prompt = new PromptResult.Builder()
                .messages(Arrays.asList(LlmMessage.system("system"), LlmMessage.user("seed")))
                .sutContextMode(Properties.LlmSutContextMode.SOURCE_CODE)
                .contextTruncated(true)
                .clusterSummaryChars(1234)
                .build();

        loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```", prompt,
                LlmFeature.TEST_REPAIR);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService, times(1)).query(request.capture());
        assertEquals(2, request.getValue().getRepairAttempt());
        assertSame(prompt, request.getValue().getContext(),
                "the repair turn must be traced with the context metadata of the prompt it continues");
    }
}
