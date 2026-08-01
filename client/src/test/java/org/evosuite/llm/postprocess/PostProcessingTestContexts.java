package org.evosuite.llm.postprocess;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Short context factories for parser unit tests. */
final class PostProcessingTestContexts {

    private PostProcessingTestContexts() {
        // Utility class.
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds) {
        return context(statementIds, variableIds,
                Collections.<LlmPostProcessingResponseParser.CallableMethod>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(),
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap());
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds,
            Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods) {
        return context(statementIds, variableIds, callableMethods,
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(),
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap());
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds,
            Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods,
            Set<String> observedCandidateKeys) {
        return context(statementIds, variableIds, callableMethods, observedCandidateKeys,
                Collections.<String>emptySet(), Collections.<String, String>emptyMap(),
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap());
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds,
            Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods,
            Set<String> observedCandidateKeys, Set<String> setupInputVariableIds) {
        return context(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, Collections.<String, String>emptyMap(),
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap());
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds,
            Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods,
            Set<String> observedCandidateKeys, Set<String> setupInputVariableIds,
            Map<String, String> variableTypes) {
        return context(statementIds, variableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes,
                Collections.<String, LlmPostProcessingResponseParser.SelectableCandidate>emptyMap());
    }

    static LlmPostProcessingResponseParser.ParseContext context(
            Set<String> statementIds, Set<String> variableIds,
            Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods,
            Set<String> observedCandidateKeys, Set<String> setupInputVariableIds,
            Map<String, String> variableTypes,
            Map<String, LlmPostProcessingResponseParser.SelectableCandidate> selectableCandidates) {
        return LlmPostProcessingResponseParser.context(statementIds, variableIds, callableMethods,
                observedCandidateKeys, setupInputVariableIds, variableTypes,
                selectableCandidates, PostProcessingOptions.fromProperties());
    }
}
