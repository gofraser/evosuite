package org.evosuite.llm.mock;

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic in-memory chat model for unit tests and trace replay.
 */
public class MockChatLanguageModel implements LlmService.ChatLanguageModel {

    private final Map<LlmFeature, Deque<LlmService.LlmResponse>> responses = new EnumMap<>(LlmFeature.class);

    public void enqueue(LlmFeature feature, String responseText) {
        enqueue(feature, new LlmService.LlmResponse(responseText, 0, 0));
    }

    public void enqueue(LlmFeature feature, LlmService.LlmResponse response) {
        responses.computeIfAbsent(feature, ignored -> new ArrayDeque<>()).add(response);
    }

    @Override
    public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
        Deque<LlmService.LlmResponse> queue = responses.get(feature);
        if (queue == null || queue.isEmpty()) {
            throw new IllegalStateException("No mock response queued for feature " + feature);
        }
        return queue.removeFirst();
    }
}
