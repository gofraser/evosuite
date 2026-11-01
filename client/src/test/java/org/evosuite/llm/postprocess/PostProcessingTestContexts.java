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
