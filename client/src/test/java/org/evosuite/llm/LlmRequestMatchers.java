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
package org.evosuite.llm;

import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TargetedResponse;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * Mockito matchers and answers for stubbing {@link LlmService#query(LlmRequest)}.
 *
 * <p>An initial request is the first turn of a conversation (repair attempt 1);
 * a repair request is a later turn, sent with the conversation so far.
 */
public final class LlmRequestMatchers {

    private LlmRequestMatchers() {
    }

    /** Any request. */
    public static LlmRequest anyRequest() {
        return any(LlmRequest.class);
    }

    /** A request charged to {@code feature}. */
    public static LlmRequest requestFor(LlmFeature feature) {
        return argThat(request -> request != null && request.getFeature() == feature);
    }

    /** Any first-turn request. */
    public static LlmRequest anyInitialRequest() {
        return argThat(request -> request != null && request.getRepairAttempt() <= 1);
    }

    /** A first-turn request charged to {@code feature}. */
    public static LlmRequest initialRequest(LlmFeature feature) {
        return argThat(request -> request != null && request.getRepairAttempt() <= 1
                && request.getFeature() == feature);
    }

    /** Any repair turn. */
    public static LlmRequest anyRepairRequest() {
        return argThat(request -> request != null && request.getRepairAttempt() > 1);
    }

    /** A repair turn charged to {@code feature}. */
    public static LlmRequest repairRequest(LlmFeature feature) {
        return argThat(request -> request != null && request.getRepairAttempt() > 1
                && request.getFeature() == feature);
    }

    /** A successful call that returned {@code text}, without token usage. */
    public static QueryResult text(String text) {
        return new QueryResult(text, TargetedResponse.CallUsage.NONE);
    }

    /** The messages of each request, in order. */
    public static List<List<LlmMessage>> messagesOf(List<LlmRequest> requests) {
        List<List<LlmMessage>> messages = new ArrayList<>();
        for (LlmRequest request : requests) {
            messages.add(request.getMessages());
        }
        return messages;
    }

    /** The prompt each request was built from, in order. */
    public static List<PromptResult> promptsOf(List<LlmRequest> requests) {
        List<PromptResult> prompts = new ArrayList<>();
        for (LlmRequest request : requests) {
            prompts.add(request.getContext());
        }
        return prompts;
    }
}
