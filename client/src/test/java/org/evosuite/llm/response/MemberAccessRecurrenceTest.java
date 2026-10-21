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

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.evosuite.llm.LlmRequestMatchers.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Only inaccessible members reported on an earlier repair turn get the escalated wording.
 */
class MemberAccessRecurrenceTest {

    private static final String RECURRENT = "CRITICAL (recurrent): You MUST NOT call `";

    @Test
    void onlyMembersFromEarlierTurnsAreMarkedRecurrent() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.query(any(LlmRequest.class))).thenReturn(text(null));
        TestRepairLoop loop = new TestRepairLoop(
                service, mock(TestParser.class), new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase), 3,
                null, "");
        RepairConversation state = new RepairConversation();
        Method requestRepair = TestRepairLoop.class.getDeclaredMethod("requestRepair",
                RepairConversation.class, List.class, String.class, String.class, LlmFeature.class,
                List.class, int.class, Map.class, long.class);
        requestRepair.setAccessible(true);

        String turn1 = "FooTest.java:12: error: alpha() has private access in com.example.Vault";
        String turn2 = turn1 + "\nFooTest.java:14: error: beta() has private access in com.example.Vault";
        String[] errors = {turn1, turn2};
        String last = null;
        for (int i = 0; i < errors.length; i++) {
            List<LlmMessage> conversation = new ArrayList<>();
            requestRepair.invoke(loop, state, conversation, null, errors[i], LlmFeature.SEEDING,
                    Collections.<String>emptyList(), 2 + i, Collections.emptyMap(),
                    TestRepairLoop.NO_REPAIR_DEADLINE);
            last = conversation.get(conversation.size() - 1).getContent();
        }

        assertTrue(last.contains(RECURRENT + "alpha()`"), last);
        assertFalse(last.contains(RECURRENT + "beta()`"), last);
        assertTrue(last.contains("Do NOT call `beta()`"), last);
    }
}
